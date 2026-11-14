package dev.dnote.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dnote.app.NotesViewModel

/** A subfolder, opened by drilling in from the home list (slides in from the right). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(model: NotesViewModel, folder: String) {
    val dn = LocalDN.current
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    val pinned = model.isPinned(folder)

    Scaffold(
        containerColor = dn.chrome,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = dn.chrome),
                navigationIcon = { IconButton(onClick = { model.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = dn.text) } },
                title = {
                    Column {
                        Text(
                            folder.substringAfterLast('/'),
                            fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp, color = dn.text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if ('/' in folder) {
                            Text(folder.substringBeforeLast('/'), fontSize = 12.sp, color = dn.muted, maxLines = 1)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { model.togglePin(folder) }) {
                        Icon(
                            if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            if (pinned) "Unpin" else "Pin",
                            tint = if (pinned) dn.accent else dn.muted,
                        )
                    }
                    IconButton(onClick = { prompt = Prompt.Tags(folder) }) {
                        Icon(Icons.AutoMirrored.Outlined.Label, "Tags", tint = dn.muted)
                    }
                    IconButton(onClick = { prompt = Prompt.NewFolder(folder) }) {
                        Icon(Icons.Outlined.CreateNewFolder, "New folder", tint = dn.muted)
                    }
                },
            )
        },
        bottomBar = {
            Box(Modifier.background(dn.chrome).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                PrimaryButton("New note") { prompt = Prompt.NewNote(folder) }
            }
        },
    ) { padding ->
        val items = model.children(folder).sortedBy { if (it.isFolder) 0 else if (it.isImage) 2 else 1 }
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp)) {
            if (items.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.AutoMirrored.Outlined.Article, null, tint = dn.faint, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("This folder is empty", color = dn.text, fontWeight = FontWeight.SemiBold)
                    Text("Notes are Markdown files stored on this device.", color = dn.muted, fontSize = 13.5.sp)
                }
            } else {
                ListGroup {
                    items.forEachIndexed { i, entry ->
                        if (i > 0) RowDivider()
                        EntryRow(model, entry, "folder", onPrompt = { prompt = it })
                    }
                }
            }
        }
    }
    PromptHost(model, prompt) { prompt = null }
}
