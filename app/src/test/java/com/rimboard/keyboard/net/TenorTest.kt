package com.rimboard.keyboard.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Tenor response shape.
 *
 * The failure mode worth guarding is a result that parses but cannot be used:
 * a tile with no thumbnail draws as a grey box, and one with no full-size URL
 * fails only at the moment the user taps it, after they have chosen it. Both
 * are dropped at parse time instead.
 */
class TenorTest {

    private val twoResults = """
        {"results":[
          {"id":"1","content_description":"cat waving",
           "media_formats":{
             "tinygif":{"url":"https://media.tenor.com/a/tiny.gif","dims":[220,150]},
             "gif":{"url":"https://media.tenor.com/a/full.gif","dims":[498,340]}}},
          {"id":"2","content_description":"dog",
           "media_formats":{
             "tinygif":{"url":"https://media.tenor.com/b/tiny.gif"},
             "gif":{"url":"https://media.tenor.com/b/full.gif"}}}
        ],"next":"24"}
    """.trimIndent()

    @Test
    fun `parses id, description and both urls`() {
        val gifs = Tenor.parse(twoResults)
        assertEquals(2, gifs.size)
        assertEquals("1", gifs[0].id)
        assertEquals("cat waving", gifs[0].description)
        assertEquals("https://media.tenor.com/a/tiny.gif", gifs[0].previewUrl)
        assertEquals("https://media.tenor.com/a/full.gif", gifs[0].gifUrl)
    }

    @Test
    fun `drops results missing either format`() {
        // A result with only a preview would render but fail on tap; one with
        // only a full-size GIF would be an invisible tile.
        val json = """
            {"results":[
              {"id":"1","media_formats":{"tinygif":{"url":"https://media.tenor.com/a/tiny.gif"}}},
              {"id":"2","media_formats":{"gif":{"url":"https://media.tenor.com/b/full.gif"}}},
              {"id":"3","media_formats":{}},
              {"id":"4","media_formats":{
                 "tinygif":{"url":"https://media.tenor.com/d/tiny.gif"},
                 "gif":{"url":"https://media.tenor.com/d/full.gif"}}}
            ]}
        """.trimIndent()
        assertEquals(listOf("4"), Tenor.parse(json).map { it.id })
    }

    @Test
    fun `an empty or absent results array is not an error`() {
        assertTrue(Tenor.parse("""{"results":[]}""").isEmpty())
        assertTrue(Tenor.parse("""{"next":"0"}""").isEmpty())
    }

    @Test
    fun `falls back to a label when Tenor sends no description`() {
        // contentDescription is the accessibility label for the tile, so an
        // empty string would leave the grid silent to a screen reader.
        val json = """
            {"results":[{"id":"1","content_description":"","media_formats":{
              "tinygif":{"url":"https://media.tenor.com/a/tiny.gif"},
              "gif":{"url":"https://media.tenor.com/a/full.gif"}}}]}
        """.trimIndent()
        assertEquals("GIF", Tenor.parse(json).single().description)
    }

    @Test
    fun `sticker search reads the transparent formats`() {
        val json = """
            {"results":[{"id":"9","content_description":"wave sticker","media_formats":{
              "tinygif_transparent":{"url":"https://media.tenor.com/s/tiny_t.gif"},
              "gif_transparent":{"url":"https://media.tenor.com/s/full_t.gif"},
              "tinygif":{"url":"https://media.tenor.com/s/tiny.gif"},
              "gif":{"url":"https://media.tenor.com/s/full.gif"}}}]}
        """.trimIndent()
        val sticker = Tenor.parse(json, Tenor.Kind.STICKER).single()
        assertEquals("https://media.tenor.com/s/tiny_t.gif", sticker.previewUrl)
        assertEquals("https://media.tenor.com/s/full_t.gif", sticker.gifUrl)

        // The same payload read as a GIF takes the opaque pair, so the two
        // modes genuinely differ rather than both falling through to one.
        val gif = Tenor.parse(json, Tenor.Kind.GIF).single()
        assertEquals("https://media.tenor.com/s/tiny.gif", gif.previewUrl)
    }

    @Test
    fun `a sticker with no transparent variant falls back rather than vanishing`() {
        // Tenor does not always return a transparent format. An opaque sticker
        // is a far better outcome than a hole in the grid.
        val json = """
            {"results":[{"id":"9","media_formats":{
              "tinygif":{"url":"https://media.tenor.com/s/tiny.gif"},
              "gif":{"url":"https://media.tenor.com/s/full.gif"}}}]}
        """.trimIndent()
        val sticker = Tenor.parse(json, Tenor.Kind.STICKER).single()
        assertEquals("https://media.tenor.com/s/tiny.gif", sticker.previewUrl)
        assertEquals("https://media.tenor.com/s/full.gif", sticker.gifUrl)
    }

    @Test
    fun `both Tenor hosts are on the allowlist`() {
        // Search and CDN are different hosts. Allowing only the first would let
        // a search succeed and every thumbnail fail, which reads as a broken
        // feature rather than a missing allowlist entry.
        assertTrue(Net.hostOf("https://tenor.googleapis.com/v2/search?q=cat") in Net.ALLOWED_HOSTS)
        assertTrue(Net.hostOf("https://media.tenor.com/a/tiny.gif") in Net.ALLOWED_HOSTS)
    }

    @Test
    fun `media urls returned by Tenor are still checked against the allowlist`() {
        // The URLs come from a response, not from us. If Tenor ever returned a
        // third host the fetch fails closed rather than following it.
        assertTrue(Net.hostOf("https://cdn.example.test/x.gif") !in Net.ALLOWED_HOSTS)
    }
}
