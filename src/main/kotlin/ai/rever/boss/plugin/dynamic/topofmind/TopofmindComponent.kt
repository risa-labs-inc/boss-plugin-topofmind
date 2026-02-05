package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CoroutineScope

/**
 * Top of Mind panel component (Dynamic Plugin).
 *
 * Displays currently active tabs across all workspaces.
 */
class TopofmindComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val activeTabsProvider: ActiveTabsProvider?,
    private val workspaceDataProvider: WorkspaceDataProvider?,
    private val splitViewOperations: SplitViewOperations?,
    private val scope: CoroutineScope
) : PanelComponentWithUI, ComponentContext by ctx {

    @Composable
    override fun Content() {
        TopOfMindContent(
            activeTabsProvider = activeTabsProvider,
            workspaceDataProvider = workspaceDataProvider,
            splitViewOperations = splitViewOperations,
            scope = scope
        )
    }
}
