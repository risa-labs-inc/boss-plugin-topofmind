package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.ContextMenuProvider
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
 * Every tab this window is running, grouped by workspace and by split pane, with a tab movable
 * between workspaces by drag or right-click.
 *
 * Expansion and drag state are held HERE, one set per mounted panel, not in top-level objects.
 * Process-global state meant two windows showing this panel shared one drag and one set of open
 * groups, so collapsing a workspace in one collapsed it in the other.
 */
class TopofmindComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val activeTabsProvider: ActiveTabsProvider?,
    private val workspaceDataProvider: WorkspaceDataProvider?,
    private val splitViewOperations: SplitViewOperations?,
    private val contextMenuProvider: ContextMenuProvider?,
    private val scope: CoroutineScope,
) : PanelComponentWithUI,
    ComponentContext by ctx {
    private val treeState = TabTreeState()
    private val dragState = TabDragState()

    @Composable
    override fun Content() {
        TopOfMindContent(
            activeTabsProvider = activeTabsProvider,
            workspaceDataProvider = workspaceDataProvider,
            splitViewOperations = splitViewOperations,
            contextMenuProvider = contextMenuProvider,
            treeState = treeState,
            dragState = dragState,
            scope = scope,
        )
    }
}
