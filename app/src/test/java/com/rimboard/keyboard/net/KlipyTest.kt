package com.rimboard.keyboard.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KLIPY response parsing, the allowlist rule its CDN needs, and the sharp edge
 * created by putting the API key in the URL path.
 *
 * Third provider in this file's history: Tenor closed to new clients, Giphy
 * moved production to paid. The tests worth keeping are the ones about the
 * gate rather than about any one vendor's JSON.
 */
class KlipyTest {

    private val twoResults = """
        {"result":true,"data":{"data":[
          {"id":8041071659142944,"slug":"hello-hi-662","title":"Hello","file":{
             "hd":{"gif":{"url":"https://static.klipy.com/a/hd.gif"}},
             "md":{"gif":{"url":"https://static.klipy.com/a/md.gif"}},
             "sm":{"gif":{"url":"https://static1.klipy.com/a/sm.gif"}},
             "xs":{"gif":{"url":"https://static2.klipy.com/a/xs.gif"}}}},
          {"id":12,"slug":"dog","title":"Dog","file":{
             "md":{"gif":{"url":"https://static.klipy.com/b/md.gif"}},
             "xs":{"gif":{"url":"https://static.klipy.com/b/xs.gif"}}}}
        ]}}
    """.trimIndent()

    @Test
    fun `unwraps the nested data envelope and picks the right sizes`() {
        val gifs = Klipy.parse(twoResults)
        assertEquals(2, gifs.size)
        assertEquals("Hello", gifs[0].description)
        // xs for the grid, md for insertion — not hd, which averages ~4 MB.
        assertEquals("https://static2.klipy.com/a/xs.gif", gifs[0].previewUrl)
        assertEquals("https://static.klipy.com/a/md.gif", gifs[0].gifUrl)
    }

    @Test
    fun `a numeric id survives as a string`() {
        // id is a JSON number here; reading it with optString returns "" on
        // some parsers, which would break thumbnail matching in the grid since
        // that is keyed on id.
        assertEquals("8041071659142944", Klipy.parse(twoResults)[0].id)
        assertEquals("12", Klipy.parse(twoResults)[1].id)
    }

    @Test
    fun `a rejected request is raised rather than read as no results`() {
        // The envelope carries its own success flag, so a bad key can arrive as
        // HTTP 200 with no data — which would otherwise show "No GIFs found"
        // and send the user hunting for a better search term.
        val json = """{"result":false,"message":"Invalid app key"}"""
        val e = assertThrows(Klipy.GifError.Api::class.java) { Klipy.parse(json) }
        assertTrue(e.message!!.contains("Invalid app key"))
    }

    @Test
    fun `results with no usable rendition are dropped`() {
        val json = """{"result":true,"data":{"data":[{"id":1,"file":{}},{"id":2}]}}"""
        assertTrue(Klipy.parse(json).isEmpty())
    }

    @Test
    fun `an empty result set is not an error`() {
        assertTrue(Klipy.parse("""{"result":true,"data":{"data":[]}}""").isEmpty())
        assertTrue(Klipy.parse("""{"result":true}""").isEmpty())
    }

    @Test
    fun `a blank title still gives the tile an accessibility label`() {
        val json = """
            {"result":true,"data":{"data":[{"id":1,"title":"","file":{
               "xs":{"gif":{"url":"https://static.klipy.com/x.gif"}},
               "md":{"gif":{"url":"https://static.klipy.com/y.gif"}}}}]}}
        """.trimIndent()
        assertEquals("GIF", Klipy.parse(json).single().description)
    }

    // ---- the key is a path segment, which is the risky part ----

    @Test
    fun `a key that could change the request path is refused`() {
        // KLIPY takes the key as a path segment, so a slash in it would not
        // produce a broken request — it would address a *different* endpoint on
        // a host the gate has already allowed, which the allowlist cannot catch.
        assertFalse(Klipy.isSafeKey("abc/../../admin"))
        assertFalse(Klipy.isSafeKey("abc/gifs/trending"))
        assertFalse(Klipy.isSafeKey("abc?x=1"))
        assertFalse(Klipy.isSafeKey("abc#frag"))
        assertFalse(Klipy.isSafeKey("abc def"))
        assertFalse(Klipy.isSafeKey(""))
    }

    @Test
    fun `keys of the shape an API actually issues are accepted`() {
        assertTrue(Klipy.isSafeKey("abc123DEF456"))
        assertTrue(Klipy.isSafeKey("live_key-123_456"))
    }

    // ---- allowlist ----

    @Test
    fun `every klipy host the docs list is allowed`() {
        for (h in listOf(
            "api.klipy.com", "static.klipy.com", "static1.klipy.com",
            "static2.klipy.com", "static3.klipy.com", "klipy.com"
        )) {
            assertTrue("$h should be allowed", Net.hostAllowed(h))
        }
    }

    @Test
    fun `the suffix rule cannot be walked around`() {
        // The two ways a suffix check goes wrong: no dot boundary lets a
        // lookalike in, and a substring test lets the name appear anywhere.
        assertFalse(Net.hostAllowed("evilklipy.com"))
        assertFalse(Net.hostAllowed("klipy.com.evil.test"))
        assertFalse(Net.hostAllowed("api.klipy.com.evil.test"))
        assertFalse(Net.hostAllowed("klipy.evil.test"))
        assertFalse(Net.hostAllowed(null))
    }

    @Test
    fun `providers that are gone stay gone`() {
        assertFalse(Net.hostAllowed("tenor.googleapis.com"))
        assertFalse(Net.hostAllowed("media.tenor.com"))
        assertFalse(Net.hostAllowed("api.giphy.com"))
        assertFalse(Net.hostAllowed("media0.giphy.com"))
    }

    @Test
    fun `the endpoints actually used are allowed and https only`() {
        assertTrue(Net.hostAllowed(Net.hostOf("https://api.klipy.com/api/v1/KEY/gifs/search?q=cat")))
        assertTrue(Net.hostAllowed(Net.hostOf("https://api.anthropic.com/v1/messages")))
        assertFalse(Net.hostAllowed(Net.hostOf("http://api.klipy.com/api/v1/KEY/gifs/search")))
    }
}
