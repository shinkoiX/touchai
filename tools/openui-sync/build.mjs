import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";
import * as esbuild from "esbuild";
import * as iui from "@openuidev/intelligent-ui-library";
import { isReactiveSchema } from "@openuidev/lang-core";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, "../..");
const genDir = path.join(__dirname, ".generated");
const outAssetsDir = path.join(repoRoot, "core/openui/src/main/assets/openui");

fs.mkdirSync(genDir, { recursive: true });
fs.mkdirSync(outAssetsDir, { recursive: true });

const lib = iui.library;
const schema = lib.toJSONSchema();

// Extract exact reactive ($binding) prop keys per component from the official Zod schemas.
const reactiveProps = {};
for (const [name, comp] of Object.entries(lib.components)) {
  const shape = comp?.props?.shape || {};
  const keys = [];
  for (const [propKey, propSchema] of Object.entries(shape)) {
    if (isReactiveSchema(propSchema)) keys.push(propKey);
  }
  if (keys.length > 0) reactiveProps[name] = keys;
}

fs.writeFileSync(path.join(genDir, "library-schema.json"), JSON.stringify(schema), "utf8");
fs.writeFileSync(path.join(genDir, "reactive-props.json"), JSON.stringify(reactiveProps, null, 2), "utf8");
fs.writeFileSync(path.join(genDir, "ci-info-stub.cjs"), "module.exports = { isCI: false };\n", "utf8");

// Export official system prompt verbatim.
const systemPrompt = lib.prompt(iui.promptOptions);
fs.writeFileSync(path.join(outAssetsDir, "intelligent-ui-system-prompt.txt"), systemPrompt, "utf8");

// Bundle headless runtime IIFE for QuickJS (zero DOM, zero React, zero Node APIs).
const outBundle = path.join(outAssetsDir, "openui-headless.min.js");
await esbuild.build({
  entryPoints: [path.join(__dirname, "headless-entry.mjs")],
  bundle: true,
  format: "iife",
  globalName: "OpenUIHeadless",
  minify: true,
  target: ["es2020"],
  outfile: outBundle,
  alias: {
    "ci-info": path.join(genDir, "ci-info-stub.cjs"),
  },
});

// Verify bundle inside a bare VM with NO DOM, NO Node, and NO native Intl (simulating QuickJS).
const bundleSrc = fs.readFileSync(outBundle, "utf8");
const sandbox = {
  console,
  Math,
  Date,
  JSON,
  Object,
  Array,
  String,
  Number,
  Boolean,
  RegExp,
  Map,
  Set,
  Error,
  TypeError,
  RangeError,
  SyntaxError,
  parseInt,
  parseFloat,
  isNaN,
  isFinite,
  encodeURIComponent,
  decodeURIComponent,
};
sandbox.globalThis = sandbox;
vm.runInNewContext(bundleSrc, sandbox);

const api = sandbox.OpenUIHeadless;
const ex1 = iui.examples[0].split("\n\n").slice(1).join("\n\n");
const r1 = JSON.parse(api.pushText("s1", ex1));
const incAction = r1.tree.props.children[1].props.children[0].props.children[2].props.action;
const r2 = JSON.parse(api.dispatchAction("s1", JSON.stringify(incAction)));
const r3 = JSON.parse(api.setState("s1", "$guests", "10"));
const rFmt = JSON.parse(api.pushText("s2", `root = Col([Text(@Format(12345.6, "currency", 2, "USD") + " · " + @Format(@Percent(3, 4), "percent") + " · " + @FormatDate("2026-10-09", "date"))])`));

console.log("Generated assets:");
console.log(" - intelligent-ui-system-prompt.txt:", systemPrompt.length, "chars");
console.log(" - openui-headless.min.js:", (bundleSrc.length / 1024).toFixed(1), "KB");
console.log(" - reactiveProps:", reactiveProps);
console.log("QuickJS-like VM checks:");
console.log(" - initial title:", r1.tree.props.children[0].props.text);
console.log(" - after +1 @Set action:", r2.tree.props.children[0].props.text);
console.log(" - after setState(10):", r3.tree.props.children[0].props.text);
console.log(" - Intl polyfill @Format/@FormatDate:", rFmt.tree.props.children[0].props.text);
api.disposeSession("s1");
api.disposeSession("s2");
