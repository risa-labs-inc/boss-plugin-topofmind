package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language

/**
 * Top of Mind panel info
 *
 * Priority 5 = Position in left.top.bottom panel
 */
object TopofmindInfo : PanelInfo {
    override val id = PanelId("top-of-mind", 5)
    override val displayName = "Top of mind"
    override val icon = Icons.Outlined.Language
    override val defaultSlotPosition = left.top.bottom
}
