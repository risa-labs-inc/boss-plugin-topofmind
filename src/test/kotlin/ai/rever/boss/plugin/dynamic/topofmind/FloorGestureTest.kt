package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A right-click on the space map must not also switch to that Space.
 *
 * The floors had no context menu before this, and a floor's whole pitch already takes a click - so
 * "the menu gesture does not fight the switch gesture" is the one thing that could quietly be
 * wrong, and it is a claim about a real gesture rather than about arithmetic. `TabRow` has shipped
 * the same pairing all along, which is the reason to expect it to hold; this is the check that it
 * does.
 */
class FloorGestureTest {
    private fun tab(workspaceId: String) =
        ActiveTabData(
            tabId = "$workspaceId-t",
            typeId = "terminal",
            title = "shell",
            workspaceId = workspaceId,
            workspaceName = "Gemini",
            panelId = "main",
            windowId = "w1",
        )

    private fun press(button: PointerButton): List<String> {
        val switched = mutableListOf<String>()
        val nodes = TabTreeBuilder.buildTree(listOf(tab("workspace-gemini")))
        val scene =
            ImageComposeScene(width = 260, height = 160, density = Density(1f)) {
                BossTheme {
                    Box(modifier = Modifier.fillMaxSize().width(260.dp)) {
                        WorkspaceFloors(
                            nodes = nodes,
                            currentWorkspaceId = null,
                            activePanelId = null,
                            spaceAccents = emptyMap(),
                            // Null, so the menu modifier is absent: what is under test is that the
                            // SWITCH ignores a secondary press, which has to hold whether or not a
                            // host is there to draw a menu over it.
                            contextMenuProvider = null,
                            onPickTheme = { _, _ -> },
                            onSelectWorkspace = { switched += it },
                        )
                    }
                }
            }
        try {
            scene.render()
            val at = Offset(120f, 40f)
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, button = button)
            scene.sendPointerEvent(PointerEventType.Release, at, button = button)
            scene.render()
        } finally {
            scene.close()
        }
        return switched
    }

    @Test
    fun `a primary press on a floor switches to that Space`() {
        assertEquals(listOf("workspace-gemini"), press(PointerButton.Primary))
    }

    @Test
    fun `a secondary press on a floor switches nothing`() {
        assertEquals(
            emptyList(),
            press(PointerButton.Secondary),
            "right-clicking the space map would switch Space as well as opening the menu",
        )
    }
}
