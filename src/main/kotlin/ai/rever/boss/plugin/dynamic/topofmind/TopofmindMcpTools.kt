package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult

/**
 * MCP tools contributed by the Top of Mind plugin: list active tabs across
 * workspaces and focus/close/open them. Registered in
 * [TopofmindDynamicPlugin.register]; removed automatically on disable/unload.
 */
internal class TopofmindMcpToolProvider(
    override val providerId: String,
    private val activeTabsProvider: ActiveTabsProvider?,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "tabs_list",
            description = "List all active tabs across every workspace (tab id, title, type, " +
                "workspace, which panel/window/split it's in, url).",
            handler = McpToolHandler {
                val p = activeTabsProvider ?: return@McpToolHandler unavailable()
                p.refreshTabs()
                val tabs = p.activeTabs.value
                if (tabs.isEmpty()) return@McpToolHandler McpToolResult("No active tabs.")
                McpToolResult(tabs.joinToString("\n") { t ->
                    val split = t.splitPosition?.let { " split=$it" } ?: ""
                    val url = t.url?.let { " <$it>" } ?: ""
                    "${t.tabId}  [${t.typeId}]  ${t.title}  (${t.workspaceName})  " +
                        "panel=${t.panelId} window=${t.windowId}$split$url"
                })
            },
        ),
        McpToolDefinition(
            name = "tab_focus",
            description = "Focus/select an active tab by its tab id (from tabs_list).",
            inputSchema = TAB_ID_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args ->
                val p = activeTabsProvider ?: return@McpToolHandler unavailable()
                val id = args.string("tab_id")?.takeIf { it.isNotBlank() }
                    ?: return@McpToolHandler McpToolResult("Missing required argument: tab_id", isError = true)
                val tab = p.activeTabs.value.firstOrNull { it.tabId == id }
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
            handler = McpToolHandler { args ->
                val p = activeTabsProvider ?: return@McpToolHandler unavailable()
                val id = args.string("tab_id")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: tab_id", isError = true)
                val closed = p.closeTab(id)
                if (closed) McpToolResult("Closed tab $id.") else McpToolResult("Could not close tab $id.", isError = true)
            },
        ),
        McpToolDefinition(
            name = "tab_open_url",
            description = "Open a new browser tab at the given URL.",
            inputSchema = OPEN_URL_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args ->
                val p = activeTabsProvider ?: return@McpToolHandler unavailable()
                val url = args.string("url")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: url", isError = true)
                val title = args.string("title") ?: url
                val newId = p.createBrowserTab(url, title)
                if (newId != null) McpToolResult("Opened browser tab $newId at $url.")
                else McpToolResult("Could not open browser tab.", isError = true)
            },
        ),
    )

    private fun unavailable(): McpToolResult =
        McpToolResult("Active tabs provider unavailable in this context.", isError = true)

    private companion object {
        const val TAB_ID_SCHEMA =
            """{"type":"object","properties":{"tab_id":{"type":"string","description":"The tab id from tabs_list."}},"required":["tab_id"]}"""
        const val OPEN_URL_SCHEMA =
            """{"type":"object","properties":{"url":{"type":"string","description":"URL to open."},"title":{"type":"string","description":"Optional tab title."}},"required":["url"]}"""
    }
}
