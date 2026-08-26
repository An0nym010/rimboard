package com.rimboard.keyboard

import com.rimboard.keyboard.net.Net
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The clause of the network promise that had nothing enforcing it.
 *
 * The settings screen says, above the switch that turns the network on:
 * "Nothing is sent in the background, in incognito, or in a password field."
 * Two of those three were real. [Net.blockedBy] refused a request carrying
 * typed text when either incognito preference was set, and nothing in the app
 * starts a request the user did not ask for. The third had no code behind it:
 * `Net` read the two preferences itself and never learned what kind of field
 * was focused, so a password field was, to the network gate, an ordinary one.
 *
 * That is not a theoretical gap. Two of the three tools seed themselves from
 * the field and send on the spot, with no search typed and no confirmation:
 *
 *  - the GIF panel takes the sixty characters before the cursor as its opening
 *    query (`showGifPanel` -> `textBeforeCursorSeed()` -> `runGifSearch`), so
 *    tapping GIF while a password is in the field sent that password to
 *    api.klipy.com as a search term;
 *  - the translate bar starts on the selection, and `showTranslatePanel`'s own
 *    note says "starting it with a selection sends a request there and then",
 *    so selecting a password and tapping the globe sent it to the translation
 *    service;
 *  - proofread and rewrite act on the selection the same way.
 *
 * The keyboard already refuses to *learn* in a password field, refuses to
 * autocorrect in one, and since the swipe fix refuses to decode a glide in one.
 * Sending its contents to a third party was the one thing left that it would
 * still do.
 */
class PasswordFieldTest {

    /** Everything permitting, which is the case the field flag has to survive. */
    private fun decide(sensitive: Boolean, sendsTypedText: Boolean) = Net.decide(
        capable = true, online = true, incognito = false,
        sensitiveField = sensitive, sendsTypedText = sendsTypedText
    )

    @Test
    fun `a password field refuses a request carrying typed text`() {
        assertEquals(
            "a password in the field can be sent to a third-party service: the " +
                "GIF panel seeds its search from the sixty characters before " +
                "the cursor and searches immediately",
            Net.Block.SENSITIVE_FIELD,
            decide(sensitive = true, sendsTypedText = true)
        )
    }

    @Test
    fun `an ordinary field still sends`() {
        // The other half. This must narrow the password case and nothing else:
        // a gate that refuses everywhere is not a fix, it is a removal.
        assertNull(decide(sensitive = false, sendsTypedText = true))
    }

    @Test
    fun `a request that carries nothing typed is unaffected`() {
        // A GIF thumbnail is fetched by the URL the provider returned, and a
        // dictionary by a URL already in the APK. Neither carries a keystroke,
        // and refusing them would blank the grid rather than protect anything
        // -- which is the distinction `sendsTypedText` exists to draw.
        assertNull(decide(sensitive = true, sendsTypedText = false))
    }

    @Test
    fun `the older refusals still come first`() {
        // Order matters for the message the user gets: a build with no
        // permission must say so rather than blaming the field, because the
        // fixes are different and only one of them exists.
        assertEquals(
            Net.Block.NO_PERMISSION,
            Net.decide(
                capable = false, online = true, incognito = false,
                sensitiveField = true, sendsTypedText = true
            )
        )
        assertEquals(
            Net.Block.USER_OFFLINE,
            Net.decide(
                capable = true, online = false, incognito = false,
                sensitiveField = true, sendsTypedText = true
            )
        )
        assertEquals(
            Net.Block.INCOGNITO,
            Net.decide(
                capable = true, online = true, incognito = true,
                sensitiveField = true, sendsTypedText = true
            )
        )
    }

    /** Unit tests run from the module directory; tolerate the project root too. */
    private fun src(): File =
        listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    /**
     * The flag is worthless unless something sets it.
     *
     * A pure decision table is easy to keep green while the one caller that
     * feeds it real state quietly stops doing so, which would leave every
     * assertion above passing over a keyboard that still sends passwords.
     */
    /**
     * Nothing may take the whole field without asking what kind of field it is.
     *
     * `getSelectedText` needs the user to have selected something;
     * `getExtractedText` is the call that returns the lot, and it is the one
     * that turned a password field into a share sheet. Written as a scan rather
     * than as two assertions about two functions, so that a third feature
     * reaching for the same call has to answer the same question.
     */
    @Test
    fun `nothing reads the whole field without checking it first`() {
        val svc = src().resolve("com/rimboard/keyboard/RimBoardService.kt").readText()
        val decls = Regex("\n    (?:private |override )?fun ([A-Za-z]+)")
            .findAll(svc).map { it.range.first to it.groupValues[1] }.toList()
        val unguarded = mutableListOf<String>()
        for (m in Regex("getExtractedText").findAll(svc)) {
            val at = m.range.first
            val decl = decls.lastOrNull { it.first < at } ?: continue
            if (!svc.substring(decl.first, at).contains("refusedInPasswordField()")) {
                unguarded.add(decl.second)
            }
        }
        assertTrue(
            "these read the entire field and hand it somewhere without asking " +
                "whether it is a password field: " + unguarded.joinToString(", "),
            unguarded.isEmpty()
        )
    }

    @Test
    fun `the service tells the gate what kind of field is focused`() {
        val svc = src().resolve("com/rimboard/keyboard/RimBoardService.kt").readText()
        assertTrue(
            "nothing calls Net.setSensitiveField, so the gate believes every " +
                "field is an ordinary one",
            svc.contains("Net.setSensitiveField(")
        )
        assertTrue(
            "the flag is set but never cleared, so it describes whichever " +
                "field was focused last rather than the one focused now",
            Regex("""setSensitiveField\(""").findAll(svc).count() >= 2
        )
    }
}
