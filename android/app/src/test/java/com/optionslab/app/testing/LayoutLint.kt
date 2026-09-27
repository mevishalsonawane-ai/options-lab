@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.optionslab.app.testing

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density

/**
 * TEST ONLY: finds layout problems in a rendered Compose tree, from the semantics nodes
 * (unmerged tree, so every Text and every clickable is its own node). Each finding names the
 * check, the node (its text / description / tag) and the numbers.
 *
 * Checks (dp are converted with the screen's density):
 *  - OVERLAP     two sibling nodes that show text or can be clicked intersect by more than 1 dp in
 *                both directions (nodes under a test tag "overlay" - dialogs, banners, stacked
 *                layers - are exempt);
 *  - OFFSCREEN   a node extends past the window by more than 1 dp and no ancestor scrolls;
 *  - CLIPPED     a node is cut by its parent (drawn bounds smaller than its size) outside a scroller;
 *  - TEXT        a Text whose layout overflowed (hasVisualOverflow: cut off) or ellipsized a line
 *                (unless the text is listed in [Options.ellipsisOk]);
 *  - TOUCH       a clickable smaller than 48x48 dp. An error for primary actions (send, place,
 *                order, close, confirm, cancel, save, delete, buy, sell, unlock, erase...), else a warning;
 *  - EMPTY       a clickable of zero size, or fully clipped away outside a scroller;
 *  - A11Y        a clickable with no text or content description anywhere inside it (error),
 *                or with no Role (warning);
 *  - ALIGN       nodes tagged "row:<id>:label" / "row:<id>:value" whose vertical centres differ by
 *                more than 2 dp.
 */
object LayoutLint {
    enum class Level { ERROR, WARNING }

    data class Finding(val check: String, val level: Level, val message: String) {
        override fun toString() = "[$level $check] $message"
    }

    data class Options(
        val ellipsisOk: Set<String> = emptySet(),
        /** Texts of nodes drawn on purpose over others (banners, toasts) when they carry no "overlay" tag. */
        val overlays: Set<String> = emptySet(),
        val primary: Regex = Regex("(?i)\\b(send|place|order|close|confirm|cancel|save|delete|remove|buy|sell|unlock|erase|log ?in|square|exit|hold)\\b"),
        val touchDp: Float = 48f,
        val slackDp: Float = 1f,
    )

    fun check(root: SemanticsNode, density: Density, options: Options = Options()): List<Finding> {
        val out = ArrayList<Finding>()
        val px = density.density
        val slack = options.slackDp * px
        val window = root.boundsInWindow.let { if (it.isEmpty) Rect(0f, 0f, root.size.width.toFloat(), root.size.height.toFloat()) else it }

        fun all(n: SemanticsNode): Sequence<SemanticsNode> = sequenceOf(n) + n.children.asSequence().flatMap { all(it) }
        val nodes = all(root).toList()

        fun label(n: SemanticsNode): String {
            val t = n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            val d = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
            val e = n.config.getOrNull(SemanticsProperties.EditableText)?.text
            val tag = n.config.getOrNull(SemanticsProperties.TestTag)
            val inner = if (t == null && d == null) n.children.firstNotNullOfOrNull { c ->
                c.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text } } else null
            return listOfNotNull(t, d, e, inner, tag?.let { "#$it" }).joinToString(" / ").ifEmpty { "node ${n.id}" }.take(60)
        }
        fun ancestors(n: SemanticsNode) = generateSequence(n.parent) { it.parent }
        fun scrolls(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null ||
            n.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null ||
            n.config.getOrNull(SemanticsActions.ScrollBy) != null
        fun inScroller(n: SemanticsNode) = ancestors(n).any(::scrolls)
        fun overlay(n: SemanticsNode) = (sequenceOf(n) + ancestors(n) + n.children.asSequence()).any {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("overlay") == true ||
                it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text in options.overlays } == true
        }
        fun clickable(n: SemanticsNode) = n.config.getOrNull(SemanticsActions.OnClick) != null
        fun shows(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text) != null || clickable(n) ||
            n.config.getOrNull(SemanticsProperties.EditableText) != null
        fun full(n: SemanticsNode) = Rect(n.positionInWindow.x, n.positionInWindow.y,
            n.positionInWindow.x + n.size.width, n.positionInWindow.y + n.size.height)
        fun dp(v: Float) = "%.1f".format(v / px)
        fun hidden(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.InvisibleToUser) != null

        for (n in nodes) {
            if (n === root || hidden(n)) continue
            val f = full(n)
            val drawn = n.boundsInWindow
            val scroller = inScroller(n)
            // OFFSCREEN
            if (!scroller && !overlay(n) && shows(n) && (f.left < window.left - slack || f.top < window.top - slack ||
                    f.right > window.right + slack || f.bottom > window.bottom + slack))
                out += Finding("OFFSCREEN", Level.ERROR, "'${label(n)}' at [${dp(f.left)},${dp(f.top)}-${dp(f.right)},${dp(f.bottom)}] dp " +
                    "is outside the ${dp(window.width)}x${dp(window.height)} dp window, with nothing to scroll it into view")
            // CLIPPED (cut by a parent, not by the window)
            else if (!scroller && shows(n) && n.size.width > 0 && n.size.height > 0 && !drawn.isEmpty &&
                (drawn.width < f.width - slack || drawn.height < f.height - slack))
                out += Finding("CLIPPED", Level.ERROR, "'${label(n)}' is cut by its container: ${dp(f.width)}x${dp(f.height)} dp laid out, " +
                    "${dp(drawn.width)}x${dp(drawn.height)} dp visible")
            // TEXT
            val layouts = ArrayList<TextLayoutResult>()
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            for (t in layouts) {
                val text = t.layoutInput.text.text
                if (t.lineCount == 0) continue
                val last = t.lineCount - 1
                val ellipsized = (0..last).any { t.isLineEllipsized(it) }
                // Measured on the lines themselves: a line wider than the node, or lines lower than it, are cut off.
                // (Width, not right edge: a centred or end-aligned paragraph can be wider than the node it sits in.)
                val widest = (0..last).maxOf { t.getLineRight(it) - t.getLineLeft(it) }
                val bottom = t.getLineBottom(last)
                if (bottom > t.size.height + slack)
                    out += Finding("TEXT", Level.ERROR, "'${text.take(60)}' is cut off: its lines need ${dp(bottom)} dp height, it has ${dp(t.size.height.toFloat())} dp")
                else if (!ellipsized && widest > t.size.width + slack)
                    out += Finding("TEXT", Level.ERROR, "'${text.take(60)}' is cut off at the side: ${dp(widest)} dp of text in ${dp(t.size.width.toFloat())} dp")
                else if (t.multiParagraph.didExceedMaxLines && !ellipsized)
                    out += Finding("TEXT", Level.ERROR, "'${text.take(60)}' has more lines than it may show; the rest is cut")
                if (ellipsized && text !in options.ellipsisOk)
                    out += Finding("TEXT", Level.ERROR, "'${text.take(60)}' is ellipsized")
            }
            if (clickable(n)) {
                val name = label(n)
                val hasText = (sequenceOf(n) + all(n)).any {
                    it.config.getOrNull(SemanticsProperties.Text) != null || it.config.getOrNull(SemanticsProperties.ContentDescription) != null ||
                        it.config.getOrNull(SemanticsActions.OnClick)?.label != null
                }
                // EMPTY
                if (n.size.width == 0 || n.size.height == 0) out += Finding("EMPTY", Level.ERROR, "clickable '$name' has zero size")
                else if (drawn.isEmpty && !scroller) out += Finding("EMPTY", Level.ERROR, "clickable '$name' is entirely clipped away")
                // TOUCH
                val w = n.size.width / px; val h = n.size.height / px
                if (w < options.touchDp - 0.5f || h < options.touchDp - 0.5f) {
                    val primary = options.primary.containsMatchIn(name)
                    out += Finding("TOUCH", if (primary) Level.ERROR else Level.WARNING, "clickable '$name' is ${"%.0f".format(w)}x${"%.0f".format(h)} dp (under 48x48)")
                }
                // A11Y
                if (!hasText) out += Finding("A11Y", Level.ERROR, "clickable node ${n.id} has no text, content description or action label " +
                    "(at [${dp(f.left)},${dp(f.top)}-${dp(f.right)},${dp(f.bottom)}] dp, role ${n.config.getOrNull(SemanticsProperties.Role)}, " +
                    "inside '${generateSequence(n.parent) { it.parent }.map { label(it) }.firstOrNull { !it.startsWith("node ") } ?: ""}'; " +
                    "its semantics [${n.config.joinToString { it.key.name }}], ${n.children.size} children; parents " +
                    generateSequence(n.parent) { it.parent }.take(3).joinToString(" < ") { a -> "[${a.config.joinToString { it.key.name }}]" } + ")")
                if (n.config.getOrNull(SemanticsProperties.Role) == null && n.config.getOrNull(SemanticsProperties.EditableText) == null)
                    out += Finding("A11Y", Level.WARNING, "clickable '$name' has no Role (TalkBack cannot say what it is)")
            }
        }
        // OVERLAP between siblings
        for (parent in nodes) {
            val kids = parent.children.filter { shows(it) && !hidden(it) && !overlay(it) && !it.boundsInWindow.isEmpty }
            for (i in kids.indices) for (j in i + 1 until kids.size) {
                val a = kids[i].boundsInWindow; val b = kids[j].boundsInWindow
                val ix = minOf(a.right, b.right) - maxOf(a.left, b.left)
                val iy = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
                if (ix > slack && iy > slack)
                    out += Finding("OVERLAP", Level.ERROR, "'${label(kids[i])}' and '${label(kids[j])}' overlap by ${dp(ix)}x${dp(iy)} dp")
            }
        }
        // ALIGN
        val rows = nodes.mapNotNull { n -> n.config.getOrNull(SemanticsProperties.TestTag)?.takeIf { it.startsWith("row:") }?.let { it to n } }
            .groupBy({ it.first.split(":").getOrNull(1) }, { it.second })
        for ((id, members) in rows) {
            if (members.size < 2) continue
            val centres = members.map { it.boundsInWindow.center.y }
            val spread = centres.max() - centres.min()
            if (spread > 2 * px) out += Finding("ALIGN", Level.ERROR, "row '$id' is misaligned: centres differ by ${dp(spread)} dp")
        }
        return out.distinct()
    }
}
