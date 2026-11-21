package com.neutrino.notes.ui.editor

import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.neutrino.notes.EditorFont
import com.neutrino.notes.NotesPrefs
import com.neutrino.notes.core.api.NotesApi
import com.neutrino.notes.core.markdown.MarkdownEditing
import com.neutrino.notes.core.markdown.MarkdownFormat
import com.neutrino.notes.core.markdown.MarkdownParser
import com.neutrino.notes.core.markdown.WikiLink
import com.neutrino.notes.core.markdown.WikiLinkIndex
import com.neutrino.notes.core.model.FileLink
import com.neutrino.notes.core.model.NoteItem
import com.neutrino.notes.core.model.NoteTextStats
import com.neutrino.notes.core.model.ShareRole
import com.neutrino.notes.core.service.OfflineException
import com.neutrino.notes.graph
import com.neutrino.notes.ui.NotesStyle
import com.neutrino.notes.ui.PageBar
import com.neutrino.notes.ui.Unavailable
import com.neutrino.shared.android.ui.TextInputDialog
import com.neutrino.shared.android.ui.openUrl
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private sealed class SaveStatus {
    object Idle : SaveStatus()
    object Saving : SaveStatus()
    object Saved : SaveStatus()
    object SavedOffline : SaveStatus()
    data class Failed(val message: String) : SaveStatus()
    /** The server refused the write: the note moved on elsewhere. */
    object Conflict : SaveStatus()
}

/**
 * One note: its Markdown source to write in, or rendered to read. The text is the note — there is
 * no model beside it — and it autosaves a moment after typing stops, guarded by the content version
 * it was loaded at, so a save never lands over a change made elsewhere unseen. Offline, or when the
 * server cannot be reached, edits go to the encrypted offline copy and sync later.
 */
@Composable
fun EditorScreen(item: NoteItem, startEditing: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val prefs = graph.prefs
    val online by graph.isOnline.collectAsState()
    val fontSize by prefs.fontSize.collectAsState()
    val font by prefs.editorFont.collectAsState()
    val smartLists by prefs.smartLists.collectAsState()
    val allTags by graph.tags.tagsByFile.collectAsState()
    val backlinksMap by graph.links.backlinksByFile.collectAsState()
    val indexed by graph.drive.indexedNotes.collectAsState()

    var current by remember { mutableStateOf(graph.drive.item(item.id) ?: item) }
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var dek by remember { mutableStateOf<ByteArray?>(null) }
    var contentVersion by remember { mutableStateOf<Long?>(null) }
    var serverUpdatedAt by remember { mutableStateOf<Long?>(null) }
    var role by remember { mutableStateOf(if (item.isShared) null else ShareRole.OWNER) }
    var usingOfflineCopy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(startEditing || !prefs.openInPreview.value) }
    var dirty by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<SaveStatus>(SaveStatus.Idle) }
    var remoteChanged by remember { mutableStateOf(false) }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    val undo = remember { mutableStateListOf<TextFieldValue>() }
    val redo = remember { mutableStateListOf<TextFieldValue>() }
    var finding by remember { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showVersions by remember { mutableStateOf(false) }
    var savingVersion by remember { mutableStateOf(false) }
    var unresolved by remember { mutableStateOf<String?>(null) }
    var linkError by remember { mutableStateOf<String?>(null) }
    var linkIndex by remember { mutableStateOf(WikiLinkIndex(graph.drive.linkableNotes)) }
    LaunchedEffect(indexed) { linkIndex = WikiLinkIndex(graph.drive.linkableNotes) }

    // A shared note is read-only until the server says otherwise; a cached copy of one stays read-only.
    val readOnly = role?.canEdit != true
    val text = value.text
    val events = remember { graph.fileEvents() }

    suspend fun load() {
        loading = true
        loadError = null
        val cached = graph.offline.isAvailableOffline(item.id)
        if (!online) {
            try {
                val (t, k) = graph.offline.readPlaintext(item.id)
                value = TextFieldValue(t); dek = k; usingOfflineCopy = true
                contentVersion = graph.offline.note(item.id)?.serverContentVersion
            } catch (e: OfflineException.NotCached) {
                loadError = "Not available offline — download it while connected."
            } catch (e: Exception) {
                loadError = e.message
            }
            loading = false
            return
        }
        try {
            // Read before the content, never after: a change landing between leaves an older stamp than the text.
            val info = runCatching { graph.content.fileInfo(item.id) }.getOrNull()
            val (t, k) = graph.content.loadContent(current)
            value = TextFieldValue(t, TextRange(0)); dek = k; usingOfflineCopy = false
            contentVersion = info?.contentVersion ?: current.contentVersion
            serverUpdatedAt = info?.updatedAt?.toEpochMilli()
            if (info != null) role = if (item.isShared) info.yourRole else ShareRole.OWNER
            undo.clear(); redo.clear()
        } catch (e: Exception) {
            // The network is nominally up but the fetch failed: a downloaded copy is a better answer than an error.
            if (cached && runCatching {
                    val (t, k) = graph.offline.readPlaintext(item.id)
                    value = TextFieldValue(t); dek = k; usingOfflineCopy = true
                    contentVersion = graph.offline.note(item.id)?.serverContentVersion
                }.isSuccess) Unit else loadError = e.message
        }
        loading = false
        if (!usingOfflineCopy && online) events.connect(item.id)
    }

    suspend fun publishSideEffects(saved: String) {
        if (!readOnly) graph.links.updateLinksIgnoringFailure(item.id, saved)
        events.broadcastFileUpdate()
    }

    suspend fun save(force: Boolean = false) {
        val key = dek ?: return
        if (readOnly || (!dirty && !force)) return
        // Read the state, not `text`: this runs later than the composition that scheduled it.
        val snapshot = value.text
        dirty = false
        status = SaveStatus.Saving
        val offlineOnly = !online || usingOfflineCopy
        if (offlineOnly) {
            if (!graph.offline.isAvailableOffline(item.id)) {
                status = SaveStatus.Failed("This note isn't downloaded, so offline edits can't be saved.")
                dirty = true
                return
            }
            try {
                graph.offline.writePendingEdit(snapshot, item.id, key)
                status = SaveStatus.SavedOffline
            } catch (e: Exception) {
                status = SaveStatus.Failed(e.message ?: "Save failed")
                dirty = true
            }
            return
        }
        try {
            val saved = graph.content.saveContent(snapshot, current, key, if (force) null else contentVersion)
            contentVersion = saved.contentVersion ?: contentVersion
            serverUpdatedAt = saved.updatedAt.toEpochMilli()
            graph.drive.noteWasSaved(item.id, snapshot.toByteArray().size.toLong(), saved.updatedAt, saved.contentVersion)
            if (graph.offline.isAvailableOffline(item.id)) runCatching {
                graph.offline.cacheLocalVersion(snapshot, item.id, key, saved.updatedAt.toEpochMilli(), saved.contentVersion)
            }
            if (value.text != snapshot) dirty = true
            status = SaveStatus.Saved
            publishSideEffects(snapshot)
        } catch (e: NotesApi.ContentVersionConflict) {
            dirty = true
            status = SaveStatus.Conflict
        } catch (e: Exception) {
            dirty = true
            status = SaveStatus.Failed(e.message ?: "Save failed")
        }
    }

    fun scheduleSave() {
        if (readOnly) return
        dirty = true
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(NotesPrefs.AUTO_SAVE_DELAY_MS)
            save()
        }
    }

    /** Every change to the text goes through here: the undo stack, then the autosave. */
    fun change(next: TextFieldValue) {
        if (next.text != value.text) {
            undo += value
            if (undo.size > 200) undo.removeAt(0)
            redo.clear()
            value = next
            scheduleSave()
        } else value = next
    }

    fun applyEdit(edit: MarkdownEditing.Edit) =
        change(TextFieldValue(edit.applyTo(value.text), TextRange(edit.caret)))

    suspend fun reloadFromRemote() {
        saveJob?.cancel()
        dirty = false
        remoteChanged = false
        status = SaveStatus.Idle
        load()
        runCatching { graph.links.loadBacklinks(item.id) }
    }

    fun handleRemoteUpdate() {
        if (dirty || status == SaveStatus.Saving) remoteChanged = true else scope.launch { reloadFromRemote() }
    }

    LaunchedEffect(Unit) {
        load()
        events.onRemoteUpdate = { handleRemoteUpdate() }
        events.onReconnect = {
            // The relay keeps no history: ask whether a ring was missed while it was down.
            scope.launch {
                if (status == SaveStatus.Saving || usingOfflineCopy) return@launch
                val latest = runCatching { graph.content.fileInfo(item.id) }.getOrNull()?.takeIf { it.isLive } ?: return@launch
                val known = serverUpdatedAt
                if (known == null || latest.updatedAt.toEpochMilli() > known) handleRemoteUpdate()
            }
        }
        if (online) {
            launch { runCatching { graph.tags.loadTags(item.id) } }
            launch { runCatching { graph.links.loadBacklinks(item.id) } }
            launch { graph.drive.loadNoteIndex() }
        }
    }

    // Leaving: save what is unsaved, and close the relay.
    DisposableEffect(Unit) {
        onDispose {
            events.disconnect()
            if (dirty) graph.scope.launch { save() }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> { if (dirty) graph.scope.launch { save() }; events.suspend() }
                Lifecycle.Event.ON_START -> events.resume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = finding) { finding = false }

    fun openNote(target: NoteItem) = graph.router.follow(target)

    fun openWikiLink(title: String) {
        linkIndex.item(title)?.let { openNote(it); return }
        scope.launch {
            // "Not in the index" is not "doesn't exist": refresh before offering to create a duplicate.
            if (online) graph.drive.loadNoteIndex(force = true)
            linkIndex = WikiLinkIndex(graph.drive.linkableNotes)
            val found = linkIndex.item(title)
            when {
                found != null -> openNote(found)
                readOnly || !online -> linkError = "“$title” isn’t a note you can open from here."
                else -> unresolved = title
            }
        }
    }

    fun openUrlOrNote(destination: String) {
        WikiLink.titleForRelativeLink(destination)?.let { openWikiLink(it); return }
        val id = Regex("""^https://(?:www\.)?getneutrino\.app/open/note/([^/?#]+)""").find(destination)?.groupValues?.get(1)
        if (id != null) {
            scope.launch {
                try { openNote(graph.drive.item(id) ?: graph.drive.fetchItem(id)) } catch (e: Exception) { linkError = e.message }
            }
            return
        }
        runCatching { openUrl(context, destination) }.onFailure { linkError = "“$destination” can’t be opened from Notes." }
    }

    fun openBacklink(link: FileLink) {
        if (!link.isNote) {
            // Another app's file: its app takes the link if installed, the web otherwise.
            openUrl(context, "https://www.getneutrino.app/open/${link.fileType}/${link.id}")
            return
        }
        scope.launch {
            try { openNote(graph.drive.item(link.id) ?: graph.drive.fetchItem(link.id)) } catch (e: Exception) { linkError = e.message }
        }
    }

    suspend fun createLinkedNote(title: String) {
        try {
            val created = graph.drive.createNote(title, if (item.isShared) null else current.parentId)
            linkIndex = WikiLinkIndex(graph.drive.linkableNotes)
            // The link here now resolves though its text never changed: the one update that must go out regardless.
            graph.links.updateLinksIgnoringFailure(item.id, value.text, force = true)
            openNote(created)
        } catch (e: Exception) {
            linkError = e.message
        }
    }

    fun export() {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, WikiLink.fileName(current.displayName))
        file.writeText(value.text)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Send a Copy"))
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
        PageBar(current.displayName, {
            if (dirty) graph.scope.launch { save() }
            onBack()
        }) {
            if (editing && !readOnly) {
                IconButton(onClick = { undo.removeLastOrNull()?.let { redo += value; value = it; scheduleSave() } }, enabled = undo.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
                }
                IconButton(onClick = { redo.removeLastOrNull()?.let { undo += value; value = it; scheduleSave() } }, enabled = redo.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.Redo, "Redo")
                }
            }
            IconButton(onClick = { editing = !editing }, enabled = !loading && loadError == null) {
                Icon(if (editing) Icons.Filled.Visibility else Icons.Filled.Edit, if (editing) "Preview" else "Edit")
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    fun act(f: () -> Unit) { menu = false; f() }
                    DropdownMenuItem(text = { Text("Find & Replace") }, leadingIcon = { Icon(Icons.Filled.Search, null) }, onClick = { act { editing = true; finding = true } })
                    if (!item.isShared) {
                        DropdownMenuItem(text = { Text("Tags…") }, leadingIcon = { Icon(Icons.Filled.Sell, null) }, enabled = online, onClick = { act { showTags = true } })
                        DropdownMenuItem(text = { Text("Share…") }, leadingIcon = { Icon(Icons.Filled.PersonAdd, null) }, enabled = online, onClick = { act { showShare = true } })
                    }
                    if (!readOnly) {
                        DropdownMenuItem(text = { Text("Save Version…") }, leadingIcon = { Icon(Icons.Filled.Bookmark, null) }, enabled = online && !usingOfflineCopy, onClick = { act { savingVersion = true } })
                    }
                    DropdownMenuItem(text = { Text("Version History") }, leadingIcon = { Icon(Icons.Filled.History, null) }, enabled = online && !usingOfflineCopy, onClick = { act { showVersions = true } })
                    val cached = graph.offline.isAvailableOffline(item.id)
                    if (!cached) DropdownMenuItem(text = { Text("Make Available Offline") }, leadingIcon = { Icon(Icons.Filled.CloudDownload, null) }, enabled = online,
                        onClick = { act { scope.launch { runCatching { graph.offline.download(current) }.onFailure { linkError = it.message } } } })
                    DropdownMenuItem(text = { Text("Copy Link") }, leadingIcon = { Icon(Icons.Filled.Link, null) }, onClick = {
                        act {
                            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Note link", com.neutrino.notes.NoteLinks.link(item.id)))
                        }
                    })
                    DropdownMenuItem(text = { Text("Send a Copy…") }, leadingIcon = { Icon(Icons.Filled.Share, null) }, enabled = loadError == null, onClick = { act { export() } })
                }
            }
        }

        if (remoteChanged) Banner("This note changed somewhere else.", "Reload") { scope.launch { reloadFromRemote() } }
        if (status == SaveStatus.Conflict) Surface(color = NotesStyle.warning.copy(alpha = 0.15f)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("This note changed on another device since it was opened.", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { scope.launch { reloadFromRemote() } }) { Text("Use Theirs") }
                    TextButton(onClick = { scope.launch { save(force = true) } }) { Text("Keep Mine") }
                }
            }
        }
        if (readOnly && !loading && loadError == null) Text(
            if (usingOfflineCopy && item.isShared) "Read only — a shared note's offline copy can't be edited." else "Read only — you can view this note but not edit it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        val tags = allTags[item.id].orEmpty()
        if (tags.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tags.forEach { AssistChip(onClick = { if (online) showTags = true }, label = { Text(it.name) }, leadingIcon = { Icon(Icons.Filled.Sell, null, Modifier.size(14.dp)) }) }
        }
        if (finding) FindBar(value, onClose = { finding = false }, onSelect = { value = value.copy(selection = it) }, onReplace = { change(it) })

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                loadError != null -> Unavailable(Icons.Filled.Warning, "Couldn’t open this note", loadError!!, "Retry") { scope.launch { load() } }
                editing -> {
                    val family = when (font) { EditorFont.MONOSPACED -> FontFamily.Monospace; EditorFont.SERIF -> FontFamily.Serif; EditorFont.SYSTEM -> FontFamily.SansSerif }
                    BasicTextField(
                        value = value,
                        onValueChange = { next ->
                            // A lone newline typed at the caret is Return: the list rules may want it.
                            val old = value
                            val inserted = next.text.length == old.text.length - (old.selection.max - old.selection.min) + 1 &&
                                next.selection.collapsed && next.selection.start == old.selection.min + 1 &&
                                next.text.getOrNull(old.selection.min) == '\n' &&
                                next.text.substring(0, old.selection.min) == old.text.substring(0, old.selection.min)
                            val rule = if (inserted && smartLists) MarkdownEditing.returnPressed(old.text, old.selection.min, old.selection.max) else null
                            if (rule != null) applyEdit(rule) else change(next)
                        },
                        readOnly = readOnly,
                        textStyle = TextStyle(fontSize = fontSize.sp, fontFamily = family, color = MaterialTheme.colorScheme.onSurface, lineHeight = (fontSize * 1.4f).sp),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        decorationBox = { inner ->
                            if (text.isEmpty() && !readOnly) Text("Start writing… Type / for formatting, [[ to link a note.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = fontSize.sp, fontFamily = family)
                            inner()
                        },
                    )
                }
                else -> {
                    val document = remember(text) { MarkdownParser.parse(text) }
                    val backlinks = backlinksMap[item.id].orEmpty()
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        if (text.isBlank()) Text("This note is empty.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MarkdownPreview(document, linkIndex, fontSize, canToggle = !readOnly) { action ->
                            when (action) {
                                is PreviewAction.WikiLink -> openWikiLink(action.title)
                                is PreviewAction.Url -> openUrlOrNote(action.destination)
                                is PreviewAction.ToggleTask -> MarkdownEditing.toggleTask(value.text, action.line)?.let { change(TextFieldValue(it, value.selection)) }
                            }
                        }
                        if (backlinks.isNotEmpty()) {
                            HorizontalDivider(Modifier.padding(top = 24.dp, bottom = 8.dp))
                            Text("Linked from", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            backlinks.forEach { link ->
                                Text((if (link.isNote) "" else "${link.fileType.replaceFirstChar { it.uppercase() }}: ") + link.displayTitle,
                                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().clickable { openBacklink(link) }.padding(vertical = 8.dp))
                            }
                        }
                    }
                }
            }
        }

        if (editing && !readOnly && !loading && loadError == null) {
            val slash = MarkdownEditing.slashToken(text, value.selection.start).takeIf { value.selection.collapsed }
            val wiki = WikiLink.token(text, value.selection.start).takeIf { value.selection.collapsed }
            when {
                slash != null -> SuggestionBar(MarkdownFormat.matching(slash.query).map { it.title to it }) { format ->
                    applyEdit(MarkdownEditing.apply(format, value.text, value.selection.start, value.selection.end, slash))
                }
                wiki != null -> {
                    val suggestions = linkIndex.suggestions(wiki.query).filter { it.id != item.id }
                    val choices = suggestions.map { it.displayName to it.displayName } +
                        (if (wiki.query.isNotBlank() && suggestions.none { it.displayName.equals(wiki.query.trim(), true) }) listOf("Create “${wiki.query.trim()}”" to "\u0000" + wiki.query.trim()) else emptyList())
                    SuggestionBar(choices) { title ->
                        val create = title.startsWith("\u0000")
                        val name = title.removePrefix("\u0000")
                        val end = if (value.text.startsWith("]]", wiki.end)) wiki.end + 2 else wiki.end
                        val insert = "[[$name]]"
                        applyEdit(MarkdownEditing.Edit(wiki.start, end, insert, wiki.start + insert.length))
                        if (create) scope.launch {
                            runCatching { graph.drive.createNote(name, if (item.isShared) null else current.parentId) }.onSuccess {
                                linkIndex = WikiLinkIndex(graph.drive.linkableNotes)
                            }.onFailure { linkError = it.message }
                        }
                    }
                }
                else -> FormatBar { id ->
                    val format = MarkdownFormat.byId(id)
                    applyEdit(MarkdownEditing.apply(format, value.text, value.selection.min, value.selection.max))
                }
            }
        }
        StatusLine(text, status, dirty)
    }

    if (showTags) TagPickerDialog(item.id) { showTags = false }
    if (showShare) ShareDialog(current) { showShare = false }
    if (showVersions) dek?.let { key ->
        VersionHistoryDialog(current, key, text, onDismiss = { showVersions = false }) {
            // Restored: reload so the pre-restore text cannot be written back over it.
            showVersions = false
            scope.launch { reloadFromRemote() }
        }
    }
    if (savingVersion) TextInputDialog("Save Version", "", "Name (optional)", "Save", onDismiss = { savingVersion = false }) { label ->
        savingVersion = false
        val key = dek ?: return@TextInputDialog
        saveJob?.cancel()
        scope.launch {
            status = SaveStatus.Saving
            try {
                val snapshot = value.text
                graph.versions.saveVersion(snapshot, current, key, label.ifBlank { null })
                dirty = false
                // The snapshot's stamp precedes the file's: ask rather than read our own save as a missed change.
                graph.content.fileInfo(item.id)?.let { serverUpdatedAt = it.updatedAt.toEpochMilli(); contentVersion = it.contentVersion }
                status = SaveStatus.Saved
                publishSideEffects(snapshot)
            } catch (e: Exception) {
                status = SaveStatus.Failed(e.message ?: "Save failed")
            }
        }
    }
    unresolved?.let { title ->
        AlertDialog(
            onDismissRequest = { unresolved = null },
            title = { Text("Create Note") },
            text = { Text("“$title” doesn’t exist yet. Create it?") },
            confirmButton = { TextButton(onClick = { unresolved = null; scope.launch { createLinkedNote(title) } }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { unresolved = null }) { Text("Cancel") } },
        )
    }
    linkError?.let { message ->
        AlertDialog(
            onDismissRequest = { linkError = null },
            title = { Text("Couldn’t Open Link") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { linkError = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun Banner(message: String, action: String, onAction: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun <T> SuggestionBar(choices: List<Pair<String, T>>, onChoose: (T) -> Unit) {
    if (choices.isEmpty()) return
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.forEach { (label, value) -> AssistChip(onClick = { onChoose(value) }, label = { Text(label) }) }
        }
    }
}

/** The formatting bar over the keyboard: the slash menu's most-used entries, one tap each. */
@Composable
private fun FormatBar(onFormat: (String) -> Unit) {
    val items = listOf("heading1" to "H1", "heading2" to "H2", "bold" to "B", "italic" to "I", "strikethrough" to "S", "code" to "</>",
        "bulletList" to "•", "numberedList" to "1.", "checklist" to "☐", "quote" to "❝", "link" to "Link", "codeBlock" to "```", "divider" to "—")
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            items.forEach { (id, label) ->
                TextButton(onClick = { onFormat(id) }) {
                    Text(label, fontWeight = if (id == "bold") FontWeight.Bold else null,
                        fontStyle = if (id == "italic") androidx.compose.ui.text.font.FontStyle.Italic else null)
                }
            }
        }
    }
}

@Composable
private fun StatusLine(text: String, status: SaveStatus, dirty: Boolean) {
    val stats = remember(text) { NoteTextStats.compute(text) }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stats.summaryText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
        Spacer(Modifier.width(8.dp))
        val (label, color) = when (status) {
            SaveStatus.Saving -> "Saving…" to MaterialTheme.colorScheme.onSurfaceVariant
            SaveStatus.Saved -> (if (dirty) "Edited" else "Saved") to MaterialTheme.colorScheme.onSurfaceVariant
            SaveStatus.SavedOffline -> "Saved offline · will sync" to NotesStyle.warning
            is SaveStatus.Failed -> "Save failed: ${status.message}" to MaterialTheme.colorScheme.error
            SaveStatus.Conflict -> "Not saved — changed elsewhere" to MaterialTheme.colorScheme.error
            SaveStatus.Idle -> (if (dirty) "Edited" else "") to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}
