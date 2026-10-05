package app.touchai.android

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RequestLogPreviewTest {
    @Test fun displaySummaryIsBoundedWithoutChangingTheStoredOutput() {
        val record = buildJsonObject {
            put("text", "Generated an image")
            put("result", "A".repeat(4_000_000))
            putJsonArray("events") { repeat(500) { add("An event ".repeat(50)) } }
        }
        val preview = requestLogPreviewText(record)
        assertTrue(preview.length <= 64_002)
        assertTrue(preview.contains("Generated an image"))
        assertTrue(preview.contains("4000000 characters"))
        assertEquals(4_000_000, record.getValue("result").jsonPrimitive.content.length)
        assertEquals(500, record.getValue("events").jsonArray.size)
    }
}
