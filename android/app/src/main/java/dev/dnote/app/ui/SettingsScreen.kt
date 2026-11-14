package dev.dnote.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dnote.app.NotesViewModel
import dev.dnote.app.SyncStatus
import dev.dnote.app.userMessage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(model: NotesViewModel) {
    var server by remember { mutableStateOf(model.server) }
    var token by remember { mutableStateOf(model.token) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val scope = rememberCoroutineScope()
    val dn = LocalDN.current
    var editing by remember { mutableStateOf<uniffi.dnote_ffi.TagInfo?>(null) }

    Scaffold(
        containerColor = dn.chrome,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = dn.chrome),
                navigationIcon = { IconButton(onClick = { model.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = dn.text) } },
                title = { Text("Settings", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = dn.text) },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionTitle("Sync server")
            Text(
                "Optional. A self-hosted dnote-server or your Cloudflare Worker. Notes always stay on this device too.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text("Server URL") },
                placeholder = { Text("https://notes.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Access token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    shape = MaterialTheme.shapes.small,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = dn.accent, contentColor = androidx.compose.ui.graphics.Color.White),
                    enabled = !checking && server.isNotBlank() && token.isNotBlank(),
                    onClick = {
                        checking = true
                        result = null
                        scope.launch {
                            model.connect(server, token)
                                .onSuccess { result = "Connected ($it). Syncing…" to true }
                                .onFailure { result = it.userMessage() to false }
                            checking = false
                        }
                    },
                ) { Text(if (model.isConnected) "Save & sync" else "Connect") }
                if (checking) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            result?.let { (text, ok) ->
                Text(text, color = if (ok) dn.success else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            HorizontalDivider()
            SectionTitle("Local development")
            QuickFill("Emulator → this computer", "http://10.0.2.2:8787") { server = it }
            QuickFill("Emulator → wrangler dev", "http://10.0.2.2:8898") { server = it }
            Text(
                "On a real phone, run `adb reverse tcp:8787 tcp:8787` and use http://localhost:8787, or use your computer's LAN address.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (model.isConnected) {
                HorizontalDivider()
                SectionTitle("Status")
                SyncLine(model)
                (model.status as? SyncStatus.Failed)?.let {
                    Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { model.sync() }) { Text("Sync now") }
                    TextButton(onClick = {
                        model.disconnect()
                        result = "Disconnected. Notes stay on this device." to true
                    }) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
                }
            }

            HorizontalDivider()
            SectionTitle("Appearance")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val options = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
                options.forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = model.theme == value,
                        onClick = { model.updateTheme(value) },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size, MaterialTheme.shapes.small),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = dn.accentSoft,
                            activeContentColor = dn.accent,
                            inactiveContainerColor = dn.surface,
                            inactiveContentColor = dn.text2,
                            activeBorderColor = dn.border,
                            inactiveBorderColor = dn.border,
                        ),
                        icon = {},
                    ) { Text(label) }
                }
            }

            HorizontalDivider()
            SectionTitle("Tags")
            Text(
                "Favorite is built in. Create tags from a note's tag button; rename, recolour or delete them here.",
                style = MaterialTheme.typography.bodySmall,
                color = dn.muted,
            )
            ListGroup(inset = 0.dp) {
                model.tags.forEachIndexed { i, t ->
                    if (i > 0) RowDivider(44.dp)
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = t.id != "favorite") { editing = t }
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (t.id == "favorite") {
                            Icon(androidx.compose.material.icons.Icons.Filled.Star, null, tint = tagColor(t.color), modifier = Modifier.size(16.dp))
                        } else {
                            androidx.compose.foundation.layout.Box(
                                Modifier.padding(3.dp).size(10.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(tagColor(t.color)),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(t.name, color = dn.text, modifier = Modifier.weight(1f))
                        Text("${t.count}", color = dn.faint, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            HorizontalDivider()
            SectionTitle("Storage")
            Text(
                "${model.entries.count { !it.isFolder && !it.isImage }} notes and ${model.entries.count { it.isImage }} images stored on this device.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(40.dp))
        }
    }
    editing?.let { tag -> TagEditor(model, tag) { editing = null } }
}

@Composable
private fun TagEditor(model: NotesViewModel, tag: uniffi.dnote_ffi.TagInfo, onDone: () -> Unit) {
    var name by remember { mutableStateOf(tag.name) }
    var color by remember { mutableStateOf(tag.color) }
    val colors = remember { uniffi.dnote_ffi.tagColors() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDone,
        containerColor = LocalDN.current.surface,
        title = { Text("Edit tag") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth())
                ColorSwatches(colors, color) { color = it }
            }
        },
        confirmButton = {
            Button(onClick = { model.updateTag(tag.id, name, color); onDone() }, shape = MaterialTheme.shapes.small) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { model.deleteTag(tag.id); onDone() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDone) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = LocalDN.current.faint, letterSpacing = 0.6.sp)
}

@Composable
private fun QuickFill(label: String, url: String, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onPick(url) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
