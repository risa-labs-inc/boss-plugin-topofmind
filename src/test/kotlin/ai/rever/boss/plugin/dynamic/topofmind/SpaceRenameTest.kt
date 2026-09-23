package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.workspace.LayoutWorkspace
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which Spaces offer "Rename Space...", and what the dialog lets through.
 *
 * The host refuses a rename without saying so, so every case it would refuse has to be caught here
 * instead - or the menu row, or the dialog's Rename button, does nothing.
 */
class SpaceRenameTest {
    private fun space(
        id: String,
        name: String,
    ) = LayoutWorkspace(
        id = id,
        name = name,
        description = "",
        layout = SplitConfig.SinglePanel(PanelConfig(id = "main", tabs = emptyList())),
    )

    private val mine = space("workspace-1", "Backend")
    private val other = space("workspace-2", "Frontend")
    private val template = space("workspace-codex", "Codex")

    @Test
    fun `a saved Space of the user's can be renamed under its saved name`() {
        assertEquals(RenameTarget("workspace-1", "Backend"), renameTargetFor("workspace-1", listOf(mine, other)))
    }

    @Test
    fun `an unsaved Space offers no rename`() {
        assertNull(renameTargetFor("workspace-9", listOf(mine)))
    }

    @Test
    fun `a template offers no rename`() {
        assertNull(renameTargetFor("workspace-codex", listOf(template, mine)))
    }

    @Test
    fun `Last Session offers no rename, since every autosave restamps its name`() {
        assertNull(renameTargetFor(LAST_SESSION_ID, listOf(space(LAST_SESSION_ID, "Last Session"), mine)))
    }

    @Test
    fun `a name two Spaces share offers no rename, since the api renames by name`() {
        val twin = space("workspace-3", "Backend")
        assertNull(renameTargetFor("workspace-1", listOf(mine, twin)))
    }

    @Test
    fun `the dialog refuses what the host would ignore`() {
        val target = RenameTarget("workspace-1", "Backend")
        val saved = listOf(mine, other)
        assertEquals("Enter a name", renameProblem("   ", target, saved))
        assertEquals("", renameProblem(" Backend ", target, saved))
        assertEquals("A Space named \"Frontend\" already exists", renameProblem("Frontend", target, saved))
        assertNull(renameProblem("  API  ", target, saved))
    }

    @Test
    fun `rename comes before theme, and a missing half is left out`() {
        assertEquals(
            listOf(SPACE_RENAME_MENU_LABEL, SPACE_THEME_MENU_LABEL),
            spaceMenuItems(onRename = {}, onPickTheme = {}).map { it.label },
        )
        assertEquals(listOf(SPACE_THEME_MENU_LABEL), spaceMenuItems(onRename = null, onPickTheme = {}).map { it.label })
        assertEquals(emptyList(), spaceMenuItems(onRename = null, onPickTheme = null))
    }
}
