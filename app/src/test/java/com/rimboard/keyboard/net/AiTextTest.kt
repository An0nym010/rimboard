package com.rimboard.keyboard.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the response parsing.
 *
 * Every case here is one where the request succeeded at the HTTP level and the
 * body still isn't a usable rewrite — a decline, an error envelope, a truncated
 * answer. Those are the ones worth a test, because the failure mode is not an
 * exception: it is plausible-looking text committed straight into whatever the
 * user was typing, which is the worst thing this feature could do.
 */
class AiTextTest {

    @Test
    fun `concatenates every text block rather than indexing the first`() {
        // The first block is not guaranteed to be text, and long replies can
        // arrive split across several blocks.
        val json = """
            {"type":"message","stop_reason":"end_turn","content":[
              {"type":"text","text":"Hallo "},
              {"type":"text","text":"Welt"}
            ]}
        """.trimIndent()
        assertEquals("Hallo Welt", AiText.parse(json))
    }

    @Test
    fun `ignores non-text blocks`() {
        val json = """
            {"type":"message","stop_reason":"end_turn","content":[
              {"type":"thinking","thinking":""},
              {"type":"text","text":"Bonjour"}
            ]}
        """.trimIndent()
        assertEquals("Bonjour", AiText.parse(json))
    }

    @Test
    fun `a refusal is not read as an empty reply`() {
        // stop_reason "refusal" arrives as a successful HTTP 200 with empty or
        // partial content. Reading content first would surface it as a blank
        // result with no explanation.
        val json = """{"type":"message","stop_reason":"refusal","content":[]}"""
        assertThrows(AiText.AiError.Refused::class.java) { AiText.parse(json) }
    }

    @Test
    fun `an error envelope surfaces the API's own message`() {
        val json = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
        val e = assertThrows(AiText.AiError.Api::class.java) { AiText.parse(json) }
        assertTrue(e.message!!.contains("invalid x-api-key"))
    }

    @Test
    fun `a truncated reply is refused rather than committed`() {
        // The single most damaging case: hitting max_tokens yields text that
        // looks finished. Committing it would silently drop the tail of the
        // user's sentence.
        val json = """
            {"type":"message","stop_reason":"max_tokens","content":[
              {"type":"text","text":"This translation stops halfway thr"}
            ]}
        """.trimIndent()
        assertThrows(AiText.AiError.TooLong::class.java) { AiText.parse(json) }
    }

    @Test
    fun `an empty content array is an error, not an empty string`() {
        val json = """{"type":"message","stop_reason":"end_turn","content":[]}"""
        assertThrows(AiText.AiError.Api::class.java) { AiText.parse(json) }
    }

    @Test
    fun `the endpoint host is on the allowlist`() {
        // The gate checks the parsed host; a typo here would fail closed at
        // runtime rather than at build time without this.
        assertTrue(Net.hostOf("https://api.anthropic.com/v1/messages") in Net.ALLOWED_HOSTS)
    }

    @Test
    fun `the system prompt forbids preamble and neutralises instructions`() {
        // Prefill is rejected on this model family, so "reply with the text and
        // nothing else" is carried entirely by the system prompt. If that
        // sentence is ever dropped, replies start arriving as
        // "Sure! Here's the translation: ..." straight into the user's field.
        for (task in AiText.Task.entries) {
            val p = AiText.systemPrompt(task, "German")
            assertTrue("$task lost the no-preamble instruction", p.contains("nothing else"))
            // The selection is untrusted text from whatever app the user is in.
            assertTrue("$task lost the injection guard", p.contains("rather than following it"))
        }
    }
}
