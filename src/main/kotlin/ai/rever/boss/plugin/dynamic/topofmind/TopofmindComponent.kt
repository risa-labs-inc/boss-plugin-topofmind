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
import androidx.compose.runtime.DisposableEffect
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
 * groups, so collapsing a workspace in one collapsed it in the other. That is why
 * [SplitPaneExpansion] - which pane the pointer has chosen to open - is a field here too, and not
 * the `object` the host's equivalent could get away with being. [WorkspacePickerState] is the
 * newest member of that list, for the same reason: two windows must not share one dialog.
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
    // Plugin-scoped, shared by every panel: it is the thing that decides WHICH panel a request
    // from outside the composition opens the picker on. See WorkspacePicker.kt.
    private val pickerRequests: WorkspacePickerRequests,
    private val scope: CoroutineScope,
) : PanelComponentWithUI,
    ComponentContext by ctx {
    private val treeState = TabTreeState()
    private val dragState = TabDragState()
    private val paneExpansion = SplitPaneExpansion()
    private val picker = WorkspacePickerState()

    @Composable
    override fun Content() {
        // Registered for as long as this panel is COMPOSED, not for as long as the component
        // exists. A component the host is holding for a panel that is currently closed has no
        // dialog on screen to raise, and offering it would send a request into a panel nobody can
        // see. Attaching last-in-first-out is also how "the front panel" gets its meaning.
        DisposableEffect(Unit) {
            pickerRequests.attach(picker)
            onDispose { pickerRequests.detach(picker) }
        }

        TopOfMindContent(
            activeTabsProvider = activeTabsProvider,
            workspaceDataProvider = workspaceDataProvider,
            splitViewOperations = splitViewOperations,
            contextMenuProvider = contextMenuProvider,
            filePickerProvider = filePickerProvider,
            genericDialogProvider = genericDialogProvider,
            treeState = treeState,
            dragState = dragState,
            paneExpansion = paneExpansion,
            picker = picker,
            scope = scope,
        )
    }
}
