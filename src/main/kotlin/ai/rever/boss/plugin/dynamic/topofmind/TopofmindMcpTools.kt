package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.WorkspaceDataProvider

/**
 * MCP tools contributed by the Top of Mind plugin: list active tabs across workspaces, and
 * focus/close/open/move them. Registered in [TopofmindDynamicPlugin.register]; removed
 * automatically on disable/unload.
 */
internal class TopofmindMcpToolProvider(
    override val providerId: String,
    private val activeTabsProvider: ActiveTabsProvider?,
    private val workspaceDataProvider: WorkspaceDataProvider?,
) : McpToolProvider {
    override fun tools(): List<McpToolDefinition> =
        listOf(
            McpToolDefinition(
                name = "tabs_list",
                description =
                    "List all active tabs across every workspace (tab id, title, type, " +
                        "workspace, which panel/window/split it's in, url). The workspace on " +
                        "screen is marked with *.",
                handler =
                    McpToolHandler {
                        val p = activeTabsProvider ?: return@McpToolHandler unavailable()
                        p.refreshTabs()
                        val tabs = p.activeTabs.value
                        if (tabs.isEmpty()) return@McpToolHandler McpToolResult("No active tabs.")
                        val currentId = workspaceDataProvider?.currentWorkspace?.value?.id
                        McpToolResult(
                            tabs.joinToString("\n") { t ->
                                val url = t.url?.let { " <$it>" } ?: ""
                                val split = t.splitPosition?.let { " split=$it" } ?: ""
                                val here = if (t.workspaceId == currentId) "*" else " "
                                "$here${t.tabId}  [${t.typeId}]  ${t.title}  " +
                                    "(${t.workspaceName} id=${t.workspaceId})$url" +
                                    "  panel=${t.panelId} window=${t.windowId}$split"
                            },
                        )
                    },
            ),
            McpToolDefinition(
                name = "tab_focus",
                description = "Focus/select an active tab by its tab id (from tabs_list).",
                inputSchema = TAB_ID_SCHEMA,
                readOnly = false,
                handler =
                    McpToolHandler { args ->
                        val p = activeTabsProvider ?: return@McpToolHandler unavailable()
                        val id =
                            args.string("tab_id")?.takeIf { it.isNotBlank() }
                                ?: return@McpToolHandler missing("tab_id")
                        val tab =
                            p.activeTabs.value.firstOrNull { it.tabId == id }
                                ?: return@McpToolHandler McpToolResult("No active tab with id $id", isError = true)
                        p.selectTab(tab.tabId, tab.panelId)
                        McpToolResult("Focused tab ${tab.tabId} (${tab.title}).")
                    },
            ),
            McpToolDefinition(
                name = "tab_close",
                description = "Close an active tab by its tab id.",
                inputSchema = TAB_ID_SCHEMA,
                readOnly = false,
                handler =
                    McpToolHandler { args ->
                        val p = activeTabsProvider ?: return@McpToolHandler unavailable()
                        val id = args.string("tab_id") ?: return@McpToolHandler missing("tab_id")
                        if (p.closeTab(id)) {
                            McpToolResult("Closed tab $id.")
                        } else {
                            McpToolResult("Could not close tab $id.", isError = true)
                        }
                    },
            ),
            McpToolDefinition(
                name = "tab_open_url",
                description = "Open a new browser tab at the given URL.",
                inputSchema = OPEN_URL_SCHEMA,
                readOnly = false,
                handler =
                    McpToolHandler { args ->
                        val p = activeTabsProvider ?: return@McpToolHandler unavailable()
                        val url = args.string("url") ?: return@McpToolHandler missing("url")
                        val title = args.string("title") ?: url
                        val newId = p.createBrowserTab(url, title)
                        if (newId != null) {
                            McpToolResult("Opened browser tab $newId at $url.")
                        } else {
                            McpToolResult("Could not open browser tab.", isError = true)
                        }
                    },
            ),
            McpToolDefinition(
                name = "tab_move",
                description =
                    "Move a tab into another workspace this window is running, keeping it alive " +
                        "(a browser tab keeps its page, a terminal its session). The workspace " +
                        "may be named by id or by name. Does not switch workspaces.",
                inputSchema = MOVE_SCHEMA,
                readOnly = false,
                handler =
                    McpToolHandler { args ->
                        val p = activeTabsProvider ?: return@McpToolHandler unavailable()
                        if (!TabTransfer.isSupported(p)) {
                            return@McpToolHandler McpToolResult(
                                "This BOSS build cannot move tabs between workspaces.",
                                isError = true,
                            )
                        }
                        val tabId = args.string("tab_id") ?: return@McpToolHandler missing("tab_id")
                        val wanted = args.string("workspace") ?: return@McpToolHandler missing("workspace")

                        p.refreshTabs()
                        val tabs = p.activeTabs.value
                        val tab =
                            tabs.firstOrNull { it.tabId == tabId }
                                ?: return@McpToolHandler McpToolResult("No active tab with id $tabId", isError = true)

                        val targets = TabTransfer.targetsFor(tab, p, workspaceDataProvider, tabs)
                        val target =
                            targets.firstOrNull { it.workspaceId == wanted }
                                ?: targets.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
                                ?: return@McpToolHandler McpToolResult(
                                    "No workspace \"$wanted\" available as a destination. Running: " +
                                        targets.joinToString(", ") { "${it.name} (${it.workspaceId})" }
                                            .ifEmpty { "none" },
                                    isError = true,
                                )

                        if (TabTransfer.move(p, tab.tabId, target.workspaceId)) {
                            p.refreshTabs()
                            McpToolResult("Moved \"${tab.title}\" to ${target.name}.")
                        } else {
                            McpToolResult("Could not move tab $tabId to ${target.name}.", isError = true)
                        }
                    },
            ),
        )

    private fun unavailable(): McpToolResult =
        McpToolResult("Active tabs provider unavailable in this context.", isError = true)

    private fun missing(arg: String): McpToolResult =
        McpToolResult("Missing required argument: $arg", isError = true)

    private companion object {
        const val TAB_ID_SCHEMA =
            """{"type":"object","properties":{"tab_id":{"type":"string","description":"The tab id from tabs_list."}},"required":["tab_id"]}"""
        const val OPEN_URL_SCHEMA =
            """{"type":"object","properties":{"url":{"type":"string","description":"URL to open."},"title":{"type":"string","description":"Optional tab title."}},"required":["url"]}"""
        const val MOVE_SCHEMA =
            """{"type":"object","properties":{"tab_id":{"type":"string","description":"The tab id from tabs_list."},"workspace":{"type":"string","description":"Destination workspace, by id or name. Must be one this window is running."}},"required":["tab_id","workspace"]}"""
    }
}
