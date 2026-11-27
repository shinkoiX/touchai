package app.touchai.core.openui

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OpenUiEngineTest {
    private fun loadBundleFromProjectAssets(): String {
        val candidates = listOf(
            File("src/main/assets/openui/openui-headless.min.js"),
            File("core/openui/src/main/assets/openui/openui-headless.min.js"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Could not locate openui-headless.min.js in ${File(".").absolutePath}")
        return file.readText()
    }

    @Test
    fun detectorExtractsRawAndFencedPrograms() {
        assertNull(OpenUiDetector.extractProgram("Hello **world**!"))
        val raw = "root = Col([Title(\"Hi\", \"lg\")])"
        assertEquals(raw, OpenUiDetector.extractProgram(raw))

        val fenced = "```openui\n$raw\n```"
        assertEquals(raw, OpenUiDetector.extractProgram(fenced))

        assertTrue(OpenUiDetector.looksLikeStreamingOpenUi("root ="))
        assertTrue(OpenUiDetector.looksLikeStreamingOpenUi("```openui\nroot"))
    }

    @Test
    fun mergeInstructionsRespectsToggle() {
        assertEquals("Be concise.", OpenUiAssets.mergeInstructions("Be concise.", "PROMPT", enabled = false, webSearchEnabled = true))
        assertEquals("PROMPT", OpenUiAssets.mergeInstructions("  ", "PROMPT", enabled = true, webSearchEnabled = false))
        val merged = OpenUiAssets.mergeInstructions("Answer in Chinese.", "PROMPT", enabled = true, webSearchEnabled = false)
        assertTrue(merged.startsWith("PROMPT"))
        assertTrue(merged.contains("Answer in Chinese."))

        val withSearch = OpenUiAssets.mergeInstructions("Answer in Chinese.", "PROMPT", enabled = true, webSearchEnabled = true)
        assertTrue(withSearch.startsWith("## Priority Tool Rule: Web Search First"))
        assertTrue(withSearch.contains("PROMPT"))
        assertTrue(withSearch.contains("## Reminder Before Answering"))
    }

    @Test
    fun quickJsEvaluatesMediaRowReactiveStateActionsAndIntlPolyfill() = runTest {
        val engine = OpenUiEngine(bundleProvider = ::loadBundleFromProjectAssets)
        try {
            val program = """
                root = Col([title, row1, btnInc, btnReset, btnUrl, btnAsk, slider, fmt])
                ${'$'}guests = 4
                title = Title("Pasta night for " + @Plural(${'$'}guests, "guest"), "lg")
                row1 = Row([Box([Image("https://example.com/a.png", "Aneurysm", 1)]), Col([Title("1. Coiling", "md"), Text("Classic treatment", "sm")], 2)], 4, "start")
                btnInc = Button("More", @Set(${'$'}guests, @Clamp(${'$'}guests + 1, 1, 20)))
                btnReset = Button("Reset", @Reset(${'$'}guests))
                btnUrl = Button("Docs", @OpenUrl("https://openui.com"))
                btnAsk = Button("Ask", @ToAssistant("Tell me more", {count: ${'$'}guests}))
                slider = Slider(${'$'}guests, 1, 20, 1, "Guests")
                fmt = Text(@Format(12345.6, "currency", 2, "USD") + " · " + @Format(@Percent(3, 4), "percent") + " · " + @FormatDate("2026-10-09", "date"))
            """.trimIndent()

            val initial = engine.pushText("t1", program)
            assertNull(initial.error)
            val root = requireNotNull(initial.root)
            assertEquals("Col", root.typeName)
            val children = root.props.elementListProp("children")
            assertEquals("Pasta night for 4 guests", children[0].props.stringProp("text"))
            assertEquals("\$guests", children[6].props.bindingTarget("value"))
            assertEquals("\$12,345.60 · 75% · Oct 9, 2026", children[7].props.stringProp("text"))

            // Dispatch @Set action
            val incAction = requireNotNull(children[2].props.objectProp("action"))
            val afterInc = engine.dispatchAction("t1", incAction)
            assertEquals("Pasta night for 5 guests", afterInc.root!!.props.elementListProp("children")[0].props.stringProp("text"))

            // Direct setState (e.g. from Slider)
            val afterSlider = engine.setState("t1", "\$guests", JsonPrimitive(9))
            assertEquals("Pasta night for 9 guests", afterSlider.root!!.props.elementListProp("children")[0].props.stringProp("text"))

            // Dispatch @Reset action
            val resetAction = requireNotNull(children[3].props.objectProp("action"))
            val afterReset = engine.dispatchAction("t1", resetAction)
            assertEquals("Pasta night for 4 guests", afterReset.root!!.props.elementListProp("children")[0].props.stringProp("text"))

            // Dispatch @OpenUrl and @ToAssistant effects
            val urlAction = requireNotNull(children[4].props.objectProp("action"))
            val afterUrl = engine.dispatchAction("t1", urlAction)
            assertEquals(listOf(OpenUiEffect.OpenUrl("https://openui.com")), afterUrl.effects)

            val askAction = requireNotNull(children[5].props.objectProp("action"))
            val afterAsk = engine.dispatchAction("t1", askAction)
            val effect = afterAsk.effects.single() as OpenUiEffect.ToAssistant
            assertEquals("Tell me more", effect.message)
            assertNotNull(effect.context)

            engine.disposeSession("t1")
        } finally {
            engine.close()
        }
    }
}
