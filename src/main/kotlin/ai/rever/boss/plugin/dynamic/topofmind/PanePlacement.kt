package ai.rever.boss.plugin.dynamic.topofmind

import androidx.compose.ui.geometry.Rect

/**
 * Where a pane sits on its workspace, read off the NAME the host gave it.
 *
 * The host derives that name from the panes' measured rectangles (`paneLabel` over `paneGlyphFor`)
 * and hands it over as `ActiveTabData.splitPosition`; these are the eight it can produce that say a
 * position, and each one maps back to the rectangle it was named for. So a pane the host called
 * "Top right" is drawn in the top right, by both things here that draw a pane.
 *
 * ONE definition, because two things read it - the position glyph on a section header and the floor
 * plate in the floors stack - and a second copy is how a header and a storey start disagreeing
 * about which side a pane is on.
 *
 * **Schematic in proportion, exact in structure.** A name says which edges a pane touches and
 * nothing about where the divider is, so a split dragged to 20/80 comes back as halves. That is a
 * property of the name rather than a shortcut taken here: measured rectangles are what the host
 * named the pane FROM, and nothing on the plugin api hands a plugin those. If one ever does, this
 * is the function both callers should stop using.
 *
 * Null for "Pane 3" and for anything unrecognised. The host numbers a pane precisely when no honest
 * name fits - the middle column of a three-way split, a pane nested two deep - so there is no
 * rectangle to return, and inventing one would be the guess this whole file exists to avoid.
 */
internal fun paneAreaFor(sectionName: String): Rect? =
    when (sectionName.trim().lowercase()) {
        "left" -> Rect(0f, 0f, 0.5f, 1f)
        "right" -> Rect(0.5f, 0f, 1f, 1f)
        "top" -> Rect(0f, 0f, 1f, 0.5f)
        "bottom" -> Rect(0f, 0.5f, 1f, 1f)
        "top left" -> Rect(0f, 0f, 0.5f, 0.5f)
        "top right" -> Rect(0.5f, 0f, 1f, 0.5f)
        "bottom left" -> Rect(0f, 0.5f, 0.5f, 1f)
        "bottom right" -> Rect(0.5f, 0.5f, 1f, 1f)
        else -> null
    }

/** A pane the host named "Top" or "Bottom" runs the full width, so its siblings are stacked. */
internal fun namesStackedPane(sectionName: String): Boolean =
    sectionName.equals("Top", ignoreCase = true) || sectionName.equals("Bottom", ignoreCase = true)
