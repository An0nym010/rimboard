package com.rimboard.keyboard.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the translation sources that can be tested without a device:
 * response parsing, URL construction, and the hostname rule that decides what
 * the network gate will let through.
 */
class TranslateSourcesTest {

    // ---------------------------------------------------------------- Lingva

    @Test
    fun `pulls the translation out of the Lingva envelope`() {
        assertEquals("Günaydın", Lingva.parse("""{"translation":"Günaydın"}"""))
    }

    @Test
    fun `an error at HTTP 200 is raised, not committed as the translation`() {
        // The failure that matters: an instance that is up but unhappy answers
        // 200 with an error body, and returning "" would leave the bar silent.
        val e = assertThrows(Translate.Error.Api::class.java) {
            Lingva.parse("""{"error":"An error occurred while retrieving the translation"}""")
        }
        assertTrue(e.message!!.contains("error occurred"))
    }

    @Test
    fun `an empty Lingva translation is an error rather than a blank commit`() {
        assertThrows(Translate.Error.Api::class.java) { Lingva.parse("""{"translation":""}""") }
    }

    @Test
    fun `a space becomes percent-20 rather than a plus sign`() {
        // Form encoding would put a literal "+" in the path, and the service
        // would faithfully translate the plus signs along with the words.
        assertEquals("good%20morning", Lingva.seg("good morning"))
    }

    @Test
    fun `a slash in the text cannot become a path separator`() {
        // The text is a path segment, so an unescaped slash would not be a
        // malformed request — it would be a request to a different endpoint.
        val encoded = Lingva.seg("a/b?c=d#e")
        assertFalse(encoded.contains('/'))
        assertFalse(encoded.contains('?'))
        assertFalse(encoded.contains('#'))
    }

    @Test
    fun `non-ascii survives encoding`() {
        assertEquals("g%C3%BCnayd%C4%B1n", Lingva.seg("günaydın"))
    }

    // --------------------------------------------------------- LibreTranslate

    @Test
    fun `pulls the translation out of the LibreTranslate envelope`() {
        assertEquals(
            "hola",
            LibreTranslate.parse("""{"translatedText":"hola","detectedLanguage":{"language":"en"}}""")
        )
    }

    @Test
    fun `the keyless portal message is an error, not a translation`() {
        // What the public instance now answers without a key. Committed blind,
        // this would type a signup URL into the user's message.
        val e = assertThrows(Translate.Error.Api::class.java) {
            LibreTranslate.parse("""{"error":"Visit https://portal.libretranslate.com to get an API key"}""")
        }
        assertTrue(e.message!!.contains("portal"))
    }

    // ------------------------------------------------------------ host rules

    @Test
    fun `the default endpoints are on the allowlist`() {
        assertTrue(Net.hostAllowed(Net.hostOf("https://${Lingva.DEFAULT_HOST}/api/v1/auto/tr/hi")))
        assertTrue(Net.hostAllowed(Net.hostOf("https://${LibreTranslate.DEFAULT_HOST}/translate")))
    }

    @Test
    fun `the dead MyMemory endpoint is no longer reachable`() {
        assertFalse(Net.hostAllowed("api.mymemory.translated.net"))
    }

    @Test
    fun `a self-hosted instance is allowed only as an exact match`() {
        val mine = "translate.example.com"
        assertTrue(Net.hostAllowed("translate.example.com", mine))
        // Not a suffix rule: the static allowlist widens by domain for a
        // service that spreads media over many hosts, but a user-typed host
        // must not quietly authorise everything beneath it.
        assertFalse(Net.hostAllowed("evil.translate.example.com", mine))
        assertFalse(Net.hostAllowed("translate.example.com.evil.test", mine))
        assertFalse(Net.hostAllowed("translate.example.com", null))
    }

    @Test
    fun `hostnames that are really something else are refused`() {
        assertFalse(Translate.isHost("user@evil.test"))
        assertFalse(Translate.isHost("good.test/../evil"))
        assertFalse(Translate.isHost("good.test:8080"))
        assertFalse(Translate.isHost("https://good.test"))
        assertFalse(Translate.isHost("nodot"))
        assertFalse(Translate.isHost(".leading.test"))
        assertFalse(Translate.isHost("trailing.test."))
        assertFalse(Translate.isHost("double..test"))
        assertFalse(Translate.isHost(""))
        assertTrue(Translate.isHost("translate.example.com"))
        assertTrue(Translate.isHost("my-box.local.test"))
    }

    @Test
    fun `plaintext is refused whatever the host`() {
        // hostOf returns null for anything but https, so a self-hosted instance
        // cannot be reached over http either.
        assertFalse(Net.hostAllowed(Net.hostOf("http://translate.example.com/"), "translate.example.com"))
    }
}
