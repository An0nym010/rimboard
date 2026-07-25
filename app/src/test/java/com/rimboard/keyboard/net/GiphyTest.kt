package com.rimboard.keyboard.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Giphy response parsing, and the allowlist rule its CDN forced.
 *
 * This replaced a Tenor client that could never have worked for anyone new:
 * Google closed Tenor to new API clients in January 2026. The lesson worth
 * keeping is in the allowlist tests below rather than in this one.
 */
class GiphyTest {

    private val twoResults = """
        {"data":[
          {"id":"1","title":"cat waving","images":{
             "fixed_width_small":{"url":"https://media0.giphy.com/a/small.gif"},
             "original":{"url":"https://media0.giphy.com/a/original.gif"}}},
          {"id":"2","title":"dog","images":{
             "fixed_width_small":{"url":"https://media3.giphy.com/b/small.gif"},
             "original":{"url":"https://media3.giphy.com/b/original.gif"}}}
        ],"meta":{"status":200,"msg":"OK"}}
    """.trimIndent()

    @Test
    fun `parses id, title and both urls`() {
        val gifs = Giphy.parse(twoResults)
        assertEquals(2, gifs.size)
        assertEquals("1", gifs[0].id)
        assertEquals("cat waving", gifs[0].description)
        assertEquals("https://media0.giphy.com/a/small.gif", gifs[0].previewUrl)
        assertEquals("https://media0.giphy.com/a/original.gif", gifs[0].gifUrl)
    }

    @Test
    fun `falls back through the rendition preference order`() {
        // Not every result carries every rendition, and the ones that are
        // missing vary by result rather than by request.
        val json = """
            {"data":[{"id":"1","images":{
               "fixed_width":{"url":"https://media1.giphy.com/c/fw.gif"},
               "downsized":{"url":"https://media1.giphy.com/c/down.gif"}}}]}
        """.trimIndent()
        val gif = Giphy.parse(json).single()
        assertEquals("https://media1.giphy.com/c/fw.gif", gif.previewUrl)
        assertEquals("https://media1.giphy.com/c/down.gif", gif.gifUrl)
    }

    @Test
    fun `drops results with no usable rendition`() {
        // A tile that cannot draw, or cannot be sent once tapped, is worse
        // than one fewer result.
        val json = """{"data":[{"id":"1","images":{}},{"id":"2"}]}"""
        assertTrue(Giphy.parse(json).isEmpty())
    }

    @Test
    fun `an error envelope is raised rather than read as no results`() {
        // Giphy reports some failures with HTTP 200 and an empty data array,
        // which would otherwise show as "no GIFs found" for a bad key.
        val json = """{"meta":{"status":403,"msg":"Invalid authentication credentials"},"data":[]}"""
        val e = assertThrows(Giphy.GifError.Api::class.java) { Giphy.parse(json) }
        assertTrue(e.message!!.contains("Invalid authentication"))
    }

    @Test
    fun `a blank title still gives the tile an accessibility label`() {
        val json = """
            {"data":[{"id":"1","title":"","images":{
               "fixed_width_small":{"url":"https://i.giphy.com/x.gif"},
               "original":{"url":"https://i.giphy.com/y.gif"}}}]}
        """.trimIndent()
        assertEquals("GIF", Giphy.parse(json).single().description)
    }

    // ---- the allowlist rule Giphy's CDN forced ----

    @Test
    fun `every giphy host the CDN can pick is allowed`() {
        // The host varies per result, so an exact list would fail as a blank
        // grid the first time Giphy added a machine.
        for (h in listOf(
            "api.giphy.com", "media.giphy.com", "media0.giphy.com",
            "media4.giphy.com", "media9.giphy.com", "i.giphy.com", "giphy.com"
        )) {
            assertTrue("$h should be allowed", Net.hostAllowed(h))
        }
    }

    @Test
    fun `the suffix rule cannot be walked around`() {
        // The two ways a suffix check goes wrong: no dot boundary lets a
        // lookalike domain in, and a substring test lets the name appear
        // anywhere at all.
        assertFalse(Net.hostAllowed("evilgiphy.com"))
        assertFalse(Net.hostAllowed("giphy.com.evil.test"))
        assertFalse(Net.hostAllowed("notgiphy.com"))
        assertFalse(Net.hostAllowed("giphy.evil.test"))
        assertFalse(Net.hostAllowed("api.giphy.com.evil.test"))
        assertFalse(Net.hostAllowed(null))
    }

    @Test
    fun `unrelated hosts stay out`() {
        assertFalse(Net.hostAllowed("example.test"))
        assertFalse(Net.hostAllowed("api.openai.com"))
        // Tenor is gone; its hosts should not have survived the swap.
        assertFalse(Net.hostAllowed("tenor.googleapis.com"))
        assertFalse(Net.hostAllowed("media.tenor.com"))
    }

    @Test
    fun `the endpoints actually used are allowed and https only`() {
        assertTrue(Net.hostAllowed(Net.hostOf("https://api.giphy.com/v1/gifs/search?q=cat")))
        assertTrue(Net.hostAllowed(Net.hostOf("https://api.anthropic.com/v1/messages")))
        // Plaintext is refused before the host is even considered.
        assertFalse(Net.hostAllowed(Net.hostOf("http://api.giphy.com/v1/gifs/search")))
    }
}
