package ai.rever.boss.plugin.dynamic.topofmind

/**
 * The order workspaces started running in this window, so the one you just opened is the bottom row.
 *
 * The tree was ordered by `LayoutWorkspace.timestamp` - when a workspace was last WRITTEN - with the
 * name as a tie-break. That is stable, which was the point, but it answers the wrong question:
 * opening a workspace saved months ago dropped it into the middle of the list at its save-time
 * position, and workspaces sharing a timestamp fell back to the tie-break and came out alphabetical.
 * Neither is what "I just opened this" looks like.
 *
 * Arrival order is. A workspace takes the next free slot the first time it is seen and keeps it, so
 * nothing moves under the cursor while you work; a workspace opened later is later, and lands at the
 * bottom.
 *
 * **Seeded, not invented.** The first sighting is a whole set arriving at once - everything the
 * window was already running when the panel mounted - and their arrival order is not knowable from
 * inside it. So [slotsFor] assigns slots in the order it is GIVEN, and the caller passes that first
 * batch sorted by timestamp and name: the old ordering survives as the seed, and only what opens
 * afterwards is appended. The alternative, whatever order the host's list happened to be in, is
 * current-workspace-first, which would have put the workspace on screen at the top of the panel on
 * every launch.
 *
 * **Session-scoped and per panel.** A slot is not persisted: the next launch seeds again from the
 * saved timestamps, which is the only durable answer there is. And this is a field on
 * [TopofmindComponent], never a top-level object, for the reason [TabTreeState], [TabDragState],
 * [SplitPaneExpansion] and [PanelDialogState] all are - two windows must not share one ordering.
 * Two panels in the same window keep separate copies and agree anyway, because they see the same
 * workspaces appear in the same order.
 */
class WorkspaceArrival {
    private val slots = mutableMapOf<String, Int>()
    private var next = 0

    /**
     * Slots for exactly [workspaceIds], assigning any new id the next free one.
     *
     * [workspaceIds] must arrive in a deterministic order - see the class KDoc on seeding - and is
     * the whole of what is running: an id absent from it is FORGOTTEN, so closing a workspace and
     * opening it again puts it at the bottom, where opening it is what the user just did. Keeping
     * the old slot would make a reopened workspace reappear in the middle of the list, which is the
     * behaviour this replaced.
     *
     * Idempotent for the same set, so the roughly-2s rebuild is a no-op and nothing moves.
     */
    fun slotsFor(workspaceIds: List<String>): Map<String, Int> {
        slots.keys.retainAll(workspaceIds.toSet())
        workspaceIds.forEach { id -> slots.getOrPut(id) { next++ } }
        return slots.toMap()
    }
}
