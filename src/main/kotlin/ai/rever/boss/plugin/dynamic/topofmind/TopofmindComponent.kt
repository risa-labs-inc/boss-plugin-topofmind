package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.ContextMenuProvider
import ai.rever.boss.plugin.api.FilePickerProvider
import ai.rever.boss.plugin.api.GenericDialogProvider
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
@Suppress("LongParameterList")
class TopofmindComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val activeTabsProvider: ActiveTabsProvider?,
    private val workspaceDataProvider: WorkspaceDataProvider?,
    private val splitViewOperations: SplitViewOperations?,
    private val contextMenuProvider: ContextMenuProvider?,
    // The two the workspace footer needs: a file dialog to open a saved workspace with, and the
    // host's own prompts to name a save and confirm a delete. Both nullable, both threaded through
    // exactly like contextMenuProvider, and a missing one hides its button rather than breaking it.
    private val filePickerProvider: FilePickerProvider?,
    private val genericDialogProvider: GenericDialogProvider?,
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
            filePickerProvider = filePickerProvider,
            genericDialogProvider = genericDialogProvider,
            treeState = treeState,
            dragState = dragState,
            scope = scope,
        )
    }
}
