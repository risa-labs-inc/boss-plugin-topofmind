package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

/**
 * Top of Mind dynamic plugin - Loaded from external JAR.
 *
 * Displays workspaces and allows quick switching between them.
 * Uses workspaceDataProvider and splitViewOperations from PluginContext.
 */
class TopofmindDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.topofmind"
    override val displayName: String = "Top of Mind (Dynamic)"
    override val version: String = "1.0.3"
    override val description: String = "Workspace manager - switch and organize workspaces"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-topofmind"

    private var workspaceDataProvider: ai.rever.boss.plugin.api.WorkspaceDataProvider? = null
    private var splitViewOperations: ai.rever.boss.plugin.api.SplitViewOperations? = null
    private var pluginScope: kotlinx.coroutines.CoroutineScope? = null

    override fun register(context: PluginContext) {
        workspaceDataProvider = context.workspaceDataProvider
        splitViewOperations = context.splitViewOperations
        pluginScope = context.pluginScope

        context.panelRegistry.registerPanel(TopofmindInfo) { ctx, panelInfo ->
            TopofmindComponent(
                ctx = ctx,
                panelInfo = panelInfo,
                workspaceDataProvider = workspaceDataProvider,
                splitViewOperations = splitViewOperations,
                scope = pluginScope ?: kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
            )
        }
    }
}
