import { ensureIntl } from "./intl-polyfill.mjs";
ensureIntl();

import {
  createStreamingParser,
  createStore,
  evaluate,
  evaluateRoot,
  markReactive,
} from "@openuidev/lang-core";
import { functions } from "./node_modules/@openuidev/intelligent-ui-library/dist/lib/functions/index.mjs";
import schema from "./.generated/library-schema.json";
import reactivePropsMap from "./.generated/reactive-props.json";

const runtimeFunctions = {};
for (const def of functions) {
  const fnSchema = schema.functions?.[def.name];
  if (fnSchema && def.fn) {
    runtimeFunctions[def.name] = { ...fnSchema, fn: def.fn };
  }
}

// Build minimal headless Library with exact reactive prop markers so $binding props evaluate identically to react-lang.
const components = {};
for (const [name, reactiveKeys] of Object.entries(reactivePropsMap)) {
  const shape = {};
  for (const key of reactiveKeys) {
    const marker = {};
    markReactive(marker);
    shape[key] = marker;
  }
  components[name] = {
    name,
    props: { shape },
  };
}
const headlessLibrary = { root: "Col", components };

const sessions = new Map();

function buildEvalContext(store) {
  return {
    getState: (name) => store.get(name),
    resolveRef: () => undefined,
    functions: runtimeFunctions,
    actions: schema.actions,
  };
}

function renderSession(session) {
  if (!session.lastParsed) {
    return { tree: null, meta: null, state: session.store.getSnapshot(), errors: [] };
  }
  const errors = [];
  const ctx = buildEvalContext(session.store);
  const tree = evaluateRoot(session.lastParsed, {
    ctx,
    library: headlessLibrary,
    store: session.store,
    errors,
  });
  return {
    tree,
    meta: session.lastParsed.meta,
    state: session.store.getSnapshot(),
    errors,
  };
}

function getOrCreateSession(sessionId, persistedState) {
  let session = sessions.get(sessionId);
  if (!session) {
    session = {
      parser: createStreamingParser(schema, "Col"),
      store: createStore(),
      lastParsed: null,
      defaults: {},
      persisted: persistedState || {},
    };
    sessions.set(sessionId, session);
  }
  return session;
}

export function pushText(sessionId, fullText, persistedStateJson) {
  const persisted = persistedStateJson ? JSON.parse(persistedStateJson) : {};
  const session = getOrCreateSession(sessionId, persisted);
  session.lastParsed = session.parser.set(fullText);
  const decls = session.lastParsed.stateDeclarations || {};
  session.defaults = decls;
  session.store.initialize(decls, session.persisted);
  return JSON.stringify(renderSession(session));
}

export function setState(sessionId, varName, valueJson) {
  const session = sessions.get(sessionId);
  if (!session) return JSON.stringify({ tree: null, meta: null, state: {}, errors: [] });
  const value = JSON.parse(valueJson);
  session.store.set(varName, value);
  return JSON.stringify(renderSession(session));
}

export function dispatchAction(sessionId, actionJson) {
  const session = sessions.get(sessionId);
  if (!session) return JSON.stringify({ effects: [], tree: null, meta: null, state: {}, errors: [] });
  const action = JSON.parse(actionJson);
  const steps = Array.isArray(action?.steps) ? action.steps : [];
  const effects = [];

  for (const step of steps) {
    if (!step || typeof step !== "object") continue;
    if (step.type === "set" && typeof step.target === "string") {
      const ctx = buildEvalContext(session.store);
      const nextVal = step.valueAST !== undefined
        ? evaluate(step.valueAST, ctx, { library: headlessLibrary })
        : step.value;
      session.store.set(step.target, nextVal);
    } else if (step.type === "reset" && Array.isArray(step.targets)) {
      for (const target of step.targets) {
        if (typeof target === "string") {
          const defVal = Object.prototype.hasOwnProperty.call(session.defaults, target)
            ? session.defaults[target]
            : null;
          session.store.set(target, defVal, { pristine: true });
        }
      }
    } else if (step.type === "open_url" && typeof step.url === "string") {
      effects.push({ type: "open_url", url: step.url });
    } else if (step.type === "continue_conversation") {
      effects.push({
        type: "to_assistant",
        message: typeof step.message === "string" ? step.message : "",
        context: step.context ?? null,
      });
    }
  }

  const rendered = renderSession(session);
  return JSON.stringify({ effects, ...rendered });
}

export function disposeSession(sessionId) {
  const session = sessions.get(sessionId);
  if (session) {
    session.store.dispose();
    sessions.delete(sessionId);
  }
}
