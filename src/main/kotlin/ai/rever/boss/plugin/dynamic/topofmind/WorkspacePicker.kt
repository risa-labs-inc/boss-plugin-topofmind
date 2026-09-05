package ai.rever.boss.plugin.dynamic.topofmind

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether ONE panel is showing the workspace picker.
 *
 * The visibility lived inside `WorkspaceActionsFooter` as a plain `remember`, which is the right
 * place while the only thing that can open it is the button next to it. It has a second caller
 * now - the host's workspace button, through this plugin's deep-link action handler - and a
 * caller outside the composition needs somewhere it can write.
 *
 * Panel-scoped, held by [TopofmindComponent] exactly as `TabTreeState`, `TabDragState` and
 * `SplitPaneExpansion` are, and deliberately **not** a top-level `object`: a second host window
 * gets its own panel component, and one shared boolean would raise the dialog in both windows and
 * close it in both when either one dismissed it. This plugin has shipped that bug twice already
 * (expansion, then drag).
 */
class WorkspacePickerState {
    var visible by mutableStateOf(false)
        private set

    fun open() {
        visible = true
    }

    fun close() {
        visible = false
    }

    fun toggle() {
        visible = !visible
    }
}

/**
 * Which panel a "show me the workspace picker" request lands on.
 *
 * One instance per PLUGIN (a field on [TopofmindDynamicPlugin], not a top-level object, so a
 * reload gets a fresh one), holding the [WorkspacePickerState] of every panel that is currently
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
 */
class WorkspacePickerRequests {
    /** Composed panels, most recently composed first. */
    private val panels = mutableListOf<WorkspacePickerState>()

    /** When an unanswered request was made, or null if there is none outstanding. */
    private var pendingAt: Long? = null

    /** A panel has composed and can now be asked to show the picker. */
    @Synchronized
    fun attach(state: WorkspacePickerState) {
        panels.remove(state)
        panels.add(0, state)
        val queued = pendingAt ?: return
        pendingAt = null
        if (System.nanoTime() - queued <= PENDING_WINDOW_NANOS) state.open()
    }

    /** A panel has left the composition. */
    @Synchronized
    fun detach(state: WorkspacePickerState) {
        panels.remove(state)
    }

    /**
     * Show the workspace picker on the front panel, or hold the request for the next panel to
     * compose.
     */
    @Synchronized
    fun request() {
        val front = panels.firstOrNull()
        if (front == null) {
            pendingAt = System.nanoTime()
        } else {
            pendingAt = null
            front.open()
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
