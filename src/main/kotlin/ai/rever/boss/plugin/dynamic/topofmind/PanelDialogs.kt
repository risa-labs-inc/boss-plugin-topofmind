package ai.rever.boss.plugin.dynamic.topofmind

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A dialog this panel can be asked to raise from outside its own composition.
 *
 * There are two, and they arrive the same way: the host dispatches a deep-link action at this
 * plugin's handler and the request has to find a panel to land on. Carrying WHICH dialog as a
 * value rather than standing up a second [PanelDialogRequests] is the whole reason this type
 * exists - a parallel copy would need its own pending-request window, its own attach/detach
 * bookkeeping and its own idea of which panel is in front, and the two would answer differently
 * the first time a panel closed between the attach and the request.
 */
enum class PanelDialog {
    /** The searchable list of saved workspaces, raised by the footer and by the host's workspace button. */
    WORKSPACE_PICKER,

    /** The quick switcher over every window's tabs, raised by the footer and by Ctrl+Space. */
    QUICK_SWITCHER,
}

/**
 * Which dialog ONE panel is showing, or none.
 *
 * The visibility lived inside `WorkspaceActionsFooter` as a plain `remember`, which is the right
 * place while the only thing that can open it is the button next to it. It has a second caller
 * now - the host, through this plugin's deep-link action handler - and a caller outside the
 * composition needs somewhere it can write.
 *
 * **One slot, not a flag per dialog.** Both are modal, so "the workspace picker and the quick
 * switcher are both open" is not a state this panel can be in; two booleans could express it and
 * would eventually be asked to.
 *
 * Panel-scoped, held by [TopofmindComponent] exactly as `TabTreeState`, `TabDragState` and
 * `SplitPaneExpansion` are, and deliberately **not** a top-level `object`: a second host window
 * gets its own panel component, and one shared value would raise the dialog in both windows and
 * close it in both when either one dismissed it. This plugin has shipped that bug twice already
 * (expansion, then drag).
 */
class PanelDialogState {
    var current: PanelDialog? by mutableStateOf(null)
        private set

    fun isOpen(dialog: PanelDialog): Boolean = current == dialog

    fun open(dialog: PanelDialog) {
        current = dialog
    }

    fun close() {
        current = null
    }

    /** Open [dialog], or close it when it is the one already up - what a footer button does. */
    fun toggle(dialog: PanelDialog) {
        current = if (current == dialog) null else dialog
    }
}

/**
 * Which panel a "show me this dialog" request lands on.
 *
 * One instance per PLUGIN (a field on [TopofmindDynamicPlugin], not a top-level object, so a
 * reload gets a fresh one), holding the [PanelDialogState] of every panel that is currently
 * composed. A request opens the dialog on exactly ONE of them.
 *
 * **The front panel is the one most recently composed.** A deep-link action carries no window,
 * and the plugin api hands a panel component no window id either - the panel factory gets a
 * decompose `ComponentContext` and a `PanelInfo`, and neither says which window is being built.
 * So "the panel the user is looking at" has to be inferred, and the last one to compose is the
 * best available reading of it. With the one window that is the normal case it is exactly right.
 *
 * **A request with no panel composed is held, briefly.** The caller opens the panel and asks in
 * the same click, and opening a panel is asynchronous - the host emits an event, a collector picks
 * it up and the panel composes a frame or more later. Asking first and dropping the request
 * because nothing was mounted yet would make the FIRST click on a closed panel do half the job.
 * The hold expires so that a panel opened by hand ten minutes later does not inherit it.
 *
 * **The held request carries which dialog was asked for**, which is why it is a pair rather than a
 * timestamp. A second request inside the window replaces the first: the user pressed something
 * else, and raising the dialog they asked for two actions ago would be the wrong answer.
 */
class PanelDialogRequests {
    /** Composed panels, most recently composed first. */
    private val panels = mutableListOf<PanelDialogState>()

    /** An unanswered request and when it was made, or null if there is none outstanding. */
    private var pending: Pair<PanelDialog, Long>? = null

    /** A panel has composed and can now be asked to show a dialog. */
    @Synchronized
    fun attach(state: PanelDialogState) {
        panels.remove(state)
        panels.add(0, state)
        val (dialog, queuedAt) = pending ?: return
        pending = null
        if (System.nanoTime() - queuedAt <= PENDING_WINDOW_NANOS) state.open(dialog)
    }

    /** A panel has left the composition. */
    @Synchronized
    fun detach(state: PanelDialogState) {
        panels.remove(state)
    }

    /** Show [dialog] on the front panel, or hold the request for the next panel to compose. */
    @Synchronized
    fun request(dialog: PanelDialog) {
        val front = panels.firstOrNull()
        if (front == null) {
            pending = dialog to System.nanoTime()
        } else {
            pending = null
            front.open(dialog)
        }
    }
}

/**
 * How long a request survives with no panel to land on.
 *
 * Long enough for the host to open the panel and for the plugin's first composition to run on a
 * cold panel, short enough that it cannot still be waiting the next time the user opens the panel
 * themselves.
 */
private const val PENDING_WINDOW_NANOS = 5_000_000_000L
