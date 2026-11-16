package dev.dnote.app.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.navigationBarsPadding
import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import dev.dnote.app.EditorSession
import dev.dnote.app.NotesViewModel
import dev.dnote.app.Route
import dev.dnote.app.noteTitle
import java.net.URLDecoder
import kotlinx.coroutines.launch
import uniffi.dnote_ffi.renderMarkdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteScreen(model: NotesViewModel, path: String) {
    val dn = LocalDN.current
    val context = LocalContext.current
    var reading by rememberSaveable { mutableStateOf(false) }
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    var menu by remember { mutableStateOf(false) }
    val session = model.session?.takeIf { it.path == path }
    val pinned = model.isPinned(path)

    Scaffold(
        containerColor = dn.bg,
        modifier = Modifier.sharedMorph(model.transitionKey),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = dn.bg),
                navigationIcon = {
                    IconButton(onClick = { model.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = dn.text) }
                },
                title = {
                    // The name is edited in the title field below; the bar just shows where the note lives.
                    if ('/' in path) Text(path.substringBeforeLast('/'), fontSize = 13.sp, color = dn.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                actions = {
                    ModeToggle(reading) { if (it) model.flushSave(); reading = it }
                    IconButton(onClick = { model.togglePin(path) }) {
                        Icon(
                            if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            if (pinned) "Unpin" else "Pin",
                            tint = if (pinned) dn.accent else dn.muted,
                        )
                    }
                    IconButton(onClick = { prompt = Prompt.Tags(path) }) {
                        Icon(Icons.AutoMirrored.Outlined.Label, "Tags", tint = if (model.tagMap[path].isNullOrEmpty()) dn.muted else dn.accent)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More", tint = dn.muted) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Share") }, onClick = {
                                menu = false
                                model.flushSave()
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, session?.text ?: "")
                                context.startActivity(Intent.createChooser(send, path.noteTitle))
                            })
                            DropdownMenuItem(text = { Text("Rename") }, onClick = {
                                menu = false
                                model.entry(path)?.let { prompt = Prompt.Rename(it) }
                            })
                            DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = {
                                menu = false
                                model.entry(path)?.let { prompt = Prompt.Delete(it) }
                            })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            key(path) { NoteTitleField(model, path) }
            NoteTags(model, path) { prompt = Prompt.Tags(path) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    session == null -> {}
                    reading -> MarkdownPreview(model, session.text, path)
                    else -> Editor(model, session)
                }
            }
        }
    }
    PromptHost(model, prompt) { prompt = null }
}

/** Two-icon Write / Read switch with the active side in accent. */
@Composable
private fun ModeToggle(reading: Boolean, onChange: (Boolean) -> Unit) {
    val dn = LocalDN.current
    Row(
        Modifier.padding(end = 4.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, dn.border, RoundedCornerShape(8.dp)),
    ) {
        for ((read, icon, label) in listOf(Triple(false, Icons.Outlined.Edit, "Write"), Triple(true, Icons.Outlined.Visibility, "Read"))) {
            val on = reading == read
            Box(
                Modifier.size(width = 38.dp, height = 32.dp)
                    .background(if (on) dn.accentSoft else Color.Transparent)
                    .clickable { onChange(read) },
                contentAlignment = Alignment.Center,
            ) { Icon(icon, label, tint = if (on) dn.accent else dn.muted, modifier = Modifier.size(18.dp)) }
        }
    }
}

/** Tag chips under the toolbar (tap to edit). */
@Composable
private fun NoteTags(model: NotesViewModel, path: String, onEdit: () -> Unit) {
    val dn = LocalDN.current
    val ids = model.tagMap[path].orEmpty()
    if (ids.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (id in ids) {
            val t = model.tag(id) ?: continue
            Row(
                Modifier.clip(CircleShape).background(tagColor(t.color).copy(alpha = 0.14f)).clickable(onClick = onEdit)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (id == "favorite") Icon(Icons.Filled.Star, null, tint = tagColor(t.color), modifier = Modifier.size(12.dp))
                else Box(Modifier.size(7.dp).clip(CircleShape).background(tagColor(t.color)))
                Spacer(Modifier.width(5.dp))
                Text(t.name, fontSize = 12.5.sp, color = dn.text2, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun Editor(model: NotesViewModel, session: EditorSession) {
    val dn = LocalDN.current
    // Start with the cursor at the end, so typing in a fresh note continues after its heading.
    var value by remember(session) { mutableStateOf(TextFieldValue(session.text, TextRange(session.text.length))) }
    // A sync merged new text into this note: take it, keeping the cursor nearby.
    LaunchedEffect(session.externalRevision) {
        if (value.text != session.text) {
            val cursor = value.selection.start.coerceAtMost(session.text.length)
            value = TextFieldValue(session.text, TextRange(cursor))
        }
    }
    val focus = remember { FocusRequester() }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var focused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Live preview: syntax shows only on the line with the cursor (while editing).
    val colors = remember(dn) { PreviewColors(dn.text, dn.muted, dn.faint, dn.accent, dn.hover, dn.border) }
    val activeLine: TextRange? = if (!focused) null else {
        val t = value.text
        val a = t.lastIndexOf('\n', value.selection.min - 1) + 1
        val b = t.indexOf('\n', value.selection.max).let { if (it < 0) t.length else it }
        TextRange(a, b)
    }
    // Positions past the line's list/quote marker all render the same: cap the key there.
    val cursorKey = if (activeLine == null) null else activeLine.start + minOf(
        value.selection.min - activeLine.start,
        markerLength(value.text.substring(activeLine.start, activeLine.end)),
    )
    val transformation = remember(colors, activeLine, cursorKey) {
        LivePreviewTransformation(colors, activeLine, if (activeLine == null) null else value.selection.min)
    }
    val currentTransformation by rememberUpdatedState(transformation)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    fun update(raw: TextFieldValue) {
        val next = continueList(value, raw)
        val changed = next.text != value.text
        value = next
        if (changed) {
            session.text = next.text
            model.edited()
        }
    }

    // The tap handler below outlives recompositions: always act on the current note.
    val onToggleTask by rememberUpdatedState { box: TextRange -> update(toggleTask(value, box)) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val markdown = model.addImage(session.path, uri) ?: return@launch
            val v = value
            val at = v.selection.max
            val before = v.text.substring(0, at)
            val prefix = if (before.isEmpty() || before.endsWith("\n")) "" else "\n"
            val insert = "$prefix$markdown\n"
            update(TextFieldValue(before + insert + v.text.substring(at), TextRange(at + insert.length)))
        }
    }

    // imePadding first, then navigationBarsPadding: with the keyboard up only the keyboard is
    // padded; without it, the gesture/navigation bar is, so the format bar is never underneath it.
    Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
        BasicTextField(
            value = value,
            onValueChange = ::update,
            visualTransformation = transformation,
            onTextLayout = { layout = it },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = dn.text2, lineHeight = 26.sp, fontSize = 16.sp),
            cursorBrush = SolidColor(dn.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                // Tapping a task box ticks it, without moving the cursor or opening the keyboard.
                .pointerInput(session) {
                    awaitEachGesture {
                        val down = awaitFirstDown(pass = PointerEventPass.Initial)
                        val l = layout ?: return@awaitEachGesture
                        val p = currentTransformation.last ?: return@awaitEachGesture
                        val box = taskAt(p, l, down.position, density) ?: return@awaitEachGesture
                        down.consume()
                        val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                        up.consume()
                        onToggleTask(box)
                    }
                }
                .drawWithContent {
                    drawContent()
                    val l = layout
                    val p = transformation.last
                    if (l != null && p != null && l.layoutInput.text == p.text.text) drawPreview(p, l, colors)
                }
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused },
        )
        // Shown while editing (also with a hardware keyboard, where the IME never appears).
        if (imeVisible || focused) {
            HorizontalDivider(color = dn.border)
            FormatBar(
                dn.chrome,
                onImage = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            ) { action -> update(action(value)) }
        }
    }
    // Short notes open ready to type; new "Untitled note (n)" notes start in the title field instead.
    LaunchedEffect(session) {
        if (session.text.length < 30 && !session.path.noteTitle.startsWith("Untitled note (")) focus.requestFocus()
    }
    LaunchedEffect(session, session.wantsBodyFocus) {
        if (session.wantsBodyFocus) {
            session.wantsBodyFocus = false
            focus.requestFocus()
        }
    }
}

@Composable
private fun FormatBar(background: Color, onImage: () -> Unit, apply: ((TextFieldValue) -> TextFieldValue) -> Unit) {
    val dn = LocalDN.current
    Row(
        Modifier.fillMaxWidth().background(background).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val items = listOf(
            Triple(Icons.Default.Title, "Heading") { v: TextFieldValue -> prefixLine(v, "# ") },
            Triple(Icons.Default.FormatBold, "Bold") { v: TextFieldValue -> wrap(v, "**") },
            Triple(Icons.Default.FormatItalic, "Italic") { v: TextFieldValue -> wrap(v, "_") },
            Triple(Icons.AutoMirrored.Filled.FormatListBulleted, "List") { v: TextFieldValue -> prefixLine(v, "- ") },
            Triple(Icons.Default.Checklist, "Task") { v: TextFieldValue -> prefixLine(v, "- [ ] ") },
            Triple(Icons.Default.Code, "Code") { v: TextFieldValue -> wrap(v, "`") },
            Triple(Icons.Default.Link, "Link") { v: TextFieldValue -> link(v) },
        )
        IconButton(onClick = onImage) {
            Icon(Icons.Outlined.Image, "Insert image", tint = dn.accent)
        }
        for ((icon, label, action) in items) {
            IconButton(onClick = { apply(action) }) {
                Icon(icon, label, tint = dn.text2)
            }
        }
    }
}

private fun wrap(v: TextFieldValue, marker: String): TextFieldValue {
    val (s, e) = v.selection.min to v.selection.max
    val text = v.text.substring(0, s) + marker + v.text.substring(s, e) + marker + v.text.substring(e)
    val cursor = if (s == e) s + marker.length else e + marker.length * 2
    return TextFieldValue(text, TextRange(cursor))
}

private fun prefixLine(v: TextFieldValue, prefix: String): TextFieldValue {
    val cursor = v.selection.start
    val lineStart = v.text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let { if (it < 0 || cursor == 0) 0 else it + 1 }
    return if (v.text.startsWith(prefix, lineStart)) {
        TextFieldValue(v.text.removeRange(lineStart, lineStart + prefix.length), TextRange((cursor - prefix.length).coerceAtLeast(lineStart)))
    } else {
        TextFieldValue(v.text.substring(0, lineStart) + prefix + v.text.substring(lineStart), TextRange(cursor + prefix.length))
    }
}

private fun link(v: TextFieldValue): TextFieldValue {
    val (s, e) = v.selection.min to v.selection.max
    val label = v.text.substring(s, e)
    val insert = "[$label](https://)"
    return TextFieldValue(v.text.substring(0, s) + insert + v.text.substring(e), TextRange(s + insert.length - 1))
}

/** Read mode: HTML rendered by the shared Rust core; image and note links are vault paths. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MarkdownPreview(model: NotesViewModel, markdown: String, notePath: String) {
    val dn = LocalDN.current
    val css = remember(dn) {
        fun hex(c: Color) = String.format("#%06X", 0xFFFFFF and c.toArgb())
        """
        body { font-family: sans-serif; font-size: 16.5px; line-height: 1.7; margin: 0; padding: 8px 20px 120px;
               color: ${hex(dn.text2)}; background: ${hex(dn.bg)}; word-wrap: break-word; }
        h1,h2,h3,h4 { color: ${hex(dn.text)}; line-height: 1.2; font-weight: 650; letter-spacing: -0.02em; margin: 1.3em 0 .45em; }
        h1 { font-size: 1.7em; margin-top: .3em; } h2 { font-size: 1.32em; } h3 { font-size: 1.12em; }
        strong { color: ${hex(dn.text)}; }
        a { color: ${hex(dn.accent)}; text-decoration: none; font-weight: 500; }
        code { font-family: monospace; font-size: .88em; background: ${hex(dn.hover)}; color: ${hex(dn.text)}; padding: .12em .38em; border-radius: 6px; }
        pre { background: ${hex(dn.chrome)}; border: 1px solid ${hex(dn.border)}; padding: 12px 14px; border-radius: 10px; overflow-x: auto; }
        pre code { background: none; padding: 0; }
        blockquote { margin: 0; padding-left: 14px; border-left: 3px solid ${hex(dn.accent)}; color: ${hex(dn.muted)}; }
        img { max-width: 100%; height: auto; border-radius: 12px; display: block; margin: 10px 0; }
        table { border-collapse: collapse; display: block; overflow-x: auto; }
        th, td { border-bottom: 1px solid ${hex(dn.border)}; padding: 7px 14px 7px 0; text-align: left; }
        th { color: ${hex(dn.text)}; }
        li:has(> input[type=checkbox]) { list-style: none; margin-left: -1.3em; }
        input[type=checkbox] { -webkit-appearance: none; appearance: none; width: 16px; height: 16px; border: 1.5px solid ${hex(dn.faint)}; border-radius: 5px; margin: 0 .55em 0 0; vertical-align: -3px; display: inline-block; position: relative; box-sizing: border-box; }
        input[type=checkbox]:checked { background: ${hex(dn.accent)}; border-color: ${hex(dn.accent)}; }
        input[type=checkbox]:checked::after { content: ""; position: absolute; left: 4px; top: 3.5px; width: 7px; height: 4px; border: solid #fff; border-width: 0 0 1.7px 1.7px; transform: rotate(-45deg); }
        li:has(> input:checked) { color: ${hex(dn.muted)}; text-decoration: line-through; }
        hr { border: 0; border-top: 1px solid ${hex(dn.border)}; }
        """.trimIndent()
    }
    val html = "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><style>$css</style></head><body>${renderMarkdown(markdown, notePath)}</body></html>"
    val base = "file://${model.root.trimEnd('/')}/"
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                // Rendered image/note URLs are vault paths, resolved against the vault folder.
                settings.allowFileAccess = true
                setBackgroundColor(dn.bg.toArgb())
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url
                        when (url.scheme) {
                            "file" -> {
                                val full = URLDecoder.decode(url.encodedPath ?: "", "UTF-8")
                                val vaultPath = full.removePrefix(model.root.trimEnd('/')).trimStart('/')
                                if (model.entry(vaultPath)?.isImage == true) model.openFrom("", Route.Image(vaultPath))
                                else if (model.entry(vaultPath) != null) model.openFrom("", Route.Note(vaultPath))
                                else model.message = "No note at $vaultPath"
                            }
                            "http", "https", "mailto", "tel" -> ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toString().toUri()))
                        }
                        return true
                    }
                }
            }
        },
        update = { it.loadDataWithBaseURL(base, html, "text/html", "utf-8", null) },
    )
}


/**
 * The note's name, edited in place. "Done" or leaving the field renames the file; an empty
 * field keeps the current name. New "Untitled note (n)" notes start empty with that name as
 * the placeholder, so typing just names the note.
 */
@Composable
private fun NoteTitleField(model: NotesViewModel, path: String) {
    val dn = LocalDN.current
    val untitled = path.noteTitle.startsWith("Untitled note (")
    var text by remember { mutableStateOf(if (untitled) "" else path.noteTitle) }
    var wasFocused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    fun commit() {
        if (text.isBlank()) text = if (untitled) "" else path.noteTitle else model.renameTitle(path, text)
    }
    fun done() {
        model.session?.wantsBodyFocus = true
        commit()
        focusManager.clearFocus()
    }
    BasicTextField(
        value = text,
        onValueChange = {
            // Return from a hardware keyboard arrives as a newline: treat it like "Done".
            if ('\n' in it) {
                text = it.replace("\n", "")
                done()
            } else text = it
        },
        singleLine = false,
        maxLines = 3,
        textStyle = MaterialTheme.typography.headlineSmall.copy(
            color = dn.text, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.5).sp,
        ),
        cursorBrush = SolidColor(dn.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { done() }),
        decorationBox = { inner ->
            Box {
                if (text.isEmpty()) {
                    Text(path.noteTitle, color = dn.faint, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.5).sp)
                }
                inner()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 4.dp)
            .focusRequester(focus)
            .onFocusChanged {
                if (wasFocused && !it.isFocused) commit()
                wasFocused = it.isFocused
            },
    )
    LaunchedEffect(Unit) {
        if (model.titleFocusPath == path) {
            model.titleFocusPath = null
            kotlinx.coroutines.delay(350)
            focus.requestFocus()
        }
    }
    DisposableEffect(Unit) { onDispose { if (text.isNotBlank()) model.renameTitle(path, text) } }
}
