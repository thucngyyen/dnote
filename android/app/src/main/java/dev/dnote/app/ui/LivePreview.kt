package dev.dnote.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import uniffi.dnote_ffi.listContinuation

/** Colours the live preview draws with. */
data class PreviewColors(
    val text: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val codeBg: Color,
    val border: Color,
)

/** Something drawn over the text, at a position in the *transformed* text. */
sealed interface Widget {
    /** Task box over "[ ]"; [box] is the original range of "[ ]". */
    data class Task(val at: Int, val checked: Boolean, val box: TextRange) : Widget
    data class Bullet(val at: Int) : Widget
    data class Quote(val from: Int, val to: Int) : Widget
    data class Rule(val at: Int) : Widget
}

/** One styling pass: the text as shown, how offsets map, and what to draw on top. */
class Preview(val text: TransformedText, val widgets: List<Widget>)

/**
 * Live preview for the editor: the note stays Markdown, but syntax is hidden on every line
 * except the one with the cursor ([active], original offsets, null when not editing), bullets
 * and task boxes are drawn (see [drawPreview]), quotes get a bar and `---` a rule.
 */
class LivePreviewTransformation(
    private val colors: PreviewColors,
    private val active: TextRange?,
    private val cursor: Int?,
) : VisualTransformation {
    /**
     * The most recent result, for drawing and hit-testing. Snapshot state, so drawing follows
     * it: ticking a box changes only what's drawn ("[ ]" and "[x]" lay out identically).
     */
    var last: Preview? by mutableStateOf(null)
        private set

    private var lastInput: String? = null

    override fun filter(text: AnnotatedString): TransformedText {
        // The text field filters the same text more than once per change; reuse the result.
        last?.let { if (text.text == lastInput) return it.text }
        val p = buildPreview(text.text, colors, active, cursor)
        lastInput = text.text
        last = p
        return p.text
    }

    override fun equals(other: Any?) =
        other is LivePreviewTransformation && other.colors == colors && other.active == active && other.cursor == cursor

    override fun hashCode() = (colors.hashCode() * 31 + active.hashCode()) * 31 + (cursor ?: -1)
}

private val headingRx = Regex("^(#{1,6})(?:[ \\t]+|$)")
private val ruleRx = Regex("^ {0,3}([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$")
private val taskRx = Regex("^([ \\t]*)([-*+] )(\\[[ xX]\\])(?: |$)")
private val bulletRx = Regex("^([ \\t]*)([-*+]) ")
private val orderedRx = Regex("^([ \\t]*)(\\d{1,9}[.)]) ")
private val quoteRx = Regex("^>[ \\t]?")
private val markerRx = Regex("^[ \\t]*(?:[-*+] (?:\\[[ xX]\\] ?)?|\\d{1,9}[.)] |> ?)")

/** Length of a line's list/task/quote marker ("  - [ ] " → 8), 0 when there is none. */
fun markerLength(line: String): Int = markerRx.find(line)?.value?.length ?: 0
private val boldRx = Regex("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1")
private val italicRx = Regex("(?<![*_\\w])([*_])(?=\\S)([^*_\\n]+?)(?<=\\S)\\1(?![*_\\w])")
private val strikeRx = Regex("~~(?=\\S)(.+?)(?<=\\S)~~")
private val codeRx = Regex("`([^`\\n]+)`")
private val linkRx = Regex("(?<!!)\\[([^\\]\\n]+)\\]\\(([^)\\n]+)\\)")
private val imageRx = Regex("!\\[[^\\]\\n]*\\]\\([^)\\n]+\\)")

/** A widget found on one line, at line-relative original offsets. */
private sealed interface LineWidget {
    data class Task(val box: Int, val checked: Boolean) : LineWidget
    data class Bullet(val at: Int) : LineWidget
    data class Quote(val length: Int) : LineWidget
    data object Rule : LineWidget
}

/** How one line is shown. Offsets are relative to the line start. */
private class LineStyle(
    val hidden: IntArray,
    val spans: List<Triple<SpanStyle, Int, Int>>,
    val widgets: List<LineWidget>,
)

/** Everything a line's rendering depends on. */
private data class LineKey(
    val line: String,
    val colors: PreviewColors,
    val inFence: Boolean,
    /** Cursor column capped at the marker end ([markerLength]); null when not the active line. */
    val cursor: Int?,
)

/**
 * Styled lines, reused across keystrokes: typing restyles only the line being edited (and the
 * line the cursor left), which keeps long notes responsive.
 */
private val lineCache = object : LinkedHashMap<LineKey, LineStyle>(1024, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LineKey, LineStyle>?) = size > 8192
}

fun buildPreview(s: String, c: PreviewColors, active: TextRange?, cursor: Int?): Preview {
    val hidden = BooleanArray(s.length)
    val spans = ArrayList<Triple<SpanStyle, Int, Int>>()
    val found = ArrayList<Pair<Int, LineWidget>>()

    var inFence = false
    var start = 0
    while (start <= s.length) {
        val nl = s.indexOf('\n', start).let { if (it < 0) s.length else it }
        val line = s.substring(start, nl)
        val ls = start
        val lineActive = active != null && ls in active.min..active.max
        val col = if (lineActive && cursor != null) minOf((cursor - ls).coerceAtLeast(0), markerLength(line)) else null
        val key = LineKey(line, c, inFence, col)
        val st = lineCache.getOrPut(key) { styleLine(line, c, inFence, lineActive, col) }
        for (i in st.hidden) hidden[ls + i] = true
        for ((sp, f, t) in st.spans) spans += Triple(sp, ls + f, ls + t)
        for (w in st.widgets) found += ls to w
        if (line.trimStart().startsWith("```")) inFence = !inFence
        start = nl + 1
        if (nl >= s.length) break
    }

    // Offsets: original -> shown (hidden characters collapse), and shown -> original.
    val toShown = IntArray(s.length + 1)
    val visible = StringBuilder(s.length)
    val toOriginal = IntArray(s.length + 1)
    for (i in s.indices) {
        toShown[i] = visible.length
        if (!hidden[i]) {
            toOriginal[visible.length] = i
            visible.append(s[i])
        }
    }
    toShown[s.length] = visible.length
    toOriginal[visible.length] = s.length
    val shownLength = visible.length

    val out = AnnotatedString.Builder(visible.toString())
    for ((st, from, to) in spans) {
        val a = toShown[from]
        val b = toShown[to]
        if (b > a) out.addStyle(st, a, b)
    }
    val mapping = object : OffsetMapping {
        override fun originalToTransformed(offset: Int) = toShown[offset.coerceIn(0, s.length)]
        override fun transformedToOriginal(offset: Int) = toOriginal[offset.coerceIn(0, shownLength)]
    }
    val widgets = found.map { (ls, w) ->
        when (w) {
            is LineWidget.Task -> Widget.Task(toShown[ls + w.box], w.checked, TextRange(ls + w.box, ls + w.box + 3))
            is LineWidget.Bullet -> Widget.Bullet(toShown[ls + w.at])
            is LineWidget.Quote -> Widget.Quote(toShown[ls], toShown[ls + w.length])
            LineWidget.Rule -> Widget.Rule(toShown[ls])
        }
    }
    return Preview(TransformedText(out.toAnnotatedString(), mapping), widgets)
}

/**
 * Styles one line. [col] is the cursor column (capped at the marker end) on the active line.
 * Every rule is line-local except fenced code, whose state comes in as [inFence].
 */
private fun styleLine(line: String, c: PreviewColors, inFence: Boolean, active: Boolean, col: Int?): LineStyle {
    val n = line.length
    val hidden = ArrayList<Int>()
    val spans = ArrayList<Triple<SpanStyle, Int, Int>>()
    val widgets = ArrayList<LineWidget>()
    fun style(st: SpanStyle, from: Int, to: Int) { if (to > from) spans += Triple(st, from, to) }
    fun hide(from: Int, to: Int) { for (i in from until to) hidden += i }

    val fenceLine = line.trimStart().startsWith("```")
    if (fenceLine || inFence) {
        style(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.9.em, background = c.codeBg, color = if (fenceLine) c.faint else c.text), 0, n)
        return LineStyle(hidden.toIntArray(), spans, widgets)
    }

    headingRx.find(line)?.let { m ->
        val level = m.groupValues[1].length
        val size = listOf(1.5f, 1.3f, 1.15f, 1.05f, 1f, 1f)[level - 1]
        style(SpanStyle(fontWeight = if (level <= 2) FontWeight.Bold else FontWeight.SemiBold, fontSize = size.em, letterSpacing = (-0.02).em, color = c.text), 0, n)
        style(SpanStyle(color = c.faint), 0, m.range.last + 1)
        if (!active) hide(0, m.range.last + 1)
    } ?: ruleRx.find(line)?.let {
        if (!active) {
            style(SpanStyle(color = Color.Transparent), 0, n)
            widgets += LineWidget.Rule
        } else style(SpanStyle(color = c.faint), 0, n)
    } ?: quoteRx.find(line)?.let { m ->
        style(SpanStyle(color = c.muted), 0, n)
        if (active) style(SpanStyle(color = c.accent), 0, m.range.last + 1) else hide(0, m.range.last + 1)
        widgets += LineWidget.Quote(n)
    } ?: taskRx.find(line)?.let { m ->
        val marker = m.groups[2]!!.range
        val box = m.groups[3]!!.range
        val checked = line.substring(box.first, box.last + 1).lowercase() == "[x]"
        val textStart = box.last + 2
        if (active && col != null && col < textStart) {
            style(SpanStyle(color = c.accent), marker.first, box.last + 1)
        } else {
            hide(marker.first, marker.last + 1)
            // "[" and "]" stay (transparent, spaced out to make room for the drawn box); the
            // " "/"x" between them is hidden so ticked and unticked boxes are the same width.
            hide(box.first + 1, box.first + 2)
            style(SpanStyle(color = Color.Transparent, letterSpacing = 0.5.em), box.first, box.last + 1)
            widgets += LineWidget.Task(box.first, checked)
        }
        if (checked && textStart < n) style(SpanStyle(color = c.faint, textDecoration = TextDecoration.LineThrough), textStart, n)
    } ?: bulletRx.find(line)?.let { m ->
        val mark = m.groups[2]!!.range.first
        if (active && col != null && col <= mark) {
            style(SpanStyle(color = c.accent), mark, mark + 1)
        } else {
            style(SpanStyle(color = Color.Transparent), mark, mark + 1)
            widgets += LineWidget.Bullet(mark)
        }
    } ?: orderedRx.find(line)?.let { m ->
        m.groups[2]!!.range.let { style(SpanStyle(color = c.accent), it.first, it.last + 1) }
    }

    // Inline syntax, outside inline code.
    val code = ArrayList<IntRange>()
    fun each(rx: Regex, body: (MatchResult) -> Unit) {
        rx.findAll(line).forEach { m -> if (code.none { it.first < m.range.last + 1 && m.range.first < it.last + 1 }) body(m) }
    }
    fun marks(m: MatchResult, k: Int) {
        val a = m.range.first
        val b = m.range.last + 1
        if (active) {
            style(SpanStyle(color = c.faint), a, a + k)
            style(SpanStyle(color = c.faint), b - k, b)
        } else {
            hide(a, a + k)
            hide(b - k, b)
        }
    }
    each(codeRx) { m ->
        style(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.9.em, background = c.codeBg), m.range.first, m.range.last + 1)
        code += m.range
        marks(m, 1)
    }
    each(boldRx) { m -> style(SpanStyle(fontWeight = FontWeight.Bold, color = c.text), m.range.first, m.range.last + 1); marks(m, 2) }
    each(italicRx) { m -> style(SpanStyle(fontStyle = FontStyle.Italic), m.range.first, m.range.last + 1); marks(m, 1) }
    each(strikeRx) { m -> style(SpanStyle(textDecoration = TextDecoration.LineThrough), m.range.first, m.range.last + 1); marks(m, 2) }
    each(linkRx) { m ->
        val label = m.groups[1]!!.range
        style(SpanStyle(color = c.accent, fontWeight = FontWeight.Medium), label.first, label.last + 1)
        if (active) {
            style(SpanStyle(color = c.faint), label.last + 1, m.range.last + 1)
        } else {
            hide(m.range.first, label.first)
            hide(label.last + 1, m.range.last + 1)
        }
    }
    each(imageRx) { m -> style(SpanStyle(color = c.faint, fontSize = 0.88.em), m.range.first, m.range.last + 1) }
    return LineStyle(hidden.toIntArray(), spans, widgets)
}

/** Draws bullets, task boxes, quote bars and rules over the laid-out text. */
fun DrawScope.drawPreview(p: Preview, layout: TextLayoutResult, c: PreviewColors) {
    val n = layout.layoutInput.text.length
    fun box(i: Int): Rect = layout.getBoundingBox(i.coerceIn(0, (n - 1).coerceAtLeast(0)))
    for (w in p.widgets) when (w) {
        is Widget.Bullet -> if (w.at < n) {
            val b = box(w.at)
            val line = layout.getLineForOffset(w.at)
            val cy = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2
            drawCircle(c.faint, radius = 2.8f * density, center = Offset(b.center.x, cy + 1f * density))
        }
        // "[]" (the inner character is hidden): the box needs "[" and "]" at w.at, w.at + 1.
        is Widget.Task -> if (w.at + 1 < n) {
            val first = box(w.at)
            val line = layout.getLineForOffset(w.at)
            val cy = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2 + 1f * density
            val side = 17f * density
            val topLeft = Offset(first.left + 1f * density, cy - side / 2)
            val r = CornerRadius(side * 0.28f)
            if (w.checked) {
                drawRoundRect(c.accent, topLeft, Size(side, side), r)
                val tick = Path().apply {
                    moveTo(topLeft.x + side * 0.27f, topLeft.y + side * 0.52f)
                    lineTo(topLeft.x + side * 0.44f, topLeft.y + side * 0.69f)
                    lineTo(topLeft.x + side * 0.74f, topLeft.y + side * 0.33f)
                }
                drawPath(tick, Color.White, style = Stroke(width = side * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            } else {
                val inset = 0.75f * density
                drawRoundRect(c.faint, topLeft + Offset(inset, inset), Size(side - 2 * inset, side - 2 * inset), r, style = Stroke(width = 1.5f * density))
            }
        }
        is Widget.Quote -> {
            val a = layout.getLineForOffset(w.from.coerceIn(0, n))
            val b = layout.getLineForOffset(w.to.coerceIn(0, n))
            val top = layout.getLineTop(a) + 3f * density
            val bottom = layout.getLineBottom(b) - 3f * density
            drawRoundRect(c.accent, Offset(-12f * density, top), Size(3f * density, bottom - top), CornerRadius(1.5f * density))
        }
        is Widget.Rule -> {
            val line = layout.getLineForOffset(w.at.coerceIn(0, n))
            val y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2
            drawRect(c.border, Offset(0f, y), Size(size.width, 1f * density))
        }
    }
}

/** The task box under [position] (text-layout coordinates), as the original "[ ]" range. */
fun taskAt(p: Preview, layout: TextLayoutResult, position: Offset, density: Float): TextRange? {
    val n = layout.layoutInput.text.length
    for (w in p.widgets) {
        if (w !is Widget.Task || w.at + 1 >= n) continue
        val a = layout.getBoundingBox(w.at)
        val line = layout.getLineForOffset(w.at)
        val hit = Rect(a.left - 10 * density, layout.getLineTop(line), a.left + 28 * density, layout.getLineBottom(line))
        if (hit.contains(position)) return w.box
    }
    return null
}

/** Tick or untick the task whose "[ ]" is at [box]. */
fun toggleTask(v: TextFieldValue, box: TextRange): TextFieldValue {
    val i = box.min + 1
    if (i >= v.text.length) return v
    val next = if (v.text[i] == ' ') 'x' else ' '
    return v.copy(text = v.text.substring(0, i) + next + v.text.substring(i + 1))
}

/**
 * Enter in a list continues it ("- ", "- [ ] ", "3. ", "> "); Enter on an empty item ends the
 * list. [prev] is the value before the edit, [next] after. Returns [next] unchanged otherwise.
 */
fun continueList(prev: TextFieldValue, next: TextFieldValue): TextFieldValue {
    val at = prev.selection.min
    if (!prev.selection.collapsed || next.text.length != prev.text.length + 1) return next
    if (next.text.getOrNull(at) != '\n' || next.text.removeRange(at, at + 1) != prev.text) return next
    val lineStart = prev.text.lastIndexOf('\n', at - 1) + 1
    val cont = listContinuation(prev.text.substring(lineStart, at)) ?: return next
    return if (cont.endList) {
        // Empty item: replace its marker ("> " inside a quote) and stay on this line.
        val text = prev.text.substring(0, lineStart) + cont.prefix + prev.text.substring(at)
        TextFieldValue(text, TextRange(lineStart + cont.prefix.length))
    } else {
        val text = prev.text.substring(0, at) + "\n" + cont.prefix + prev.text.substring(at)
        TextFieldValue(text, TextRange(at + 1 + cont.prefix.length))
    }
}
