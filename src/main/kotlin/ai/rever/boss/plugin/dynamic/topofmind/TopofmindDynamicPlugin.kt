package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

/**
 * Top of Mind dynamic plugin - Loaded from external JAR.
 *
 * Quick access to frequently used items
 */
class TopofmindDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.topofmind"
    override val displayName: String = "Top of Mind (Dynamic)"
    override val version: String = "1.0.0"
    override val description: String = "Quick access to frequently used items"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-topofmind"

    override fun register(context: PluginContext) {
        context.panelRegistry.registerPanel(TopofmindInfo) { ctx, panelInfo ->
            TopofmindComponent(ctx, panelInfo)
        }
    }
}
