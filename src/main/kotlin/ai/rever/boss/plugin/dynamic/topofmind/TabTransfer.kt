package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.WorkspaceDataProvider

/** A workspace a tab can be moved into: running in this window, and not the one it is already in. */
data class TransferTarget(
    val workspaceId: String,
    val name: String,
)

/**
 * Moving a tab between the workspaces this window is running.
 *
 * A window runs several workspaces at once and shows one of them, which is why the tree has rows
 * for tabs that are not on screen. Moving one keeps it alive: the host transfers the component
 * instance, so a browser tab keeps its page and a terminal keeps its session.
 *
 * The whole affordance is gated on [ActiveTabsProvider.supportsTabTransfer]. That is not the same
 * question as "is this host new enough": the manifest's `minBossVersion` answers that one, and it
 * has to, because this file names host members directly and the host's BinaryCompatibilityValidator
 * member-checks every `ai.rever.boss.plugin.*` class in the jar - a host without them rejects the
 * plugin outright rather than loading it with the move disabled. What the probe answers is the
 * OTHER case: a host that has the member and says no, which is what an out-of-process plugin gets,
 * since the IPC proxy cannot forward a live component transfer.
 */
object TabTransfer {
    /** Whether this host will actually move tabs between workspaces. */
    fun isSupported(provider: ActiveTabsProvider?): Boolean = provider?.supportsTabTransfer == true

    /**
     * Workspace ids this window is running.
     *
     * Asked for rather than derived, because a workspace with NO tabs contributes no rows to
     * [ActiveTabsProvider.activeTabs] and would otherwise be invisible - and an empty workspace is
     * a perfectly good place to put something.
     */
    private fun liveWorkspaceIds(provider: ActiveTabsProvider): Set<String> = provider.liveWorkspaceIds

    /**
     * Where [tab] may be moved to, newest resolution first.
     *
     * Names come from [WorkspaceDataProvider.workspaces] where it knows them, and otherwise from
     * whatever the tab list calls that workspace - a workspace can be running under an id the saved
     * list has never seen ("last-session" is the standing example), and offering it as a bare id is
     * better than not offering it.
     */
    fun targetsFor(
        tab: ActiveTabData,
        activeTabsProvider: ActiveTabsProvider,
        workspaceDataProvider: WorkspaceDataProvider?,
        allTabs: List<ActiveTabData>,
    ): List<TransferTarget> {
        // Union, not just the live set: a running workspace is always live, but the tab list is
        // what carries a NAME for one the saved list has never seen, and the two disagreeing
        // should not lose a destination either way.
        val ids = liveWorkspaceIds(activeTabsProvider) + allTabs.map { it.workspaceId }
        val savedNames =
            workspaceDataProvider
                ?.workspaces
                ?.value
                ?.associate { it.id to it.name }
                .orEmpty()
        val tabNames = allTabs.associate { it.workspaceId to it.workspaceName }
        return ids
            .asSequence()
            .filter { it.isNotBlank() && it != tab.workspaceId }
            .map { id ->
                TransferTarget(
                    workspaceId = id,
                    name = savedNames[id] ?: tabNames[id] ?: id,
                )
            }.sortedBy { it.name.lowercase() }
            .toList()
    }

    /**
     * Move [tabId] into [targetWorkspaceId].
     *
     * Suspending because the host has to marshal the transfer onto the UI thread; it returns false
     * for anything it will not do (unknown tab, destination not running, already there).
     */
    suspend fun move(
        provider: ActiveTabsProvider,
        tabId: String,
        targetWorkspaceId: String,
    ): Boolean = provider.moveTabToWorkspace(tabId, targetWorkspaceId)
}
