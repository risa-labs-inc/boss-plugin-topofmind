package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.DeepLinkActionHandler
import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

/**
 * The one action this plugin answers.
 *
 * Reached two ways, and they are the same call: a `boss://plugin?id=<pluginId>&action=…` deep
 * link, and the host's own workspace button at the foot of the vertical tab bar, which dispatches
 * straight into `DeepLinkActionRegistryImpl` rather than inventing a second channel. The host
 * repeats this string; it is a wire name, so it is a constant on both sides and neither can
 * rename it alone.
 */
internal const val ACTION_OPEN_WORKSPACE_PICKER = "open-workspace-picker"

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

    /**
     * Which mounted panel a workspace-picker request lands on.
     *
     * A field on the plugin instance, so it is created and thrown away with the plugin and a
     * reload cannot leave a stale one behind. The per-panel dialog state hangs off
     * [TopofmindComponent]; this only decides which of them is asked.
     */
    private val pickerRequests = WorkspacePickerRequests()

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
                // The workspace footer's two: the host's file dialog and its prompts. Null on a
                // host that does not offer them, which hides the buttons that need them.
                filePickerProvider = context.filePickerProvider,
                genericDialogProvider = context.genericDialogProvider,
                pickerRequests = pickerRequests,
                scope = context.pluginScope,
            )
        }
        // Contribute tabs_list/tab_focus/tab_close/tab_open_url/tab_move; auto-removed on unload.
        context.registerMcpToolProvider(
            TopofmindMcpToolProvider(pluginId, context.activeTabsProvider, context.workspaceDataProvider),
        )
        // Lets the host's workspace button raise this panel's workspace picker instead of dropping
        // its own menu.
        //
        // No matching unregister, and none is wanted: the host wraps a dynamic plugin's context in
        // `TrackingPluginContext`, which records a deep-link handler as a UI extension and removes
        // it when the plugin unloads - the same reason the MCP tools above have no teardown either.
        // A manual `dispose()` unregister would be a second removal of something already gone.
        context.registerDeepLinkActionHandler(
            object : DeepLinkActionHandler {
                // By convention the plugin id, which is what a deep link's `id` parameter carries.
                override val handlerId: String = pluginId

                override fun handle(
                    action: String,
                    params: Map<String, String>,
                ): Boolean {
                    // Unknown actions are the registry's to log, not this handler's to guess at.
                    if (action != ACTION_OPEN_WORKSPACE_PICKER) return false
                    pickerRequests.request()
                    return true
                }
            },
        )
    }
}
