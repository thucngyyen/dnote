package dev.dnote.app.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Inbox
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.format.DateUtils
import android.util.LruCache
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dnote.app.LocalRouteScope
import dev.dnote.app.LocalSharedScope
import dev.dnote.app.NotesViewModel
import dev.dnote.app.Route
import dev.dnote.app.SyncStatus
import dev.dnote.app.noteTitle
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.dnote_ffi.Entry
import uniffi.dnote_ffi.tagColors

// ---- Shared-element morph -----------------------------------------------------

/** Morph source/target for the folder → note transition. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedMorph(key: String?, corner: Dp = 12.dp): Modifier {
    val shared = LocalSharedScope.current
    val anim = LocalRouteScope.current
    if (key == null || shared == null || anim == null) return this
    return with(shared) {
        this@sharedMorph.sharedBounds(
            rememberSharedContentState(key),
            animatedVisibilityScope = anim,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(corner)),
        )
    }
}

// ---- Formatting -------------------------------------------------------------------

fun relativeTime(ms: Long): String {
    if (ms <= 0) return ""
    val now = System.currentTimeMillis()
    if (now - ms < DateUtils.MINUTE_IN_MILLIS) return "Just now"
    return DateUtils.getRelativeTimeSpanString(ms, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
}

val Entry.title: String get() = if (isFolder || isImage) name else path.noteTitle

/** Previews come from the Rust core already stripped of Markdown syntax. */
fun cleanPreview(s: String): String = s.trim()

fun notesLabel(n: Int) = if (n == 1) "1 note" else "$n notes"

// ---- Thumbnails ---------------------------------------------------------------------

private val thumbCache = LruCache<String, Bitmap>(64)

fun decodeSampled(file: File, maxPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

@Composable
fun Thumbnail(file: File, size: Dp, corner: Dp = 8.dp) {
    val dn = LocalDN.current
    val key = "${file.path}@${file.lastModified()}"
    val bitmap by produceState(thumbCache.get(key), key) {
        if (value == null) value = withContext(Dispatchers.IO) { decodeSampled(file, 256)?.also { thumbCache.put(key, it) } }
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner)).background(dn.hover),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Icon(Icons.Outlined.Image, null, tint = dn.faint, modifier = Modifier.size(size / 2))
        }
    }
}

// ---- Bits ------------------------------------------------------------------------

@Composable
fun TagDots(model: NotesViewModel, path: String) {
    val ids = model.tagMap[path].orEmpty()
    if (ids.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        for (id in ids.take(4)) {
            val t = model.tag(id) ?: continue
            if (id == "favorite") {
                Icon(Icons.Filled.Star, t.name, tint = tagColor(t.color), modifier = Modifier.size(12.dp))
            } else {
                Box(Modifier.size(7.dp).clip(CircleShape).background(tagColor(t.color)))
            }
        }
    }
}

@Composable
fun SyncLine(model: NotesViewModel) {
    val dn = LocalDN.current
    val (color, label) = when (val s = model.status) {
        SyncStatus.LocalOnly -> dn.faint to "On this device"
        SyncStatus.Syncing -> Color(0xFFF59E0B) to "Syncing…"
        is SyncStatus.Idle -> dn.success to "Synced ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.at))}"
        is SyncStatus.Failed -> MaterialTheme.colorScheme.error to "Sync failed"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.5.sp, color = dn.muted)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        color = LocalDN.current.faint,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
    )
}

/** Rounded surface holding a list of rows separated by hairlines. */
@Composable
fun ListGroup(modifier: Modifier = Modifier, inset: Dp = 16.dp, content: @Composable () -> Unit) {
    val dn = LocalDN.current
    Surface(
        color = dn.surface,
        shape = RoundedCornerShape(12.dp),
        border = if (dn.dark) BorderStroke(1.dp, dn.border) else null,
        shadowElevation = if (dn.dark) 0.dp else 0.5.dp,
        modifier = modifier.padding(horizontal = inset).fillMaxWidth(),
    ) { Column { content() } }
}

@Composable
fun RowDivider(start: Dp = 64.dp) {
    HorizontalDivider(Modifier.padding(start = start), color = LocalDN.current.border, thickness = 0.8.dp)
}

/** Icon in a soft rounded square. */
@Composable
fun IconTile(icon: ImageVector, tint: Color, bg: Color, size: Dp = 36.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(9.dp)).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

// ---- Entry rows -----------------------------------------------------------------

sealed interface Prompt {
    data class Rename(val entry: Entry) : Prompt
    data class Delete(val entry: Entry) : Prompt
    data class Tags(val path: String) : Prompt
    data class NewNote(val folder: String) : Prompt
    data class NewFolder(val parent: String) : Prompt
    data class Move(val entry: Entry) : Prompt
}

/**
 * A note / folder / image row. Tap opens it (notes and images morph out of the row),
 * long-press shows Pin, Tags…, Rename, Delete.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EntryRow(model: NotesViewModel, entry: Entry, source: String, onPrompt: (Prompt) -> Unit) {
    val dn = LocalDN.current
    var menu by remember { mutableStateOf(false) }
    val key = "$source:${entry.path}"
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .sharedMorph(if (entry.isFolder) null else key, 0.dp)
                .combinedClickable(
                    onClick = {
                        when {
                            entry.isFolder -> model.push(Route.Folder(entry.path))
                            entry.isImage -> model.openFrom(key, Route.Image(entry.path))
                            else -> model.openFrom(key, Route.Note(entry.path))
                        }
                    },
                    onLongClick = { menu = true },
                    onLongClickLabel = "Actions",
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                entry.isFolder -> IconTile(Icons.Outlined.Folder, dn.accent, dn.accentSoft)
                entry.isImage -> Thumbnail(model.file(entry.path), 36.dp)
                else -> IconTile(Icons.AutoMirrored.Outlined.Article, dn.muted, dn.hover)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.title,
                        fontSize = 16.sp,
                        fontWeight = if (entry.isFolder) FontWeight.Medium else FontWeight.SemiBold,
                        color = dn.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (model.isPinned(entry.path)) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.PushPin, "Pinned", tint = dn.accent, modifier = Modifier.size(13.dp))
                    }
                }
                val sub = when {
                    entry.isFolder -> notesLabel(model.noteCount(entry.path))
                    entry.isImage -> relativeTime(entry.modifiedMs)
                    else -> cleanPreview(model.previews[entry.path].orEmpty()).ifEmpty { "No additional text" }
                }
                Text(sub, fontSize = 13.5.sp, color = dn.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!entry.isFolder && !entry.isImage) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                        Text(relativeTime(entry.modifiedMs), fontSize = 12.sp, color = dn.faint)
                        if (model.tagMap[entry.path].orEmpty().isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            TagDots(model, entry.path)
                        }
                    }
                }
            }
            if (entry.isFolder) {
                TagDots(model, entry.path)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = dn.faint)
            }
        }
        EntryMenu(model, entry, menu, onDismiss = { menu = false }, onPrompt = onPrompt)
    }
}

@Composable
fun EntryMenu(model: NotesViewModel, entry: Entry, expanded: Boolean, onDismiss: () -> Unit, onPrompt: (Prompt) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (entry.isFolder) {
            DropdownMenuItem(text = { Text("New note here") }, onClick = { onDismiss(); onPrompt(Prompt.NewNote(entry.path)) })
            DropdownMenuItem(text = { Text("New subfolder") }, onClick = { onDismiss(); onPrompt(Prompt.NewFolder(entry.path)) })
            HorizontalDivider()
        }
        DropdownMenuItem(
            text = { Text(if (model.isPinned(entry.path)) "Unpin" else "Pin") },
            onClick = { onDismiss(); model.togglePin(entry.path) },
        )
        if (!entry.isImage) {
            DropdownMenuItem(text = { Text("Tags…") }, onClick = { onDismiss(); onPrompt(Prompt.Tags(entry.path)) })
        }
        DropdownMenuItem(text = { Text("Rename") }, onClick = { onDismiss(); onPrompt(Prompt.Rename(entry)) })
        DropdownMenuItem(text = { Text("Move to…") }, onClick = { onDismiss(); onPrompt(Prompt.Move(entry)) })
        DropdownMenuItem(
            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
            onClick = { onDismiss(); onPrompt(Prompt.Delete(entry)) },
        )
    }
}

/** Handles the dialogs / sheets a row or screen can ask for. */
@Composable
fun PromptHost(model: NotesViewModel, prompt: Prompt?, onDone: () -> Unit) {
    when (prompt) {
        null -> {}
        is Prompt.Rename -> NameDialog(
            when {
                prompt.entry.isFolder -> "Rename folder"
                prompt.entry.isImage -> "Rename image"
                else -> "Rename note"
            },
            prompt.entry.title,
            "",
            onDismiss = onDone,
        ) { onDone(); model.rename(prompt.entry, it) }
        is Prompt.Delete -> AlertDialog(
            onDismissRequest = onDone,
            title = { Text("Delete “${prompt.entry.title}”?") },
            text = {
                Text(
                    if (prompt.entry.isFolder) "The folder and its ${notesLabel(model.noteCount(prompt.entry.path))} will be deleted on all synced devices."
                    else "It will be deleted on all synced devices.",
                )
            },
            confirmButton = {
                TextButton(onClick = { onDone(); model.delete(prompt.entry.path) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
        )
        is Prompt.Tags -> TagSheet(model, prompt.path, onDone)
        is Prompt.Move -> MoveSheet(model, prompt.entry, onDone)
        // No name prompt: create "Untitled note (n)" right away; it's named from the title field.
        is Prompt.NewNote -> androidx.compose.runtime.LaunchedEffect(prompt) {
            onDone()
            model.createNote(prompt.folder, "", (model.scope as? dev.dnote.app.Scope.Tag)?.id?.takeIf { prompt.folder.isEmpty() })
        }
        is Prompt.NewFolder -> NameDialog("New folder", "", "Folder name", onDismiss = onDone) {
            onDone()
            if (it.isNotBlank()) model.createFolder(prompt.parent, it)
        }
    }
}

// ---- Move -------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveSheet(model: NotesViewModel, entry: Entry, onDismiss: () -> Unit) {
    val dn = LocalDN.current
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val targets = model.allFolders.filter { it.path != entry.path && !it.path.startsWith("${entry.path}/") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = dn.surface,
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                "Move “${entry.title}”",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = dn.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                MoveRow("Top level", 0, entry.parent.isEmpty(), Icons.Outlined.Inbox) { onDismiss(); model.move(entry, "") }
                for (f in targets) {
                    MoveRow(f.name, f.depth.toInt() + 1, f.path == entry.parent, Icons.Outlined.Folder) { onDismiss(); model.move(entry, f.path) }
                }
            }
            HorizontalDivider(color = dn.border, modifier = Modifier.padding(vertical = 6.dp))
            if (creating) {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("Folder name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(enabled = name.isNotBlank(), onClick = {
                        onDismiss()
                        model.createFolder("", name)
                        model.move(entry, name.trim())
                    }) { Text("Create") }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().clickable { creating = true }.padding(horizontal = 24.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.CreateNewFolder, null, tint = dn.accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(14.dp))
                    Text("New folder…", color = dn.accent, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun MoveRow(label: String, depth: Int, current: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val dn = LocalDN.current
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = !current, onClick = onClick)
            .padding(start = 24.dp + (maxOf(0, depth - 1) * 18).dp, end = 24.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (current) dn.faint else dn.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = if (current) dn.faint else dn.text, modifier = Modifier.weight(1f))
        if (current) Text("Current", color = dn.faint, fontSize = 13.sp)
    }
}

// ---- Tags -------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TagSheet(model: NotesViewModel, path: String, onDismiss: () -> Unit) {
    val dn = LocalDN.current
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val colors = remember { tagColors() }
    // Default to a colour no tag uses yet.
    var color by remember { mutableStateOf(colors.firstOrNull { c -> model.tags.none { it.color == c } } ?: colors.getOrElse(1) { "blue" }) }
    val applied = model.tagMap[path].orEmpty()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = dn.surface,
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                "Tags",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = dn.text,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Text(
                path.noteTitle,
                fontSize = 13.sp,
                color = dn.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(10.dp))
            for (t in model.tags) {
                val on = t.id in applied
                Row(
                    Modifier.fillMaxWidth().clickable { model.setTag(path, t.id, !on) }.padding(horizontal = 24.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (t.id == "favorite") {
                        Icon(Icons.Filled.Star, null, tint = tagColor(t.color), modifier = Modifier.size(18.dp))
                    } else {
                        Box(Modifier.padding(4.dp).size(10.dp).clip(CircleShape).background(tagColor(t.color)))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(t.name, fontSize = 16.sp, color = dn.text, modifier = Modifier.weight(1f))
                    if (on) Icon(Icons.Filled.Check, "Applied", tint = dn.accent)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = dn.border)
            if (!creating) {
                Row(
                    Modifier.fillMaxWidth().clickable { creating = true }.padding(horizontal = 24.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, null, tint = dn.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(14.dp))
                    Text("New tag…", fontSize = 16.sp, color = dn.accent, fontWeight = FontWeight.Medium)
                }
            } else {
                val focus = remember { FocusRequester() }
                Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("Tag name") },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    ColorSwatches(colors, color) { color = it }
                    Button(
                        enabled = name.isNotBlank(),
                        onClick = {
                            model.createTag(name, color)?.let { model.setTag(path, it.id, true) }
                            name = ""
                            creating = false
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Create and apply") }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ColorSwatches(colors: List<String>, selected: String, onPick: (String) -> Unit) {
    val dn = LocalDN.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (c in colors) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (c == selected) dn.text else Color.Transparent, CircleShape)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(tagColor(c))
                    .semantics { contentDescription = "$c colour"; this.selected = c == selected }
                    .clickable { onPick(c) },
            )
        }
    }
}

// ---- Dialogs ----------------------------------------------------------------

@Composable
fun NameDialog(title: String, initial: String, placeholder: String, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LocalDN.current.surface,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = { Text(placeholder) },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = { Button(onClick = { onSubmit(value.trim()) }, shape = RoundedCornerShape(8.dp)) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PrimaryButton(text: String, icon: ImageVector? = Icons.Filled.Add, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = LocalDN.current.accent, contentColor = Color.White),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}
