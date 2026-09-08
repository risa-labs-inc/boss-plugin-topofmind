package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.workspace.LayoutWorkspace
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.TabConfig

/**
 * The placeholders that make a layout a TEMPLATE rather than a Space.
 *
 * **A copy of the host's `PROJECT_PLACEHOLDERS`** (`components/workspaces/`
 * `WorkspaceProjectRequirement.kt`), string for string, exactly as [SpaceIcon] copies the host's
 * glyph and [paneAreaFor] its pane arithmetic. A plugin cannot import a host internal, and the api
 * exposes the workspace TYPES ([LayoutWorkspace], [SplitConfig]) without exposing this predicate
 * over them - so the scan is expressible here and only the four strings have to be repeated.
 *
 * They are in ONE place on this side too, behind [isTemplate], rather than being asked for at each
 * tile: two copies of the rule inside one file is worse than one copy across two repos, because
 * only one of those two can be fixed by reading the other file.
 *
 * If the host ever adds a fifth, this list is stale and a template carrying only the new one is
 * listed as a Space. That is the failure mode of the duplication and it is the mild one - a tile in
 * the wrong section, where the host still refuses to materialise it without a project, because the
 * decision that MATTERS is made host-side (see below).
 */
private val PROJECT_PLACEHOLDERS =
    listOf(
        "{projectPath}",
        "{gitRemoteUrl}",
        "{currentFile}",
        "{claudeContinueFlag}",
    )

/**
 * Whether this is a template: a parameterised layout waiting for a project, not a saved Space.
 *
 * The host's `LayoutWorkspace.requiresProject()`, and the same reasoning: a Space is written from
 * live state with real paths in it, so it can never carry an unsubstituted placeholder, and
 * carrying one is therefore what being a template MEANS. There is no `isTemplate` field to read -
 * [LayoutWorkspace] is the api's own data class, member-checked against 33 plugin repos, and a new
 * constructor parameter on it rejects every already-built plugin.
 *
 * **This grouping is presentation, not the mechanism.** Picking a tile in either section calls the
 * same [switchToWorkspace], and the HOST decides what actually opens: it materialises a template
 * into a Space - substituting the placeholders, naming it for the project, saving it - or says why
 * it cannot. A plugin could not do that job if it wanted to, because resolving `{gitRemoteUrl}`
 * forks `git` in the project directory and `{claudeContinueFlag}` reads `~/.claude/projects`.
 */
internal fun LayoutWorkspace.isTemplate(): Boolean = layout.holdsProjectPlaceholder()

private fun SplitConfig.holdsProjectPlaceholder(): Boolean =
    when (this) {
        is SplitConfig.SinglePanel -> panel.tabs.any { it.holdsProjectPlaceholder() }
        is SplitConfig.VerticalSplit -> left.holdsProjectPlaceholder() || right.holdsProjectPlaceholder()
        is SplitConfig.HorizontalSplit -> top.holdsProjectPlaceholder() || bottom.holdsProjectPlaceholder()
    }

private fun TabConfig.holdsProjectPlaceholder(): Boolean =
    listOfNotNull(url, filePath, initialCommand, workingDirectory)
        .any { field -> PROJECT_PLACEHOLDERS.any { it in field } }

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
