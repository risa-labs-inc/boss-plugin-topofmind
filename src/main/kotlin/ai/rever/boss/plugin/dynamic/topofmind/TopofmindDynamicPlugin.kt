package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

/**
 * Top of Mind dynamic plugin - loaded from an external JAR.
 *
 * Shows every tab this window is running, across every workspace, and lets one be moved between
 * them. Also owns the generic `tabs_*` MCP tools.
 */
class TopofmindDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.topofmind"
    override val displayName: String = "Top of Mind (Dynamic)"

    // Kept in step with build.gradle.kts, which processResources treats as the single source of
    // truth for plugin.json. This copy is read by the host's plugin list and had drifted five
    // releases behind.
    override val version: String = "1.2.0"
    override val description: String = "View, switch between and move active tabs across workspaces"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-topofmind"

    override fun register(context: PluginContext) {
        context.panelRegistry.registerPanel(TopofmindInfo) { ctx, panelInfo ->
            TopofmindComponent(
                ctx = ctx,
                panelInfo = panelInfo,
                activeTabsProvider = context.activeTabsProvider,
                workspaceDataProvider = context.workspaceDataProvider,
                splitViewOperations = context.splitViewOperations,
                // The host renders this natively (a real NSMenu on macOS), which is what lets
                // "Move to workspace" be a submenu rather than a hand-drawn popup.
                contextMenuProvider = context.contextMenuProvider,
                scope = context.pluginScope,
            )
        }
        // Contribute tabs_list/tab_focus/tab_close/tab_open_url/tab_move; auto-removed on unload.
        context.registerMcpToolProvider(
            TopofmindMcpToolProvider(pluginId, context.activeTabsProvider, context.workspaceDataProvider),
        )
    }
}
