package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.ActiveTabData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The switcher's grouping, and the fact a heading has to carry to be tinted: WHICH Space it is for.
 *
 * A Space's colour is keyed by id, and this list is the only thing between the tab data and a
 * header that has to look one up.
 */
class SwitcherRowsTest {
    private fun tab(
        workspaceId: String,
        workspaceName: String,
        title: String,
        windowId: String = "w1",
    ) = ActiveTabData(
        tabId = "$windowId-$workspaceId-$title",
        typeId = "terminal",
        title = title,
        workspaceId = workspaceId,
        workspaceName = workspaceName,
        panelId = "main",
        windowId = windowId,
    )

    private fun groups(rows: List<SwitcherRow>) = rows.filterIsInstance<SwitcherRow.Group>()

    @Test
    fun `a heading knows which Space it is for, not only what it is called`() {
        val rows =
            switcherRows(
                tabs = listOf(tab("workspace-gemini", "Gemini", "docs")),
                query = "",
                thisWindowId = "w1",
            )

        assertEquals(listOf("workspace-gemini"), groups(rows).map { it.workspaceId })
    }

    @Test
    fun `two Spaces sharing a name keep their own ids`() {
        // A user can save a Space called exactly what a shipped layout is called, so a header that
        // looked its colour up by name would paint one Space with the other's.
        val rows =
            switcherRows(
                tabs =
                    listOf(
                        tab("workspace-codex", "Codex", "codex"),
                        tab("workspace-1788", "Codex", "notes"),
                    ),
                query = "",
                thisWindowId = "w1",
            )

        assertEquals(setOf("workspace-codex", "workspace-1788"), groups(rows).map { it.workspaceId }.toSet())
    }

    @Test
    fun `one Space running in two windows is two headings wearing one colour`() {
        // Grouped by (window, Space), so the same Space appears twice - and both headings carry
        // the same id, because it IS one Space.
        val rows =
            switcherRows(
                tabs =
                    listOf(
                        tab("workspace-gemini", "Gemini", "here", windowId = "w1"),
                        tab("workspace-gemini", "Gemini", "there", windowId = "w2"),
                    ),
                query = "",
                thisWindowId = "w1",
            )

        val headings = groups(rows)
        assertEquals(2, headings.size)
        assertTrue(headings.all { it.workspaceId == "workspace-gemini" })
        assertEquals(listOf(false, true), headings.map { it.elsewhere }, "this window's group comes first")
    }

    @Test
    fun `an unnamed Space still carries its id`() {
        // The name falls back to "Space"; the id is what the colour is keyed by and must not.
        val rows = switcherRows(listOf(tab("workspace-1788", "", "notes")), query = "", thisWindowId = "w1")

        val heading = groups(rows).single()
        assertEquals("Space", heading.workspaceName)
        assertEquals("workspace-1788", heading.workspaceId)
    }
}
