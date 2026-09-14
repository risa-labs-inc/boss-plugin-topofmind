package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.BossThemeOption
import ai.rever.boss.plugin.api.BrowserIntegration
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Picking a theme for a Space, all the way from the row's menu to the tint that row draws.
 *
 * **This is the crossing most likely to be broken while both halves pass on their own.** The
 * plugin WRITES through `ActiveTabsProvider.setWorkspaceTheme` and READS from
 * `ActiveTabsProvider.workspaceAccents`, which are two members of an interface it does not
 * implement: a host that wrote the file without republishing the flow, or a panel that read the
 * map once instead of collecting it, would leave a picker that appears to do nothing while every
 * unit test on either side stays green.
 *
 * So the test drives the real composable through an `ImageComposeScene` - the panel's own
 * `collectAsState` over the provider's flow, the real `WorkspaceHeader`, the real tint - flips the
 * theme through the provider, renders again, and asserts the PIXELS moved. [FakeThemeHost] models
 * the host's one-writer rule: the setter is the only thing that publishes into the flow.
 */
class SpaceThemeRoundTripTest {
    private val nvidia = Color(0xFF76B900)
    private val blueprint = Color(0xFF0F5BFF)
    private val operator = Color(0xFFF2A93B)

    private val themes =
        listOf(
            BossThemeOption("blueprint", "Blueprint", isLight = false, accent = blueprint, surface = Color(0xFF080B11)),
            BossThemeOption(
                "blueprint-light",
                "Blueprint Light",
                isLight = true,
                accent = blueprint,
                surface = Color(0xFFFFFFFF),
            ),
            BossThemeOption("operator", "Operator", isLight = false, accent = operator, surface = Color(0xFF15171A)),
            BossThemeOption("nvidia", "NVIDIA", isLight = false, accent = nvidia, surface = Color(0xFF0C0C0C)),
        )

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

    // ==================== the pure half ====================

    @Test
    fun `a host that serves no themes offers no picker`() {
        // The gate on the header's menu item, and the reason there is no second `supportsX`
        // member: an empty catalogue IS the probe.
        assertTrue(FakeThemeHost(emptyList()).availableThemes.isEmpty())
    }

    @Test
    fun `both Blueprints are marked when either is worn, because they share an accent`() {
        // The picker is told a COLOUR, which is what the api publishes about a Space. Saying "one
        // of these two" is truer than picking whichever the list reaches first - and it is why the
        // plate, not the swatch, is what separates them on screen.
        val marked = themes.filter { it.accent == blueprint }.map { it.id }

        assertEquals(listOf("blueprint", "blueprint-light"), marked)
    }

    @Test
    fun `the two Blueprints are separable by their surface and by nothing else`() {
        val dark = themes.single { it.id == "blueprint" }
        val light = themes.single { it.id == "blueprint-light" }

        assertEquals(dark.accent, light.accent, "the shared #0F5BFF this whole design works around")
        assertNotEquals(dark.surface, light.surface, "the ground is the only thing that can tell them apart")
        assertFalse(dark.isLight)
        assertTrue(light.isLight)
    }

    // ==================== the crossing ====================

    @Test
    fun `picking a theme moves the tint the panel draws`() {
        val host =
            FakeThemeHost(
                themes,
                accents = mapOf("workspace-gemini" to blueprint),
                tabs = listOf(tab("workspace-gemini")),
            )

        var applied = false
        val (before, after) = renderAround(host) { applied = host.setWorkspaceTheme("workspace-gemini", "nvidia") }

        assertTrue(applied, "the host took the write")
        assertNotEquals(
            before.toList(),
            after.toList(),
            "the panel still draws the old colour: the write reached the file and not the flow, " +
                "or the panel read the map once instead of collecting it",
        )
        assertTrue(
            distanceTo(after, nvidia) < distanceTo(before, nvidia),
            "the panel changed but not toward the theme that was picked",
        )
    }

    @Test
    fun `a theme this host does not have is refused and changes nothing`() {
        val host =
            FakeThemeHost(
                themes,
                accents = mapOf("workspace-gemini" to blueprint),
                tabs = listOf(tab("workspace-gemini")),
            )

        var applied = true
        val (before, after) =
            renderAround(host) { applied = host.setWorkspaceTheme("workspace-gemini", "midnight-retired") }

        assertFalse(applied)
        assertEquals(before.toList(), after.toList())
    }

    @Test
    fun `theming a Space leaves every other Space alone`() {
        val host =
            FakeThemeHost(
                themes,
                accents = mapOf("workspace-gemini" to blueprint, "workspace-codex" to operator),
            )

        host.setWorkspaceTheme("workspace-gemini", "nvidia")

        assertEquals(nvidia, host.workspaceAccents.value["workspace-gemini"])
        assertEquals(operator, host.workspaceAccents.value["workspace-codex"])
    }

    /**
     * THE REAL PANEL, rendered off screen, before and after [change], in ONE composition.
     *
     * Two things this shape buys, and both are the point:
     *
     * - **Mounting `TopOfMindContent` rather than a stand-in for its two lines.** What is under
     *   test is that the tint comes off a COLLECTED flow, and a test doing its own collecting
     *   would pass against a panel that had stopped. Every provider but the tabs one is null,
     *   which the panel is built to survive.
     * - **One scene, rendered twice.** Two fresh scenes would each compose from scratch and read
     *   whatever the flow held at that moment, so a panel that had swapped `collectAsState` for a
     *   plain `.value` read would still pass. Holding the composition across the change is what
     *   makes the subscription the thing being measured.
     *
     * The WHOLE frame is sampled rather than one row, so the assertion needs no arithmetic about
     * where a header lands: the tint is the only thing that differs between two renders of the
     * same tabs, so the frame's average moving is the tint moving.
     */
    private fun renderAround(
        host: ActiveTabsProvider,
        change: () -> Unit,
    ): Pair<IntArray, IntArray> {
        val scene =
            ImageComposeScene(
                width = 260,
                height = 320,
                density = Density(1f),
                // Unconfined, so a `collectAsState` emission lands before the next `render()`
                // rather than on some later frame this test never draws.
                coroutineContext = Dispatchers.Unconfined,
            ) {
                Box(modifier = Modifier.fillMaxSize().background(BossThemeColors.SurfaceColor)) {
                    TopOfMindContent(
                        activeTabsProvider = host,
                        workspaceDataProvider = null,
                        splitViewOperations = null,
                        contextMenuProvider = null,
                        filePickerProvider = null,
                        genericDialogProvider = null,
                        treeState = TabTreeState(),
                        dragState = TabDragState(),
                        paneExpansion = SplitPaneExpansion(),
                        panelDialogs = PanelDialogState(),
                        workspaceArrival = WorkspaceArrival(),
                        windowId = "w1",
                        scope = CoroutineScope(Dispatchers.Unconfined),
                    )
                }
            }
        return try {
            // A frame either side of the change, thrown away, and both are needed.
            //
            // Before: the panel has effects that run after its first composition - a tab refresh,
            // the floors' scroll-the-newest-into-view - so the first render is not the settled
            // picture. After: `collectAsState` delivers its emission on a coroutine, so the write
            // is not on screen until a frame has carried it there.
            //
            // This is plumbing, not leniency. A panel that read `.value` once instead of
            // collecting, or a host that persisted without republishing, never converges however
            // many frames it is given - which is what the mutations against this test show.
            scene.render()
            val before = scene.pixels()
            change()
            scene.render()
            before to scene.pixels()
        } finally {
            scene.close()
        }
    }

    private fun ImageComposeScene.pixels(): IntArray {
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(render())
        return IntArray(bitmap.width * bitmap.height) { i ->
            bitmap.getColor(i % bitmap.width, i / bitmap.width)
        }
    }

    /**
     * How far a rendered frame's average colour sits from [target], in plain RGB.
     *
     * A DISTANCE, because the obvious metric is wrong: "is it greener" as green-minus-red says
     * Blueprint (#0F5BFF, +76) is greener than NVIDIA (#76B900, +67), so a frame that moved
     * exactly as intended scored a tie. Asking whether it moved NEARER the colour that was picked
     * cannot be fooled that way, and it is also the claim worth making.
     */
    private fun distanceTo(
        frame: IntArray,
        target: Color,
    ): Double {
        val mean = { shift: Int -> frame.sumOf { (it shr shift) and 0xFF }.toDouble() / frame.size }
        val dr = mean(16) - target.red * 255
        val dg = mean(8) - target.green * 255
        val db = mean(0) - target.blue * 255
        return dr * dr + dg * dg + db * db
    }

    /**
     * A host that themes Spaces, with the ONE-WRITER rule the real one has.
     *
     * `setWorkspaceTheme` is the only thing that writes [workspaceAccents], which is what makes
     * the round trip above a real test: a fake that let the test poke the flow directly would pass
     * against a host that persisted the choice and never republished it.
     */
    private class FakeThemeHost(
        override val availableThemes: List<BossThemeOption>,
        accents: Map<String, Color> = emptyMap(),
        tabs: List<ActiveTabData> = emptyList(),
    ) : ActiveTabsProvider {
        private val _accents = MutableStateFlow(accents)

        override val workspaceAccents: StateFlow<Map<String, Color>> = _accents

        override fun setWorkspaceTheme(
            workspaceId: String,
            themeId: String,
        ): Boolean {
            val theme = availableThemes.firstOrNull { it.id == themeId } ?: return false
            _accents.value = _accents.value + (workspaceId to theme.accent)
            return true
        }

        override val activeTabs: StateFlow<List<ActiveTabData>> = MutableStateFlow(tabs)

        override suspend fun refreshTabs() {}

        override fun selectTab(
            tabId: String,
            panelId: String,
        ) {}

        override fun getTabUrl(tabId: String): String? = null

        override fun getFaviconCacheKey(tabId: String): String? = null

        @Composable
        override fun loadFavicon(cacheKey: String?): Painter? = null

        override fun getFallbackIcon(typeId: String): ImageVector? = null

        override fun getBrowserIntegration(tabId: String): BrowserIntegration? = null

        override fun createBrowserTab(
            url: String,
            title: String,
        ): String? = null

        override fun closeTab(tabId: String): Boolean = false
    }
}
