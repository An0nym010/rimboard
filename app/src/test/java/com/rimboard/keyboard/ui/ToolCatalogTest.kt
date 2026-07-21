package com.rimboard.keyboard.ui

import com.rimboard.keyboard.settings.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog is addressed by string id from two places that cannot check
 * themselves: the stored pinned-tool preference, and the default set handed to
 * a fresh install. A renamed id breaks both silently — the tool is simply
 * absent, with nothing logged and nothing failing.
 */
class ToolCatalogTest {

    @Test
    fun `ids are unique`() {
        val dupes = ToolCatalog.all.map { it.id }
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("duplicate tool ids: $dupes", dupes.isEmpty())
    }

    @Test
    fun `action codes are unique`() {
        // Two tools sharing a code would both fire the same action, and the
        // panel would run the wrong one depending on order.
        val dupes = ToolCatalog.all.map { it.code }
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("duplicate action codes: $dupes", dupes.isEmpty())
    }

    @Test
    fun `every tool has a label`() {
        val unlabelled = ToolCatalog.all.filter { it.labelRes == 0 }.map { it.id }
        assertTrue("tools with no label: $unlabelled", unlabelled.isEmpty())
    }

    @Test
    fun `defaultOrder covers the catalog exactly`() {
        assertEquals(ToolCatalog.all.map { it.id }, ToolCatalog.defaultOrder)
    }

    @Test
    fun `byId resolves every tool and rejects an unknown one`() {
        for (t in ToolCatalog.all) {
            assertEquals(t.id, ToolCatalog.byId(t.id)?.id)
        }
        assertTrue(ToolCatalog.byId("no-such-tool") == null)
    }

    @Test
    fun `byCode resolves every tool, which is what labels the strip`() {
        // The suggestion strip has only the action code to go on when it needs
        // a TalkBack label for a pinned icon. It used to map code to label with
        // its own table, which was missing "All tools" — the first entry in the
        // default pinned set — so a fresh install shipped an unlabelled icon.
        for (t in ToolCatalog.all) {
            assertEquals("no tool for code ${t.code}", t.id, ToolCatalog.byCode(t.code)?.id)
        }
        assertTrue(ToolCatalog.byCode(Int.MIN_VALUE) == null)
    }

    @Test
    fun `the default pinned set names tools that exist`() {
        // A fresh install has no fixed settings or clipboard button, so this set
        // is the only route to either — an id that no longer resolves would
        // strand a new user with a shorter drawer and no way to tell why.
        val unknown = Prefs.DEFAULT_PINNED.filter { ToolCatalog.byId(it) == null }
        assertTrue("default pinned names unknown tools: $unknown", unknown.isEmpty())
    }

    @Test
    fun `the default pinned set can reach the tools panel`() {
        // Without this the drawer offers no way to open the panel that
        // configures it, and only a long-press on the chevron recovers.
        val panel = ToolCatalog.all.first { it.id == "toolbar" }
        assertTrue(
            "default pinned must include the tools panel",
            Prefs.DEFAULT_PINNED.contains(panel.id)
        )
    }
}
