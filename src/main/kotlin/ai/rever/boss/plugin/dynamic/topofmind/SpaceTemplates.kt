package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.workspace.LayoutWorkspace

/**
 * The ids of every layout BOSS ships, which is what makes a tile a TEMPLATE.
 *
 * **A copy of the host's `PredefinedWorkspaces.allIds`** (`components/workspaces/`
 * `LayoutWorkspace.kt`), id for id, exactly as [SpaceIcon] copies the host's glyph and
 * [paneAreaFor] its pane arithmetic. A plugin cannot import a host internal, and the api exposes
 * the workspace TYPES without exposing which ids the host ships - so the set is repeated here and
 * nowhere else on this side.
 *
 * **It cannot be a prefix test.** `LayoutWorkspace.generateId()` mints `workspace-<epoch millis>`,
 * so a Space the user saved carries the same `workspace-` prefix as a built-in; `startsWith` would
 * file every Space under Templates. The set has to be the explicit ids.
 *
 * The drift mode is worth stating, and it is the mild direction:
 *
 * - **A built-in the host ships and this list does not name shows under Spaces.** That is a tile in
 *   the wrong section and nothing else: picking it still applies, and still materialises if it has
 *   placeholders, because the host owns both of those decisions (see [isTemplate]).
 * - **An id here that the host has retired files nothing at all**, since no saved Space can carry
 *   it - the ids are names like `workspace-gemini`, which `generateId` never produces.
 *
 * So a stale copy degrades to "looks like an ordinary Space", never to "a Space of mine is treated
 * as one of theirs".
 */
private val BUILT_IN_SPACE_IDS =
    setOf(
        "workspace-claude-code",
        "workspace-code-review",
        "workspace-gemini",
        "workspace-codex",
        "workspace-opencode",
        "workspace-terminal-browser",
        "workspace-dual-terminal",
        "workspace-browser",
    )

/**
 * Whether this is a template: one of the layouts BOSS ships, rather than a Space someone saved.
 *
 * **Identity, not shape**, and this is the second answer to that question. It used to be "does the
 * layout still carry an unsubstituted `{projectPath}`", which is the host's `requiresProject()` -
 * a different question that agrees on seven of the eight built-ins and disagrees on **Browser
 * Only**, a single browser panel on a fixed URL with nothing to parameterise. That put one of the
 * shipped layouts in with the user's own Spaces. The user's model is the plain one: the defaults we
 * ship are the templates.
 *
 * **The two notions stay separate rather than being conflated the other way.** Being a template is
 * this; being MATERIALISED on pick is still `requiresProject()`, host-side. So the seven with
 * placeholders become a Space named for the project, and Browser Only is applied as it is - it has
 * nothing to substitute and no project to name a copy after. See [TEMPLATES_HINT], which is
 * therefore worded to promise neither.
 *
 * A plugin could not make the materialising decision anyway: resolving `{gitRemoteUrl}` forks `git`
 * in the project directory and `{claudeContinueFlag}` lists `~/.claude/projects`.
 */
internal fun LayoutWorkspace.isTemplate(): Boolean = id in BUILT_IN_SPACE_IDS

/**
 * The picker's two groups, in the order they are drawn.
 *
 * **A split, not a filter, and both halves keep the host's order.** The workspace list arrives in
 * one order everything in the app agrees on (`WorkspaceManager` puts the built-ins first and the
 * saved ones after), so partitioning preserves it inside each section rather than inventing a
 * second ordering the Space button's menu would disagree with.
 *
 * Spaces come FIRST. Templates are the thing you reach for once, when a project has no Space yet;
 * a Space is what you reach for every other time, and it is what the picker was for before
 * templates were told apart from it.
 */
internal data class SpaceSections(
    val spaces: List<LayoutWorkspace>,
    val templates: List<LayoutWorkspace>,
)

internal fun spaceSectionsOf(workspaces: List<LayoutWorkspace>): SpaceSections {
    val (templates, spaces) = workspaces.partition { it.isTemplate() }
    return SpaceSections(spaces = spaces, templates = templates)
}
