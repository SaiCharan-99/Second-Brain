package com.secondbrain.app.vault

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.secondbrain.app.AppColors

/**
 * A hand-rolled Markdown-to-[AnnotatedString] renderer for the reader pane —
 * deliberately not a real parser, for the same reason
 * [com.secondbrain.voice.SpeechNormalizer] gives for itself: "a parser would
 * be correct and slow to write... a sequence of targeted rewrites is what the
 * job actually needs." A note body is `NoteRenderer` output or a person typing
 * by hand — headings, emphasis, inline code, bullets and `[[wikilinks]]`, and
 * nothing this format doesn't produce.
 *
 * The one piece of real logic is [[wikilink]] styling: EC-N7/EC-N8's rule ("a
 * wrong link is worse than no link") is what WF-5 asks to make visible —
 * "A dangling link renders visually distinct." Compose's [TextDecoration] has
 * no dashed variant for inline spans, so distinctness here is colour
 * ([AppColors.Dangling] vs [AppColors.Blue]) rather than a literal dash
 * pattern; the design board's intent survives, the exact stroke does not.
 *
 * Clicks are embedded per-span via [LinkAnnotation.Clickable] rather than a
 * caller-side offset lookup — `ClickableText` (the offset-lookup API) is
 * deprecated in favour of exactly this shape.
 */
object NoteMarkdown {

    private val wikilinkRe = Regex("""\[\[([^\[\]]+)]]""")
    private val boldRe = Regex("""\*\*(.+?)\*\*""")
    private val italicRe = Regex("""(?<![*\w])\*(?!\*)(?!\s)(.+?)(?<!\s)\*(?![*\w])""")
    private val codeRe = Regex("""`([^`]+)`""")
    private val headingRe = Regex("""^(#{1,6})\s+(.*)$""")
    private val bulletRe = Regex("""^\s*[-*+]\s+(.*)$""")

    /**
     * @param dangling rawTargets (exactly the text inside `[[ ]]`, alias/heading
     *   syntax included — the same key [com.secondbrain.vault.VaultIndex]
     *   stores) with no resolved note. Every other `[[wikilink]]` renders as
     *   resolved — the safe default, since a target absent from this set is
     *   either genuinely resolved or a bug, and neither should look like a
     *   dead link inviting a stub nobody asked for.
     * @param onWikilinkClick called with the raw target text when a
     *   `[[wikilink]]` span is clicked. The caller decides what "resolved" vs
     *   "dangling" means for that click — this function only renders.
     */
    fun render(body: String, dangling: Set<String>, onWikilinkClick: (String) -> Unit): AnnotatedString =
        buildAnnotatedString {
            val lines = body.lines()
            lines.forEachIndexed { index, rawLine ->
                renderLine(rawLine, dangling, onWikilinkClick)
                if (index != lines.lastIndex) append('\n')
            }
        }

    private fun AnnotatedString.Builder.renderLine(rawLine: String, dangling: Set<String>, onWikilinkClick: (String) -> Unit) {
        headingRe.find(rawLine)?.let { m ->
            val level = m.groupValues[1].length
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = (22 - level * 2).coerceAtLeast(15).sp)) {
                renderInline(m.groupValues[2], dangling, onWikilinkClick)
            }
            return
        }
        bulletRe.find(rawLine)?.let { m ->
            append("•  ")
            renderInline(m.groupValues[1], dangling, onWikilinkClick)
            return
        }
        renderInline(rawLine, dangling, onWikilinkClick)
    }

    private data class InlineMatch(val range: IntRange, val kind: Kind, val inner: String)
    private enum class Kind { WIKILINK, BOLD, ITALIC, CODE }

    private fun nextInlineMatch(text: String, from: Int): InlineMatch? =
        listOfNotNull(
            wikilinkRe.find(text, from)?.let { InlineMatch(it.range, Kind.WIKILINK, it.groupValues[1]) },
            boldRe.find(text, from)?.let { InlineMatch(it.range, Kind.BOLD, it.groupValues[1]) },
            codeRe.find(text, from)?.let { InlineMatch(it.range, Kind.CODE, it.groupValues[1]) },
            italicRe.find(text, from)?.let { InlineMatch(it.range, Kind.ITALIC, it.groupValues[1]) },
        ).minByOrNull { it.range.first }

    /** Bold, italic, inline code and [[wikilinks]] within one line's text. */
    private fun AnnotatedString.Builder.renderInline(text: String, dangling: Set<String>, onWikilinkClick: (String) -> Unit) {
        var cursor = 0
        while (cursor < text.length) {
            val match = nextInlineMatch(text, cursor)
            if (match == null) {
                append(text.substring(cursor))
                break
            }
            if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
            when (match.kind) {
                Kind.WIKILINK -> appendWikilink(match.inner, dangling, onWikilinkClick)
                Kind.BOLD -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(match.inner) }
                Kind.ITALIC -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(match.inner) }
                Kind.CODE -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = AppColors.Ink.copy(alpha = 0.06f)),
                ) { append(match.inner) }
            }
            cursor = match.range.last + 1
        }
    }

    private fun AnnotatedString.Builder.appendWikilink(rawTarget: String, dangling: Set<String>, onClick: (String) -> Unit) {
        val color = if (rawTarget in dangling) AppColors.Dangling else AppColors.Blue
        pushLink(LinkAnnotation.Clickable(tag = rawTarget, linkInteractionListener = LinkInteractionListener { onClick(rawTarget) }))
        withStyle(SpanStyle(color = color, textDecoration = TextDecoration.Underline)) {
            append(displayTarget(rawTarget))
        }
        pop()
    }

    /** F14: `[[Target|alias]]` and `[[Target#heading]]` display their human part, never the raw brackets. */
    internal fun displayTarget(rawTarget: String): String {
        val afterAlias = rawTarget.split('|', limit = 2).let { if (it.size == 2) it[1] else it[0] }
        return afterAlias.substringBefore('#').trim().ifBlank { rawTarget.trim() }
    }
}
