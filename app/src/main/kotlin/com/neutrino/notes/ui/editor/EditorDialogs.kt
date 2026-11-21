package com.neutrino.notes.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.neutrino.notes.core.markdown.TextDiff
import com.neutrino.notes.core.model.DirectoryUser
import com.neutrino.notes.core.model.NoteItem
import com.neutrino.notes.core.model.NoteVersion
import com.neutrino.notes.core.model.ShareRole
import com.neutrino.notes.graph
import com.neutrino.notes.ui.NotesStyle
import com.neutrino.notes.ui.formatDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Find in the note, and replace one or all. Matching is case-insensitive, as iOS's find navigator. */
@Composable
fun FindBar(value: TextFieldValue, onClose: () -> Unit, onSelect: (TextRange) -> Unit, onReplace: (TextFieldValue) -> Unit) {
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var current by remember { mutableStateOf(0) }
    val matches = remember(value.text, query) {
        if (query.isEmpty()) emptyList() else Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(value.text).map { it.range.first until it.range.last + 1 }.toList()
    }
    fun select(i: Int) {
        if (matches.isEmpty()) return
        current = ((i % matches.size) + matches.size) % matches.size
        val r = matches[current]
        onSelect(TextRange(r.first, r.last + 1))
    }
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it; current = 0 }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Find") },
                    supportingText = { Text(if (query.isEmpty()) "" else if (matches.isEmpty()) "No matches" else "${current + 1} of ${matches.size}") })
                IconButton(onClick = { select(current - 1) }, enabled = matches.isNotEmpty()) { Icon(Icons.Filled.KeyboardArrowUp, "Previous") }
                IconButton(onClick = { select(current + 1) }, enabled = matches.isNotEmpty()) { Icon(Icons.Filled.KeyboardArrowDown, "Next") }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(replacement, { replacement = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Replace with") })
                TextButton(onClick = {
                    val r = matches.getOrNull(current) ?: return@TextButton
                    val text = value.text.replaceRange(r.first, r.last + 1, replacement)
                    onReplace(TextFieldValue(text, TextRange(r.first + replacement.length)))
                }, enabled = matches.isNotEmpty()) { Text("Replace") }
                TextButton(onClick = {
                    onReplace(TextFieldValue(value.text.replace(query, replacement, ignoreCase = true), value.selection))
                }, enabled = matches.isNotEmpty()) { Text("All") }
            }
        }
    }
}

/** Tags on one note: tick to attach, untick to detach, or make a new one. Saved as the difference. */
@Composable
fun TagPickerDialog(fileId: String, onDismiss: () -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val all by graph.tags.tags.collectAsState()
    var selected by remember { mutableStateOf(graph.tags.tags(fileId).map { it.id }.toSet()) }
    var newName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        graph.tags.load()
        runCatching { graph.tags.loadTags(fileId) }.onSuccess { list -> selected = list.map { it.id }.toSet() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tags") },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    if (all.isEmpty()) item { Text("No tags yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(all, key = { it.id }) { tag ->
                        Row(Modifier.fillMaxWidth().clickable { selected = if (tag.id in selected) selected - tag.id else selected + tag.id }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(tag.id in selected, { selected = if (it) selected + tag.id else selected - tag.id })
                            Text(tag.name, Modifier.weight(1f))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newName, { newName = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("New tag") })
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { graph.tags.createTag(newName) }.onSuccess { selected = selected + it.id; newName = "" }.onFailure { error = it.message }
                        }
                    }, enabled = newName.isNotBlank()) { Text("Add") }
                }
                Text("Tag names are stored unencrypted, like note names.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                busy = true
                scope.launch {
                    runCatching { graph.tags.applyTags(selected, fileId) }.onSuccess { onDismiss() }.onFailure { error = it.message }
                    busy = false
                }
            }, enabled = !busy) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Who a note or folder is shared with, and sharing it with someone else. Giving access to an
 * encrypted note also seals its key to them on this phone; someone with no key yet is warned.
 */
@Composable
fun ShareDialog(item: NoteItem, onDismiss: () -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val permissionsMap by graph.sharing.permissions.collectAsState()
    val hasKey by graph.sharing.hasKey.collectAsState()
    val serviceError by graph.sharing.error.collectAsState()
    val people = permissionsMap[item.id].orEmpty()
    var email by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(ShareRole.VIEWER) }
    var suggestions by remember { mutableStateOf<List<DirectoryUser>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { graph.sharing.loadPermissions(item) }
    LaunchedEffect(email) {
        delay(250)
        suggestions = graph.sharing.searchUsers(email).filter { u -> people.none { it.userId == u.id } }
    }
    fun share(user: DirectoryUser?) {
        busy = true
        message = null
        scope.launch {
            try {
                val target = user ?: graph.sharing.lookupUser(email)
                val result = graph.sharing.share(item, target, role)
                message = result.summary ?: "Shared with ${target.displayName}."
                email = ""
            } catch (e: Exception) {
                message = e.message
            }
            busy = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share “${item.displayName}”") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Email") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done))
                suggestions.take(5).forEach { u ->
                    Text("${u.displayName} · ${u.email}", Modifier.fillMaxWidth().clickable(enabled = !busy) { share(u) }.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.primary)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShareRole.grantable.forEach { r -> FilterChip(role == r, { role = r }, label = { Text(r.title) }) }
                }
                TextButton(onClick = { share(null) }, enabled = email.contains('@') && !busy) { Text("Share") }
                if (busy) CircularProgressIndicator(Modifier.padding(4.dp))
                (message ?: serviceError)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (people.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                people.forEach { p ->
                    var menu by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.displayName)
                            p.displayDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            if (p.role != ShareRole.OWNER && hasKey[p.userId] == false) Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Warning, null, tint = NotesStyle.warning, modifier = Modifier.padding(end = 4.dp))
                                Text("No encryption key yet — can't read it", style = MaterialTheme.typography.bodySmall, color = NotesStyle.warning)
                            }
                        }
                        Box {
                            TextButton(onClick = { menu = true }, enabled = p.role != ShareRole.OWNER) { Text(p.role.title) }
                            DropdownMenu(menu, { menu = false }) {
                                ShareRole.grantable.forEach { r ->
                                    DropdownMenuItem(text = { Text("${r.title} — ${r.summary}") }, onClick = { menu = false; graph.sharing.updateRole(item, p, r) })
                                }
                                DropdownMenuItem(text = { Text("Send Key Again") }, onClick = {
                                    menu = false
                                    scope.launch { message = runCatching { graph.sharing.sendKey(item, p).summary ?: "Key sent." }.getOrElse { it.message } }
                                })
                                DropdownMenuItem(text = { Text("Remove Access", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; graph.sharing.revoke(item, p) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/**
 * A note's history: named versions, each readable, comparable with the note as it stands, and
 * restorable. Autosave makes none; "Save Version…" does.
 */
@Composable
fun VersionHistoryDialog(item: NoteItem, dek: ByteArray, currentText: String, onDismiss: () -> Unit, onRestored: () -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var versions by remember { mutableStateOf<List<NoteVersion>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<Pair<NoteVersion, String>?>(null) }
    var comparing by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { graph.versions.listVersions(item.id) }.onSuccess { versions = it }.onFailure { error = it.message; versions = emptyList() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (open != null) { open = null; comparing = false } else onDismiss() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Text(open?.first?.displayTitle ?: "Version History", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    open?.let {
                        TextButton(onClick = { comparing = !comparing }) { Text(if (comparing) "Version" else "Compare") }
                        TextButton(onClick = { confirmRestore = true }) { Text("Restore") }
                    }
                }
                val shown = open
                when {
                    versions == null -> CircularProgressIndicator(Modifier.padding(32.dp))
                    shown == null -> LazyColumn {
                        if (versions!!.isEmpty()) item {
                            Text(error ?: "No saved versions yet. Use Save Version… to keep a copy you can return to.", Modifier.padding(24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(versions!!, key = { it.id }) { v ->
                            Column(Modifier.fillMaxWidth().clickable {
                                scope.launch { runCatching { graph.versions.versionText(item.id, v.id, dek) }.onSuccess { open = v to it }.onFailure { error = it.message } }
                            }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(v.displayTitle)
                                Text(formatDateTime(v.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    comparing -> {
                        val diff = remember(shown) { TextDiff.compare(shown.second, currentText) }
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                            Text("${shown.first.shortTitle} → now", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (diff.all { it.kind == TextDiff.Kind.UNCHANGED }) Text("No differences.", Modifier.padding(top = 8.dp))
                            diff.forEach { line ->
                                val (mark, bg) = when (line.kind) {
                                    TextDiff.Kind.INSERTED -> "+ " to Color(0x3334C759)
                                    TextDiff.Kind.DELETED -> "− " to Color(0x33FF3B30)
                                    TextDiff.Kind.UNCHANGED -> "  " to Color.Transparent
                                }
                                Text(mark + line.text, Modifier.fillMaxWidth().background(bg), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    else -> Text(shown.second, Modifier.verticalScroll(rememberScrollState()).padding(16.dp), fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
    if (confirmRestore) open?.let { (v, _) ->
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore ${v.shortTitle}?") },
            text = { Text("The note goes back to this version. What it says now is kept as a version first, so this can be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    scope.launch {
                        runCatching { graph.versions.restore(v.id, item.id) }.onSuccess { (size, at) ->
                            graph.drive.noteWasSaved(item.id, size, at, null)
                            onRestored()
                        }.onFailure { error = it.message; open = null }
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } },
        )
    }
}
