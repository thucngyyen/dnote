package dev.dnote.app.ui

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Add
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dnote.app.NotesViewModel
import dev.dnote.app.R
import dev.dnote.app.Route
import dev.dnote.app.Scope
import dev.dnote.app.noteTitle

private data class Chip(val scope: Scope, val label: String, val color: Color?, val star: Boolean = false)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(model: NotesViewModel) {
    val dn = LocalDN.current
    var query by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(dn.chrome)) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; model.sync { refreshing = false } },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 110.dp)) {
                Header(model) { prompt = it }
                SearchField(query) { query = it }
                if (query.isNotBlank()) {
                    SearchResults(model, query)
                } else {
                    if (model.conflicts.isNotEmpty()) ConflictCard(model)
                    PinnedSection(model) { prompt = it }
                    ScopeChips(model)
                    (model.scope as? Scope.Folder)?.let { sc -> model.entry(sc.path)?.let { FolderHeader(model, it) { p -> prompt = p } } }
                    Spacer(Modifier.height(12.dp))
                    ScopeList(model) { prompt = it }
                }
            }
        }
        // Primary action, pinned to the bottom.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(dn.chrome.copy(alpha = 0.94f))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            val folder = (model.scope as? Scope.Folder)?.path ?: ""
            PrimaryButton("New note") { prompt = Prompt.NewNote(folder) }
        }
    }
    PromptHost(model, prompt) { prompt = null }
}

@Composable
private fun Header(model: NotesViewModel, onPrompt: (Prompt) -> Unit) {
    val dn = LocalDN.current
    var addMenu by remember { mutableStateOf(false) }
    val folder = (model.scope as? Scope.Folder)?.path ?: ""
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(
            painterResource(if (dn.dark) R.drawable.ic_mark_reversed else R.drawable.ic_mark),
            contentDescription = null,
            modifier = Modifier.height(30.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(wordmark(), fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp)
            SyncLine(model)
        }
        Box {
            IconButton(onClick = { addMenu = true }) {
                Icon(Icons.Default.Add, "Add", tint = dn.muted)
            }
            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                DropdownMenuItem(
                    text = { Text("New note") },
                    leadingIcon = { Icon(Icons.Outlined.EditNote, null) },
                    onClick = { addMenu = false; onPrompt(Prompt.NewNote(folder)) },
                )
                DropdownMenuItem(
                    text = { Text(if (folder.isEmpty()) "New folder" else "New folder in ${folder.substringAfterLast('/')}") },
                    leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                    onClick = { addMenu = false; onPrompt(Prompt.NewFolder(folder)) },
                )
            }
        }
        IconButton(onClick = { model.push(Route.Settings) }) {
            Icon(Icons.Outlined.Settings, "Settings", tint = dn.muted)
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val dn = LocalDN.current
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth().height(44.dp)
            .clip(RoundedCornerShape(12.dp)).background(dn.surface)
            .border(1.dp, dn.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = dn.faint, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search notes", color = dn.faint, fontSize = 15.sp)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = dn.text, fontSize = 15.sp),
                cursorBrush = SolidColor(dn.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(Icons.Filled.Close, "Clear", tint = dn.faint, modifier = Modifier.size(18.dp).clickable { onChange("") })
        }
    }
}

@Composable
private fun SearchResults(model: NotesViewModel, query: String) {
    val dn = LocalDN.current
    val hits = model.search(query)
    if (hits.isEmpty()) {
        Text("Nothing matches “$query”.", color = dn.muted, modifier = Modifier.padding(24.dp))
        return
    }
    ListGroup {
        hits.forEachIndexed { i, hit ->
            if (i > 0) RowDivider(16.dp)
            val key = "search:${hit.path}"
            Column(
                Modifier.fillMaxWidth().sharedMorph(key, 0.dp)
                    .clickable { model.openFrom(key, Route.Note(hit.path)) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(hit.path.noteTitle, fontWeight = FontWeight.SemiBold, color = dn.text, fontSize = 16.sp)
                Text(
                    hit.snippet.ifEmpty { hit.path },
                    color = dn.muted, fontSize = 13.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---- Pinned -----------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinnedSection(model: NotesViewModel, onPrompt: (Prompt) -> Unit) {
    val dn = LocalDN.current
    val pinned = model.pins.mapNotNull { model.entry(it) }
    if (pinned.isEmpty()) return
    SectionLabel("Pinned")
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(pinned, key = { it.path }) { entry ->
            var menu by remember { mutableStateOf(false) }
            val key = "pin:${entry.path}"
            Box {
                Surface(
                    color = dn.surface,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, dn.border),
                    modifier = Modifier.width(158.dp).height(104.dp)
                        .sharedMorph(if (entry.isFolder) null else key)
                        .clip(RoundedCornerShape(12.dp))
                        .combinedClickable(
                            onClick = {
                                when {
                                    entry.isFolder -> model.push(Route.Folder(entry.path))
                                    entry.isImage -> model.openFrom(key, Route.Image(entry.path))
                                    else -> model.openFrom(key, Route.Note(entry.path))
                                }
                            },
                            onLongClick = { menu = true },
                        ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (entry.isFolder) {
                                IconTile(Icons.Outlined.Folder, dn.accent, dn.accentSoft, 28.dp)
                            } else if (entry.isImage) {
                                Thumbnail(model.file(entry.path), 28.dp, 7.dp)
                            } else {
                                IconTile(Icons.AutoMirrored.Outlined.Article, dn.muted, dn.hover, 28.dp)
                            }
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.Filled.PushPin, null, tint = dn.accent, modifier = Modifier.size(13.dp))
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(entry.title, fontWeight = FontWeight.SemiBold, color = dn.text, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (entry.isFolder) notesLabel(model.noteCount(entry.path)) else cleanPreview(model.previews[entry.path].orEmpty()).ifEmpty { relativeTime(entry.modifiedMs) },
                            color = dn.muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                EntryMenu(model, entry, menu, onDismiss = { menu = false }, onPrompt = onPrompt)
            }
        }
    }
}

// ---- Scope chips with a sliding pill ---------------------------------------------

@Composable
private fun chips(model: NotesViewModel): List<Chip> {
    val list = mutableListOf(Chip(Scope.All, "All notes", null))
    model.entries.filter { it.isFolder && it.parent.isEmpty() }.forEach { list += Chip(Scope.Folder(it.path), it.name, null) }
    model.tags.forEach { t ->
        list += Chip(Scope.Tag(t.id), if (t.id == "favorite") "Favorites" else t.name, tagColor(t.color), star = t.id == "favorite")
    }
    return list
}

@Composable
private fun ScopeChips(model: NotesViewModel) {
    val dn = LocalDN.current
    val density = LocalDensity.current
    val list = chips(model)
    val selected = list.indexOfFirst { it.scope == model.scope }.coerceAtLeast(0)
    val bounds = remember { mutableStateMapOf<Int, Pair<Dp, Dp>>() }
    val scroll = rememberScrollState()
    val target = bounds[selected]
    val pillX by animateDpAsState(target?.first ?: 0.dp, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow), label = "pillX")
    val pillW by animateDpAsState(target?.second ?: 0.dp, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow), label = "pillW")

    // Keep the selected chip in view.
    LaunchedEffect(selected, target) {
        val t = target ?: return@LaunchedEffect
        with(density) {
            val left = t.first.roundToPx()
            val right = (t.first + t.second).roundToPx()
            val viewport = scroll.viewportSize
            if (left < scroll.value + 40) scroll.animateScrollTo((left - 40).coerceAtLeast(0))
            else if (right > scroll.value + viewport - 40) scroll.animateScrollTo(right - viewport + 40)
        }
    }

    SectionLabel("Browse")
    Box(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 16.dp)) {
        // Layer 1: chip outlines. Layer 2 (above): the pill. Layer 3: labels, which also take the taps.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEachIndexed { i, chip ->
                Box(
                    Modifier.clip(CircleShape).background(dn.surface).border(1.dp, dn.border, CircleShape),
                ) { ChipContent(chip, false, dn.text2, Modifier.alpha(0f)) }
            }
        }
        if (target != null) {
            Box(Modifier.offset(x = pillX).width(pillW).height(36.dp).clip(CircleShape).background(dn.accent))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEachIndexed { i, chip ->
                val on = i == selected
                val textColor by animateColorAsState(if (on) Color.White else dn.text2, tween(220), label = "chipText")
                ChipContent(
                    chip, on, textColor,
                    Modifier
                        .onPlaced { c -> with(density) { bounds[i] = c.positionInParent().x.toDp() to c.size.width.toDp() } }
                        .clip(CircleShape)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { model.scope = chip.scope },
                )
            }
        }
    }
}

@Composable
private fun ChipContent(chip: Chip, on: Boolean, textColor: Color, modifier: Modifier) {
    Row(modifier.height(36.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            chip.star -> Icon(Icons.Filled.Star, null, tint = if (on) Color.White else chip.color!!, modifier = Modifier.size(14.dp))
            chip.color != null -> Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) Color.White else chip.color))
            chip.scope is Scope.Folder -> Icon(Icons.Outlined.Folder, null, tint = textColor, modifier = Modifier.size(15.dp))
        }
        if (chip.scope !is Scope.All) Spacer(Modifier.width(6.dp))
        Text(chip.label, color = textColor, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

// ---- Scope list -----------------------------------------------------------------

private fun scopeIndex(model: NotesViewModel, scope: Scope): Int {
    val folders = model.entries.filter { it.isFolder && it.parent.isEmpty() }.map { it.path }
    return when (scope) {
        Scope.All -> 0
        is Scope.Folder -> 1 + folders.indexOf(scope.path)
        is Scope.Tag -> 1 + folders.size + model.tags.indexOfFirst { it.id == scope.id }
    }
}

/** Shown under the chips when a folder is selected: its name, size and every folder action. */
@Composable
private fun FolderHeader(model: NotesViewModel, folder: uniffi.dnote_ffi.Entry, onPrompt: (Prompt) -> Unit) {
    val dn = LocalDN.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Folder, null, tint = dn.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(folder.name, color = dn.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(notesLabel(model.noteCount(folder.path)), color = dn.muted, fontSize = 12.sp)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreHoriz, "Folder actions", tint = dn.text2) }
            EntryMenu(model, folder, menu, onDismiss = { menu = false }, onPrompt = onPrompt)
        }
    }
}

@Composable
private fun ScopeList(model: NotesViewModel, onPrompt: (Prompt) -> Unit) {
    val dn = LocalDN.current
    AnimatedContent(
        targetState = model.scope,
        transitionSpec = {
            // Slide in from the side of the newly selected chip.
            val forward = scopeIndex(model, targetState) >= scopeIndex(model, initialState)
            val dir = if (forward) 1 else -1
            (slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { it / 3 * dir } + fadeIn(tween(220))) togetherWith
                (slideOutHorizontally(tween(200)) { -it / 3 * dir } + fadeOut(tween(160)))
        },
        label = "scope",
    ) { scope ->
        val items = model.scopeItems(scope)
        Column {
            if (items.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.AutoMirrored.Outlined.Article, null, tint = dn.faint, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        when (scope) {
                            is Scope.Tag -> "Nothing tagged yet"
                            is Scope.Folder -> "This folder is empty"
                            Scope.All -> "No notes yet"
                        },
                        color = dn.text, fontWeight = FontWeight.SemiBold,
                    )
                    Text("Long-press a note to pin or tag it.", color = dn.muted, fontSize = 13.5.sp)
                }
            } else {
                ListGroup {
                    items.forEachIndexed { i, entry ->
                        if (i > 0) RowDivider()
                        EntryRow(model, entry, "list", onPrompt)
                    }
                }
                if (scope is Scope.Folder) {
                    TextButton(onClick = { onPrompt(Prompt.NewFolder(scope.path)) }, modifier = Modifier.padding(start = 12.dp, top = 4.dp)) {
                        Icon(Icons.Outlined.CreateNewFolder, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("New folder here")
                    }
                } else if (scope == Scope.All) {
                    TextButton(onClick = { onPrompt(Prompt.NewFolder("")) }, modifier = Modifier.padding(start = 12.dp, top = 4.dp)) {
                        Icon(Icons.Outlined.CreateNewFolder, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("New folder")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConflictCard(model: NotesViewModel) {
    val dn = LocalDN.current
    Surface(
        color = dn.surface,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, dn.border),
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFF59E0B)))
                Spacer(Modifier.width(8.dp))
                Text("Edits clashed during sync", fontWeight = FontWeight.SemiBold, color = dn.text)
            }
            Text(
                "Both versions were kept: ${model.conflicts.joinToString { it.noteTitle }}.",
                fontSize = 13.sp, color = dn.muted, modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = { model.conflicts = emptyList() }) { Text("Dismiss") }
        }
    }
}
