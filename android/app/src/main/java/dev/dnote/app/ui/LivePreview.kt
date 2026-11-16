package dev.dnote.app.ui

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
    /** The most recent result, for drawing and hit-testing. */
    var last: Preview? = null
        private set

    override fun filter(text: AnnotatedString): TransformedText {
        val p = buildPreview(text.text, colors, active, cursor)
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
private val boldRx = Regex("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1")
private val italicRx = Regex("(?<![*_\\w])([*_])(?=\\S)([^*_\\n]+?)(?<=\\S)\\1(?![*_\\w])")
private val strikeRx = Regex("~~(?=\\S)(.+?)(?<=\\S)~~")
private val codeRx = Regex("`([^`\\n]+)`")
private val linkRx = Regex("(?<!!)\\[([^\\]\\n]+)\\]\\(([^)\\n]+)\\)")
private val imageRx = Regex("!\\[[^\\]\\n]*\\]\\([^)\\n]+\\)")

fun buildPreview(s: String, c: PreviewColors, active: TextRange?, cursor: Int?): Preview {
    val hidden = BooleanArray(s.length)
    val spans = ArrayList<Triple<SpanStyle, Int, Int>>()
    val pending = ArrayList<(IntArray) -> Widget>()
    val code = ArrayList<IntRange>()
    fun style(st: SpanStyle, from: Int, to: Int) { if (to > from) spans += Triple(st, from, to) }
    fun hide(from: Int, to: Int) { for (i in from until to) hidden[i] = true }
    fun isActive(pos: Int): Boolean {
        val a = active ?: return false
        return pos in a.min..a.max
    }

    // Blocks, line by line.
    var inFence = false
    var start = 0
    while (start <= s.length) {
        val nl = s.indexOf('\n', start).let { if (it < 0) s.length else it }
        val line = s.substring(start, nl)
        val ls = start
        val lineActive = isActive(ls)
        start = nl + 1
        val fenceLine = line.trimStart().startsWith("```")
        if (fenceLine || inFence) {
            style(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.9.em, background = c.codeBg, color = if (fenceLine) c.faint else c.text), ls, nl)
            code += ls until nl
            if (fenceLine) inFence = !inFence
            if (nl >= s.length) break else continue
        }
        headingRx.find(line)?.let { m ->
            val level = m.groupValues[1].length
            val size = listOf(1.5f, 1.3f, 1.15f, 1.05f, 1f, 1f)[level - 1]
            style(SpanStyle(fontWeight = if (level <= 2) FontWeight.Bold else FontWeight.SemiBold, fontSize = size.em, letterSpacing = (-0.02).em, color = c.text), ls, nl)
            style(SpanStyle(color = c.faint), ls, ls + m.range.last + 1)
            if (!lineActive) hide(ls, ls + m.range.last + 1)
        } ?: ruleRx.find(line)?.let {
            if (!lineActive) {
                style(SpanStyle(color = Color.Transparent), ls, nl)
                pending += { map -> Widget.Rule(map[ls]) }
            } else style(SpanStyle(color = c.faint), ls, nl)
        } ?: quoteRx.find(line)?.let { m ->
            style(SpanStyle(color = c.muted), ls, nl)
            if (lineActive) style(SpanStyle(color = c.accent), ls, ls + m.range.last + 1) else hide(ls, ls + m.range.last + 1)
            pending += { map -> Widget.Quote(map[ls], map[nl]) }
        } ?: taskRx.find(line)?.let { m ->
            val marker = m.groups[2]!!.range.let { (ls + it.first) to (ls + it.last + 1) }
            val box = m.groups[3]!!.range.let { (ls + it.first) to (ls + it.last + 1) }
            val checked = s.substring(box.first, box.second).lowercase() == "[x]"
            val textStart = box.second + 1
            if (lineActive && cursor != null && cursor in ls until textStart) {
                style(SpanStyle(color = c.accent), marker.first, box.second)
            } else {
                hide(marker.first, marker.second)
                // "[" and "]" stay (transparent, spaced out to make room for the drawn box); the
                // " "/"x" between them is hidden so ticked and unticked boxes are the same width.
                hide(box.first + 1, box.first + 2)
                style(SpanStyle(color = Color.Transparent, letterSpacing = 0.5.em), box.first, box.second)
                pending += { map -> Widget.Task(map[box.first], checked, TextRange(box.first, box.second)) }
            }
            if (checked && textStart < nl) style(SpanStyle(color = c.faint, textDecoration = TextDecoration.LineThrough), textStart, nl)
        } ?: bulletRx.find(line)?.let { m ->
            val mark = ls + m.groups[2]!!.range.first
            if (lineActive && cursor != null && cursor in ls..mark) {
                style(SpanStyle(color = c.accent), mark, mark + 1)
            } else {
                style(SpanStyle(color = Color.Transparent), mark, mark + 1)
                pending += { map -> Widget.Bullet(map[mark]) }
            }
        } ?: orderedRx.find(line)?.let { m ->
            m.groups[2]!!.range.let { style(SpanStyle(color = c.accent), ls + it.first, ls + it.last + 1) }
        }
        if (nl >= s.length) break
    }

    // Inline syntax, outside code.
    fun each(rx: Regex, body: (MatchResult) -> Unit) {
        rx.findAll(s).forEach { m -> if (code.none { it.first < m.range.last + 1 && m.range.first < it.last + 1 }) body(m) }
    }
    fun marks(m: MatchResult, n: Int) {
        val a = m.range.first
        val b = m.range.last + 1
        if (isActive(a)) {
            style(SpanStyle(color = c.faint), a, a + n)
            style(SpanStyle(color = c.faint), b - n, b)
        } else {
            hide(a, a + n)
            hide(b - n, b)
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
        if (isActive(m.range.first)) {
            style(SpanStyle(color = c.faint), label.last + 1, m.range.last + 1)
        } else {
            hide(m.range.first, label.first)
            hide(label.last + 1, m.range.last + 1)
        }
    }
    each(imageRx) { m -> style(SpanStyle(color = c.faint, fontSize = 0.88.em), m.range.first, m.range.last + 1) }

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
    return Preview(TransformedText(out.toAnnotatedString(), mapping), pending.map { it(toShown) })
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
        is Widget.Task -> if (w.at + 2 < n) {
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
        if (w !is Widget.Task || w.at + 2 >= n) continue
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
    return if (cont.isEmpty()) {
        // Empty item: drop its marker and stay on the (now blank) line.
        TextFieldValue(prev.text.removeRange(lineStart, at), TextRange(lineStart))
    } else {
        val text = prev.text.substring(0, at) + "\n" + cont + prev.text.substring(at)
        TextFieldValue(text, TextRange(at + 1 + cont.length))
    }
}
