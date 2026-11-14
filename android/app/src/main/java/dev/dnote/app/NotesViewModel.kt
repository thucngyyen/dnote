package dev.dnote.app

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import java.io.ByteArrayOutputStream
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.dnote_ffi.Entry
import uniffi.dnote_ffi.SearchHit
import uniffi.dnote_ffi.SyncSummary
import uniffi.dnote_ffi.TagInfo
import uniffi.dnote_ffi.Vault
import uniffi.dnote_ffi.checkServer
import uniffi.dnote_ffi.merge3
import uniffi.dnote_ffi.welcomeNote

sealed interface Route {
    data class Folder(val path: String) : Route
    data class Note(val path: String) : Route
    data class Image(val path: String) : Route
    data object Settings : Route
}

/** What the home list shows; selected with the scope chips. */
sealed interface Scope {
    data object All : Scope
    data class Folder(val path: String) : Scope
    data class Tag(val id: String) : Scope
}

sealed interface SyncStatus {
    data object LocalOnly : SyncStatus
    data object Syncing : SyncStatus
    data class Idle(val at: Long) : SyncStatus
    data class Failed(val message: String) : SyncStatus
}

/** The note open in the editor. [saved] is what is on disk; [text] may be ahead of it. */
class EditorSession(val path: String, text: String) {
    var text by mutableStateOf(text)
    var saved: String = text
    /** Bumped when the text is replaced from outside the editor (sync merge). */
    var externalRevision by mutableIntStateOf(0)
    /** Put the cursor in the body (after "Done" in the title field). */
    var wantsBodyFocus by mutableStateOf(false)
}

val String.noteTitle: String
    get() {
        val name = substringAfterLast('/')
        for (ext in listOf(".md", ".markdown", ".txt")) {
            if (name.lowercase().endsWith(ext)) return name.dropLast(ext.length)
        }
        return name
    }

val String.parentPath: String get() = if ('/' in this) substringBeforeLast('/') else ""

fun Throwable.userMessage(): String = message ?: toString()

class NotesViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("dnote", Context.MODE_PRIVATE)
    val vault: Vault = Vault.open(File(app.filesDir, "Notes").path)

    var entries by mutableStateOf<List<Entry>>(emptyList())
        private set
    val stack = mutableStateListOf<Route>(Route.Folder(""))
    var session by mutableStateOf<EditorSession?>(null)
        private set
    var status by mutableStateOf<SyncStatus>(SyncStatus.LocalOnly)
        private set
    var conflicts by mutableStateOf<List<String>>(emptyList())
    var scope by mutableStateOf<Scope>(Scope.All)
    var pins by mutableStateOf<List<String>>(emptyList())
        private set
    var tags by mutableStateOf<List<TagInfo>>(emptyList())
        private set
    /** path -> tag ids */
    var tagMap by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set
    /** path -> one-line preview, filled in the background. */
    val previews = mutableStateMapOf<String, String>()
    val root: String get() = vault.root()
    /** Shared-element key of the row a note/image was opened from (for the morph transition). */
    var transitionKey by mutableStateOf<String?>(null)

    fun openFrom(sourceKey: String, route: Route) {
        transitionKey = sourceKey
        push(route)
    }
    var message by mutableStateOf<String?>(null)

    /** "system", "light" or "dark". */
    var theme by mutableStateOf(prefs.getString("theme", "system") ?: "system")
        private set

    fun updateTheme(value: String) {
        theme = value
        prefs.edit().putString("theme", value).apply()
    }

    var server by mutableStateOf(prefs.getString("server", "") ?: "")
        private set
    var token by mutableStateOf(prefs.getString("token", "") ?: "")
        private set
    val isConnected get() = server.isNotEmpty() && token.isNotEmpty()

    private var syncing = false
    private var syncAgain = false
    private var saveJob: Job? = null
    private var syncJob: Job? = null

    init {
        seedIfEmpty()
        reload()
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                sync()
            }
        }
    }

    // ---- Tree ----------------------------------------------------------------

    fun reload() {
        runCatching { entries = vault.entries() }.onFailure { message = it.userMessage() }
        refreshMeta()
        val notes = entries.filter { !it.isFolder && !it.isImage }.map { it.path }
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { notes.associateWith { runCatching { vault.preview(it) }.getOrDefault("") } }
            previews.keys.retainAll(loaded.keys)
            previews.putAll(loaded)
        }
    }

    private fun refreshMeta() {
        pins = vault.pins()
        tags = vault.tags()
        val map = HashMap<String, MutableList<String>>()
        for (t in tags) for (p in vault.pathsWithTag(t.id)) map.getOrPut(p) { mutableListOf() }.add(t.id)
        tagMap = map
        val sc = scope
        if (sc is Scope.Folder && entries.none { it.isFolder && it.path == sc.path }) scope = Scope.All
        if (sc is Scope.Tag && tags.none { it.id == sc.id }) scope = Scope.All
    }

    fun entry(path: String): Entry? = entries.firstOrNull { it.path == path }

    fun file(path: String) = File(root, path)

    fun tag(id: String): TagInfo? = tags.firstOrNull { it.id == id }

    /** Items listed for a scope: folders first, then notes, then images. */
    fun scopeItems(scope: Scope): List<Entry> = when (scope) {
        Scope.All -> entries.filter { !it.isFolder && !it.isImage }.sortedByDescending { it.modifiedMs }
        is Scope.Folder -> children(scope.path).sortedBy { if (it.isFolder) 0 else if (it.isImage) 2 else 1 }
        is Scope.Tag -> {
            val paths = vault.pathsWithTag(scope.id).toSet()
            entries.filter { it.path in paths }.sortedBy { if (it.isFolder) 0 else if (it.isImage) 2 else 1 }
        }
    }

    // ---- Pins & tags -------------------------------------------------------------

    fun isPinned(path: String) = path in pins

    fun togglePin(path: String) {
        runCatching { vault.togglePin(path) }.onFailure { message = it.userMessage() }
        refreshMeta()
        scheduleSync(1_500)
    }

    fun setTag(path: String, tagId: String, on: Boolean) {
        runCatching { vault.setTag(path, tagId, on) }.onFailure { message = it.userMessage() }
        refreshMeta()
        scheduleSync(1_500)
    }

    fun createTag(name: String, color: String): TagInfo? =
        runCatching { vault.createTag(name, color) }
            .onSuccess { refreshMeta(); scheduleSync(1_500) }
            .onFailure { message = it.userMessage() }
            .getOrNull()

    fun updateTag(id: String, name: String, color: String) {
        runCatching { vault.updateTag(id, name, color) }.onFailure { message = it.userMessage() }
        refreshMeta()
        scheduleSync(1_500)
    }

    fun deleteTag(id: String) {
        runCatching { vault.deleteTag(id) }.onFailure { message = it.userMessage() }
        refreshMeta()
        scheduleSync(1_500)
    }

    // ---- Images ------------------------------------------------------------------

    /**
     * Import a picked photo: downscale to 2560 px, JPEG 85 %, save to Attachments/.
     * Returns the Markdown to insert into [notePath].
     */
    suspend fun addImage(notePath: String, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = getApplication<Application>().contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2560) sample *= 2
            val bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: error("Couldn't read that image")
            val scale = minOf(1f, 2560f / maxOf(bitmap.width, bitmap.height))
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            } else bitmap
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            val name = "photo-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())}.jpg"
            vault.addImage(notePath, name, out.toByteArray())
        }.onFailure { withContext(Dispatchers.Main) { message = it.userMessage() } }
            .getOrNull()
            ?.also { withContext(Dispatchers.Main) { reload(); scheduleSync(1_000) } }
    }

    fun children(folder: String) = entries.filter { it.parent == folder }

    fun noteCount(folder: String) =
        entries.count { !it.isFolder && (it.parent == folder || it.parent.startsWith("$folder/")) }

    fun search(query: String): List<SearchHit> = runCatching { vault.search(query) }.getOrDefault(emptyList())

    private fun seedIfEmpty() {
        if (prefs.getBoolean("seeded", false) || vault.entries().isNotEmpty()) return
        prefs.edit().putBoolean("seeded", true).apply()
        vault.write("Welcome.md", welcomeNote())
    }

    // ---- Navigation ------------------------------------------------------------

    fun push(route: Route) {
        if (route is Route.Note) open(route.path)
        stack.add(route)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        val top = stack.removeAt(stack.lastIndex)
        if (top is Route.Note) closeEditor(top.path)
        return true
    }

    // ---- Notes -----------------------------------------------------------------

    private fun open(path: String) {
        flushSave()
        if (session?.path == path) return
        session = runCatching { EditorSession(path, vault.read(path)) }
            .onFailure { message = it.userMessage() }
            .getOrNull()
    }

    private fun closeEditor(path: String) {
        if (session?.path != path) return
        flushSave()
        session = null
        // Refresh this note's list preview right away (the list shows it as soon as we pop).
        previews[path] = runCatching { vault.preview(path) }.getOrDefault("")
    }

    /** Called by the editor on every keystroke. */
    fun edited() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(600)
            flushSave()
        }
        scheduleSync(2_500)
    }

    fun flushSave() {
        saveJob?.cancel()
        // While a sync runs, edits stay in memory; they are merged in when it finishes.
        val s = session ?: return
        if (syncing || s.text == s.saved) return
        runCatching {
            vault.write(s.path, s.text)
            s.saved = s.text
        }.onFailure { message = it.userMessage() }
    }

    /** A note just created by "New note": its title field starts focused. */
    var titleFocusPath by mutableStateOf<String?>(null)

    /** Rename the open note from its title field (same folder). Empty or unchanged titles are ignored. */
    fun renameTitle(path: String, title: String) {
        val t = title.trim()
        if (t.isEmpty() || t == path.noteTitle || !vault.exists(path)) return
        flushSave()
        runCatching { vault.renameNoteTitle(path, t) }
            .onSuccess { newPath ->
                if (newPath == path) return@onSuccess
                session?.let { s ->
                    if (s.path == path) session = EditorSession(newPath, s.text).also { it.wantsBodyFocus = s.wantsBodyFocus }
                }
                for (i in stack.indices) if (stack[i] == Route.Note(path)) stack[i] = Route.Note(newPath)
                if (titleFocusPath == path) titleFocusPath = null
                reload()
                scheduleSync(1_000)
            }
            .onFailure { message = it.userMessage() }
    }

    /** Create a note; with an empty [name] it is "Untitled note (n)" and opens with the title field focused. */
    fun createNote(folder: String, name: String, tagId: String? = null) {
        runCatching { vault.createNote(folder, name) }
            .onSuccess {
                if (name.isBlank()) titleFocusPath = it
                if (tagId != null) runCatching { vault.setTag(it, tagId, true) }
                reload()
                push(Route.Note(it))
                scheduleSync(1_000)
            }
            .onFailure { message = it.userMessage() }
    }

    fun createFolder(parent: String, name: String) {
        val path = if (parent.isEmpty()) name.trim() else "$parent/${name.trim()}"
        runCatching { vault.createFolder(path) }
            .onSuccess { reload() }
            .onFailure { message = it.userMessage() }
    }

    fun rename(entry: Entry, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) return
        flushSave()
        val target = if (entry.parent.isEmpty()) name else "${entry.parent}/$name"
        runCatching { vault.rename(entry.path, target) }
            .onSuccess { newPath ->
                fun move(p: String) =
                    if (p == entry.path || p.startsWith("${entry.path}/")) newPath + p.drop(entry.path.length) else p
                session?.let { s -> if (move(s.path) != s.path) session = EditorSession(move(s.path), s.text) }
                for (i in stack.indices) {
                    stack[i] = when (val r = stack[i]) {
                        is Route.Folder -> Route.Folder(move(r.path))
                        is Route.Note -> Route.Note(move(r.path))
                        is Route.Image -> Route.Image(move(r.path))
                        else -> r
                    }
                }
                (scope as? Scope.Folder)?.let { scope = Scope.Folder(move(it.path)) }
                reload()
                scheduleSync(1_000)
            }
            .onFailure { message = it.userMessage() }
    }

    /** All folders, depth-first, for the "Move to…" picker. */
    val allFolders: List<Entry> get() = entries.filter { it.isFolder }

    /** Move a note, image or folder into [folder] ("" = top level), keeping its name. */
    fun move(entry: Entry, folder: String) {
        if (folder == entry.parent || folder == entry.path || folder.startsWith("${entry.path}/")) return
        flushSave()
        val target = if (folder.isEmpty()) entry.name else "$folder/${entry.name}"
        runCatching { vault.rename(entry.path, target) }
            .onSuccess { newPath ->
                fun moved(p: String) =
                    if (p == entry.path || p.startsWith("${entry.path}/")) newPath + p.drop(entry.path.length) else p
                session?.let { s -> if (moved(s.path) != s.path) session = EditorSession(moved(s.path), s.text) }
                for (i in stack.indices) {
                    stack[i] = when (val r = stack[i]) {
                        is Route.Folder -> Route.Folder(moved(r.path))
                        is Route.Note -> Route.Note(moved(r.path))
                        is Route.Image -> Route.Image(moved(r.path))
                        else -> r
                    }
                }
                (scope as? Scope.Folder)?.let { scope = Scope.Folder(moved(it.path)) }
                reload()
                scheduleSync(1_000)
            }
            .onFailure { message = it.userMessage() }
    }

    fun delete(path: String) {
        fun inside(p: String) = p == path || p.startsWith("$path/")
        session?.let { if (inside(it.path)) session = null }
        stack.removeAll {
            (it is Route.Folder && it.path.isNotEmpty() && inside(it.path)) ||
                (it is Route.Note && inside(it.path)) || (it is Route.Image && inside(it.path))
        }
        (scope as? Scope.Folder)?.let { if (inside(it.path)) scope = Scope.All }
        runCatching { vault.delete(path) }
            .onSuccess {
                reload()
                scheduleSync(1_000)
            }
            .onFailure { message = it.userMessage() }
    }

    // ---- Sync ------------------------------------------------------------------

    fun appBecameActive() {
        reload()
        sync()
    }

    private fun scheduleSync(delayMs: Long) {
        if (!isConnected) return
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            delay(delayMs)
            sync()
        }
    }

    fun sync(onDone: (() -> Unit)? = null) {
        if (!isConnected) {
            status = SyncStatus.LocalOnly
            onDone?.invoke()
            return
        }
        if (syncing) {
            syncAgain = true
            onDone?.invoke()
            return
        }
        flushSave()
        syncing = true
        status = SyncStatus.Syncing
        val (srv, tok) = server to token
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { vault.sync(srv, tok) } }
            syncing = false
            result
                .onSuccess { summary: SyncSummary ->
                    status = SyncStatus.Idle(System.currentTimeMillis())
                    if (summary.conflicts.isNotEmpty()) conflicts = summary.conflicts
                    if (summary.changed.isNotEmpty()) {
                        reload()
                        reconcileOpenNote(summary.changed.toSet())
                    }
                }
                .onFailure { status = SyncStatus.Failed(it.userMessage()) }
            flushSave()
            onDone?.invoke()
            if (syncAgain) {
                syncAgain = false
                sync()
            }
        }
    }

    private fun reconcileOpenNote(changed: Set<String>) {
        val s = session ?: return
        if (s.path !in changed) return
        val stored = runCatching { vault.read(s.path) }.getOrNull()
        if (stored == null) {
            session = null
            stack.removeAll { it == Route.Note(s.path) }
            message = "“${s.path.noteTitle}” was deleted on another device."
            return
        }
        val next = if (s.text == s.saved) stored else merge3(s.saved, s.text, stored)
        s.saved = stored
        if (next != s.text) {
            s.text = next
            s.externalRevision++
        }
        if (next != stored) edited()
    }

    // ---- Settings --------------------------------------------------------------

    /** Returns the backend name on success. */
    suspend fun connect(serverUrl: String, accessToken: String): Result<String> {
        val srv = serverUrl.trim().trimEnd('/')
        val tok = accessToken.trim()
        return withContext(Dispatchers.IO) { runCatching { checkServer(srv, tok) } }.onSuccess {
            if (srv != server) runCatching { vault.resetSync() }
            server = srv
            token = tok
            prefs.edit().putString("server", srv).putString("token", tok).apply()
            sync()
        }
    }

    fun disconnect() {
        server = ""
        token = ""
        prefs.edit().remove("server").remove("token").apply()
        status = SyncStatus.LocalOnly
    }
}
