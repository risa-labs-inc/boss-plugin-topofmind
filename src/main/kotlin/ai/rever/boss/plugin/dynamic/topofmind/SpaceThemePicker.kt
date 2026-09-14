package ai.rever.boss.plugin.dynamic.topofmind

import ai.rever.boss.plugin.api.BossThemeOption
import ai.rever.boss.plugin.ui.BossColors
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossSecondaryButton
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import ai.rever.boss.plugin.ui.ContextMenuItemData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DIALOG_MIN_WIDTH = 280.dp
private val DIALOG_MAX_WIDTH = 340.dp
private val DIALOG_INSET = 12.dp
private val DIALOG_RADIUS = RoundedCornerShape(8.dp)
private const val DIALOG_TITLE_SP = 15

private val THEME_ROW_HEIGHT = 40.dp
private val THEME_ROW_RADIUS = RoundedCornerShape(4.dp)
private val THEME_ROW_INSET = 8.dp
private val THEME_ROW_GAP = 10.dp
private const val NAME_SP = 13
private val CHECK_SIZE = 16.dp

// The preview plate: a piece of that theme, at the size of a control.
private val PLATE_WIDTH = 44.dp
private val PLATE_HEIGHT = 26.dp
private val PLATE_RADIUS = RoundedCornerShape(4.dp)
private val PLATE_DOT = 13.dp

/** BossTabButton's SELECTED_FILL_ALPHA, the wash this panel marks a chosen row with everywhere. */
private const val SELECTED_FILL_ALPHA = 0.16f

/** What every route to the theme picker calls it. The ellipsis says a dialog follows. */
internal const val SPACE_THEME_MENU_LABEL = "Space Theme..."

/**
 * The right-click row that raises the theme picker, for any surface that names a Space.
 *
 * ONE definition, because there are three ways in now - the header's button, the header's menu and
 * the space map's menu - and they must be one gesture with one label rather than three that drift.
 * Same reason the dialog itself is shared: there is one writer behind all of them.
 */
internal fun spaceThemeMenuItems(onPickTheme: () -> Unit): List<ContextMenuItemData> =
    listOf(
        ContextMenuItemData(
            label = SPACE_THEME_MENU_LABEL,
            icon = SpaceThemeIcon,
            onClick = onPickTheme,
        ),
    )

/**
 * The glyph that means "theme", wherever a Space offers one.
 *
 * A PALETTE, not a gear or a brush. A gear says settings, which is a different page and a
 * different scope; a brush says "paint something", which is editing content rather than choosing
 * a look. A palette is a set of colours to pick from, which is exactly what the dialog behind it
 * is. It is also the glyph the HOST already uses on `Options > Space Theme`, so one mark means one
 * thing whether a user meets it on the Space button or in this panel.
 */
internal val SpaceThemeIcon: ImageVector
    get() = Icons.Outlined.Palette

/**
 * The theme a Space wears, picked from a small dialog.
 *
 * [BossDialog], never a plain Compose `Dialog` or `Popup`: under JxBrowser's hardware-accelerated
 * surface those render BEHIND the page, which is the whole reason the wrapper exists. Content is
 * wrapped in [BossTheme] because the heavyweight path composes it in a window of its own, where
 * the panel's theme is not in scope. Both rules are `SpacePicker`'s and this is the same kind of
 * thing.
 *
 * **A dialog rather than a context submenu, and that is forced.** `ContextMenuItemData` is a
 * label, an icon, a divider, a click and a submenu - there is no colour on it - so a theme list
 * inside the right-click menu would be six words and no swatches, which is a list of names for
 * things whose whole content is how they look. It is the same reason the Space picker is a dialog.
 */
@Composable
internal fun SpaceThemeDialog(
    workspaceName: String,
    themes: List<BossThemeOption>,
    currentThemeId: String?,
    onDismiss: () -> Unit,
    onPick: (BossThemeOption) -> Unit,
) {
    BossDialog(onDismissRequest = onDismiss) {
        BossTheme {
            SpaceThemePickerContent(
                workspaceName = workspaceName,
                themes = themes,
                currentThemeId = currentThemeId,
                onDismiss = onDismiss,
                onPick = onPick,
            )
        }
    }
}

/**
 * The picker's card, separated from the dialog that raises it.
 *
 * `internal` rather than private so it can be composed into an `ImageComposeScene` and rendered to
 * a PNG, for the reason `SpacePickerContent` is: a picker whose entire job is to show what six
 * things LOOK like cannot be verified by reading its arithmetic. The dialog itself cannot be
 * rendered that way - it is a window.
 *
 * **A list, not a grid, where the Space picker is a grid.** The two dialogs are doing different
 * jobs. A Space tile is something you RECOGNISE - a big pane beside two stacked ones - so it wants
 * area and the name is a caption under it. A theme is a short, fixed, NAMED set, and two of the
 * six differ by nothing but the word "Light" while a third (Daylight) is light and does not say
 * so: the name is half the answer, so it belongs beside the preview rather than under it, where a
 * caption under a tile turns the two Blueprints into a pair of identical squares with small print.
 * Six rows is also a shorter dialog than two rows of captioned tiles.
 *
 * **The current theme is marked by the panel's own two marks**, an accent wash at
 * [SELECTED_FILL_ALPHA] and a check, so nobody has to learn a third vocabulary for "this one".
 */
@Composable
internal fun SpaceThemePickerContent(
    workspaceName: String,
    themes: List<BossThemeOption>,
    /**
     * Which theme that Space resolves to, from `ActiveTabsProvider.workspaceThemeId`.
     *
     * **An ID, and the first render of this dialog is why.** Marking by the COLOUR the api already
     * published per Space ticked Blueprint AND Blueprint Light, because they share `#0F5BFF`
     * exactly - two checks in one list, one wash spanning both rows, and no way to tell which was
     * actually on. Null when the host cannot say, and then nothing is ticked, which is honest.
     */
    currentThemeId: String?,
    onDismiss: () -> Unit,
    onPick: (BossThemeOption) -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .requiredWidthIn(min = DIALOG_MIN_WIDTH, max = DIALOG_MAX_WIDTH)
                .border(1.dp, BossThemeColors.BorderColor, DIALOG_RADIUS),
        shape = DIALOG_RADIUS,
        color = BossColors.contextMenuBackground,
    ) {
        Column(modifier = Modifier.padding(DIALOG_INSET)) {
            Text(
                // The Space is named in the title rather than left to be inferred: this dialog is
                // raised from a row, and by the time it is open that row is behind it.
                text = "Theme for \"$workspaceName\"",
                fontSize = DIALOG_TITLE_SP.sp,
                fontWeight = FontWeight.SemiBold,
                color = BossThemeColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(DIALOG_INSET))

            themes.forEach { theme ->
                ThemeRow(
                    theme = theme,
                    isCurrent = theme.id == currentThemeId,
                    onPick = { onPick(theme) },
                )
            }

            Spacer(modifier = Modifier.height(DIALOG_INSET))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BossSecondaryButton(text = "Cancel", onClick = onDismiss)
            }
        }
    }
}

/** One theme: a piece of it, its name, and whether the Space is wearing it. */
@Composable
private fun ThemeRow(
    theme: BossThemeOption,
    isCurrent: Boolean,
    onPick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill =
        when {
            isCurrent -> BossThemeColors.AccentColor.copy(alpha = SELECTED_FILL_ALPHA)
            hovered -> BossColors.darkSurface
            else -> Color.Transparent
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(THEME_ROW_HEIGHT)
                .background(fill, THEME_ROW_RADIUS)
                .hoverable(interaction)
                .clickable(onClick = onPick)
                .padding(horizontal = THEME_ROW_INSET),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(THEME_ROW_GAP),
    ) {
        ThemePlate(theme)
        Text(
            text = theme.name,
            fontSize = NAME_SP.sp,
            color = BossThemeColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isCurrent) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Current theme",
                tint = BossThemeColors.TextPrimary,
                modifier = Modifier.size(CHECK_SIZE),
            )
        }
    }
}

/**
 * A piece of the theme: its own surface, with its own accent on it.
 *
 * **This is how Blueprint and Blueprint Light are told apart, and a swatch could not do it.** They
 * share `#0F5BFF` exactly, so a row of coloured dots would show the same dot twice beside two
 * names that differ by one word - the reader's only evidence would be the small print, which is
 * the wrong way round for a picker about appearance. The GROUND is what actually differs, so the
 * plate is painted in `BossThemeOption.surface` and the accent sits on it: ink under blue against
 * paper under blue, which nobody has to read. It is also a truer preview than any glyph standing
 * in for one - a theme IS a ground and a signal - and it answers `isLight` without being told,
 * though the api says that too for a caller that needs the fact rather than the picture.
 *
 * The border is not decoration: `surface` for a dark theme is very nearly this dialog's own
 * ground, so without an edge the plate would be an invisible rectangle with a dot floating in it.
 */
@Composable
private fun ThemePlate(theme: BossThemeOption) {
    Box(
        modifier =
            Modifier
                .width(PLATE_WIDTH)
                .height(PLATE_HEIGHT)
                .background(theme.surface, PLATE_RADIUS)
                .border(1.dp, BossThemeColors.BorderColor, PLATE_RADIUS),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.size(PLATE_DOT).background(theme.accent, CircleShape))
    }
}
