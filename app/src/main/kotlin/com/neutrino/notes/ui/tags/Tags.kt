package com.neutrino.notes.ui.tags

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neutrino.notes.Route
import com.neutrino.notes.core.model.NoteItem
import com.neutrino.notes.core.model.NoteTag
import com.neutrino.notes.graph
import com.neutrino.notes.ui.PageBar
import com.neutrino.notes.ui.Unavailable
import com.neutrino.notes.ui.relativeTime
import com.neutrino.shared.android.ui.ConfirmDialog
import com.neutrino.shared.android.ui.TextInputDialog
import kotlinx.coroutines.launch

/** Every tag, with how many notes carry it. Long-press to rename or delete; tap to see its notes. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TagsScreen() {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val tags by graph.tags.tags.collectAsState()
    val error by graph.tags.error.collectAsState()
    val online by graph.isOnline.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<NoteTag?>(null) }
    var deleting by remember { mutableStateOf<NoteTag?>(null) }
    var createError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { graph.tags.load() }

    Column(Modifier.fillMaxSize()) {
        PageBar("Tags", null) { IconButton(onClick = { creating = true }, enabled = online) { Icon(Icons.Filled.Add, "New Tag") } }
        PullToRefreshBox(refreshing, { scope.launch { refreshing = true; graph.tags.load(); refreshing = false } }, Modifier.weight(1f)) {
            if (tags.isEmpty()) Unavailable(Icons.Filled.Sell, "No Tags", if (online) "Tag a note from its menu, or tap + to make one." else "Tags need a connection.")
            LazyColumn(Modifier.fillMaxSize()) {
                items(tags, key = { it.id }) { tag ->
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        Row(Modifier.fillMaxWidth().combinedClickable(onClick = { graph.router.push(Route.Tagged(tag)) }, onLongClick = { menu = true })
                            .padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Sell, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(14.dp))
                            Text(tag.name, Modifier.weight(1f))
                            Text(tag.fileCount.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; renaming = tag })
                            DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; deleting = tag })
                        }
                    }
                }
            }
        }
    }
    if (creating) TextInputDialog("New Tag", "", "Name", "Create", onDismiss = { creating = false }) { name ->
        creating = false
        scope.launch { runCatching { graph.tags.createTag(name) }.onFailure { createError = it.message } }
    }
    renaming?.let { tag -> TextInputDialog("Rename Tag", tag.name, "Name", "Save", onDismiss = { renaming = null }) { renaming = null; graph.tags.renameTag(tag, it) } }
    deleting?.let { tag ->
        ConfirmDialog("Delete “${tag.name}”?", "The tag comes off every note that has it. The notes themselves are kept.", "Delete", destructive = true,
            onDismiss = { deleting = null }) { graph.tags.deleteTag(tag) }
    }
    (createError ?: error)?.let { message ->
        AlertDialog(onDismissRequest = { createError = null; graph.tags.error.value = null }, title = { Text("Tags") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { createError = null; graph.tags.error.value = null }) { Text("OK") } })
    }
}

/** The notes carrying one tag, newest first. */
@Composable
fun TaggedNotesScreen(tag: NoteTag, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    var notes by remember { mutableStateOf<List<NoteItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tag.id) {
        runCatching { graph.tags.notes(tag.id) }.onSuccess { notes = it.sortedByDescending { n -> n.modifiedAt } }.onFailure { error = it.message; notes = emptyList() }
    }
    Column(Modifier.fillMaxSize()) {
        PageBar(tag.name, onBack)
        when {
            notes == null -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            notes!!.isEmpty() -> Unavailable(Icons.Filled.Sell, "No Notes", error ?: "No notes carry this tag.")
            else -> LazyColumn {
                items(notes!!, key = { it.id }) { note ->
                    Row(Modifier.fillMaxWidth().clickable { graph.router.push(Route.Editor(note)) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.Notes, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(note.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(relativeTime(note.modifiedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
