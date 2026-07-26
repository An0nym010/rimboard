package com.rimboard.keyboard.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * MyMemory response parsing.
 *
 * The cases worth pinning are the ones where the request "succeeded" at the
 * HTTP level and the body is still not a usable translation — MyMemory reports
 * failure inside a 200, and puts the error message where the translation
 * belongs, so committing it blind would type "INVALID LANGUAGE PAIR" into the
 * user's field.
 */
class MyMemoryTest {

    @Test
    fun `pulls the translation out of the envelope`() {
        val json = """
            {"responseData":{"translatedText":"günaydın","match":1},
             "responseStatus":200}
        """.trimIndent()
        assertEquals("günaydın", MyMemory.parse(json))
    }

    @Test
    fun `a string responseStatus is read as leniently as a numeric one`() {
        // MyMemory returns responseStatus as a number on success and a string
        // on some responses; both must be understood.
        val ok = """{"responseData":{"translatedText":"hola"},"responseStatus":"200"}"""
        assertEquals("hola", MyMemory.parse(ok))
    }

    @Test
    fun `an error status is raised, not committed as the translation`() {
        // The failure mode that matters: the message sits in translatedText.
        val json = """
            {"responseData":{"translatedText":"INVALID LANGUAGE PAIR"},
             "responseStatus":403,"responseDetails":"invalid language pair"}
        """.trimIndent()
        val e = assertThrows(MyMemory.Error.Api::class.java) { MyMemory.parse(json) }
        assertEquals("invalid language pair", e.message)
    }

    @Test
    fun `HTML entities in the translation are decoded`() {
        // MyMemory encodes apostrophes and quotes; raw they would be typed as
        // "&#39;" into the field.
        val json = """{"responseData":{"translatedText":"c&#39;est l&#39;été"},"responseStatus":200}"""
        assertEquals("c'est l'été", MyMemory.parse(json))
    }

    @Test
    fun `an amp entity is decoded once, not twice`() {
        val json = """{"responseData":{"translatedText":"Ben &amp; Jerry"},"responseStatus":200}"""
        assertEquals("Ben & Jerry", MyMemory.parse(json))
    }

    @Test
    fun `an empty translation is an error rather than a blank commit`() {
        val json = """{"responseData":{"translatedText":""},"responseStatus":200}"""
        assertThrows(MyMemory.Error.Api::class.java) { MyMemory.parse(json) }
    }

    @Test
    fun `the endpoint host is on the allowlist`() {
        assertTrue(Net.hostAllowed(Net.hostOf("https://api.mymemory.translated.net/get?q=x")))
    }
}

private fun assertTrue(b: Boolean) = org.junit.Assert.assertTrue(b)
