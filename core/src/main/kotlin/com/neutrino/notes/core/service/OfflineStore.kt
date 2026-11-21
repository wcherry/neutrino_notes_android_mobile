package com.neutrino.notes.core.service

import com.neutrino.notes.core.model.CacheReason
import com.neutrino.notes.core.model.Conflict
import com.neutrino.notes.core.model.NoteItem
import com.neutrino.notes.core.model.OfflineNote
import com.neutrino.notes.core.model.PendingEdit
import com.neutrino.notes.core.model.PendingMutation
import com.neutrino.shared.core.keys.KeyException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.text.Collator
import java.util.logging.Logger

sealed class OfflineException(message: String) : Exception(message) {
    class NotCached : OfflineException("That note isn't available offline. Download it while connected.")
    class HasPendingEdit : OfflineException("That note has unsynced changes. Sync it before removing the offline copy.")
    class DecryptionFailed : OfflineException("Failed to decrypt the offline copy of the note.")
}

/**
 * The notes available offline, and the metadata changes waiting for the server.
 *
 *     index.json         the catalogue
 *     queue.json         the mutation queue
 *     <id>.bin           server ciphertext of the cached version, byte for byte
 *     <id>.pending.bin   a local edit, encrypted under the same DEK
 *
 * **No plaintext is ever written to disk**: the cache holds the server's ciphertext and re-encrypts
 * local edits under the note's own DEK, so reading it back still needs this device's keyring.
 * Main-thread confined.
 */
class OfflineStore(private val directory: File, private val content: NoteContentService) {

    private val log = Logger.getLogger("OfflineStore")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }

    private val _notes = MutableStateFlow<List<OfflineNote>>(emptyList())
    /** The catalogue, by name. */
    val notes: StateFlow<List<OfflineNote>> = _notes

    private val _mutations = MutableStateFlow<List<PendingMutation>>(emptyList())
    /** Deletes, moves and renames made offline, oldest first. */
    val pendingMutations: StateFlow<List<PendingMutation>> = _mutations

    init {
        directory.mkdirs()
        _notes.value = runCatching { json.decodeFromString(ListSerializer(OfflineNote.serializer()), File(directory, INDEX).readText()) }
            .getOrDefault(emptyList()).let(::sorted)
        _mutations.value = runCatching { json.decodeFromString(ListSerializer(PendingMutation.serializer()), File(directory, QUEUE).readText()) }
            .getOrDefault(emptyList()).sortedBy { it.queuedAt }
    }

    fun note(id: String): OfflineNote? = _notes.value.firstOrNull { it.id == id }
    fun isAvailableOffline(id: String): Boolean = note(id) != null

    val pendingEditCount: Int get() = _notes.value.count { it.pendingEdit != null }
    val totalBytesOnDisk: Long get() = directory.listFiles { f -> f.name.endsWith(".bin") }?.sumOf { it.length() } ?: 0

    // ── Download / remove ───────────────────────────────────────────────────────────────────────

    /**
     * Fetches and stores the server's ciphertext and sealed DEK, replacing the cached version. A
     * pending edit is left alone — its base still names the version it was written against, so a
     * newer server copy is still caught as a conflict. Decrypted once in memory, to prove this device
     * can read the blob before it is cached.
     */
    suspend fun download(item: NoteItem, reason: CacheReason = CacheReason.PINNED) {
        val (ciphertext, sealed) = content.downloadEncrypted(item)
        val plaintext = withContext(Dispatchers.Default) { decrypt(ciphertext, unseal(sealed)) }
        var serverModifiedAt = item.modifiedAt.toEpochMilli()
        var serverContentVersion = item.contentVersion
        runCatching { content.fileInfo(item.id) }.getOrNull()?.let {
            serverModifiedAt = it.updatedAt.toEpochMilli()
            serverContentVersion = it.contentVersion
        }
        withContext(Dispatchers.IO) { write(ciphertext, base(item.id)) }
        val existing = note(item.id)
        val now = System.currentTimeMillis()
        val size = plaintext.toByteArray().size.toLong()
        val note = (existing ?: OfflineNote(item.id, item.name, item.parentId, item.mimeType, sealed.sealed, sealed.keyVersion,
            serverModifiedAt, serverContentVersion, now, size, cacheReason = reason, isShared = item.isShared)).copy(
            name = item.name,
            parentId = item.parentId,
            mimeType = item.mimeType ?: existing?.mimeType,
            sealedDek = sealed.sealed,
            keyVersion = sealed.keyVersion,
            serverModifiedAt = serverModifiedAt,
            serverContentVersion = serverContentVersion,
            cachedAt = now,
            // A pinned note is never downgraded to an evictable reason by a background refresh.
            cacheReason = if (existing?.cacheReason == CacheReason.PINNED) CacheReason.PINNED else reason,
            sizeBytes = if (existing?.pendingEdit == null) size else existing.sizeBytes,
        )
        upsert(note)
    }

    /** Refuses to discard unsynced work. */
    fun remove(id: String) {
        val note = note(id) ?: throw OfflineException.NotCached()
        if (note.pendingEdit != null) throw OfflineException.HasPendingEdit()
        deleteBlobs(id)
        _notes.value = _notes.value.filter { it.id != id }
        saveIndex()
    }

    /** Drops the automatic entries, keeping pinned ones and anything unsynced. */
    fun evictAutomaticEntries() {
        val evictable = _notes.value.filter { it.cacheReason.isEvictable && it.pendingEdit == null }
        evictable.forEach { deleteBlobs(it.id) }
        val ids = evictable.map { it.id }.toSet()
        _notes.value = _notes.value.filter { it.id !in ids }
        saveIndex()
    }

    /** Sign-out: nothing of the account may outlive its session — queued work included. */
    fun wipe() {
        directory.listFiles()?.forEach { it.delete() }
        _notes.value = emptyList()
        _mutations.value = emptyList()
    }

    // ── Plaintext, in memory only ───────────────────────────────────────────────────────────────

    /** Decrypts for reading or editing; a pending edit wins over the cached version. Returns the DEK too. */
    suspend fun readPlaintext(id: String): Pair<String, ByteArray> = withContext(Dispatchers.Default) {
        val note = note(id) ?: throw OfflineException.NotCached()
        val dek = unseal(SealedFileKey(note.sealedDek, note.keyVersion))
        // A missing pending blob (an interrupted write) falls back to the base: a stale read beats a failure.
        val file = pending(id).takeIf { note.pendingEdit != null && it.exists() } ?: base(id)
        if (!file.exists()) throw OfflineException.NotCached()
        decrypt(file.readBytes(), dek) to dek
    }

    /** Stores an edit as the pending edit, encrypted under the note's DEK. A chain of offline edits keeps the base it started from. */
    suspend fun writePendingEdit(text: String, id: String, dek: ByteArray) {
        val note = note(id) ?: throw OfflineException.NotCached()
        withContext(Dispatchers.Default) { write(content.encryptText(text, dek), pending(id)) }
        upsert(note.copy(
            pendingEdit = PendingEdit(
                editedAt = System.currentTimeMillis(),
                baseServerModifiedAt = note.pendingEdit?.baseServerModifiedAt ?: note.serverModifiedAt,
                baseContentVersion = note.pendingEdit?.baseContentVersion ?: note.serverContentVersion,
            ),
            sizeBytes = text.toByteArray().size.toLong(),
        ))
    }

    // ── Sync-engine callbacks ───────────────────────────────────────────────────────────────────

    /** Replaces the cached version with newer server ciphertext; pending edits and conflicts are the caller's. */
    fun acceptServerVersion(ciphertext: ByteArray, id: String, serverModifiedAt: Long, sizeBytes: Long, serverContentVersion: Long? = null) {
        val note = note(id) ?: throw OfflineException.NotCached()
        write(ciphertext, base(id))
        // Null means "unchanged", never "unknown": clobbering a known version would disarm the guard.
        upsert(note.copy(serverModifiedAt = serverModifiedAt, serverContentVersion = serverContentVersion ?: note.serverContentVersion,
            sizeBytes = sizeBytes, cachedAt = System.currentTimeMillis()))
    }

    /** Refreshes the cached version from what this device just uploaded, re-encrypted rather than re-downloaded. */
    fun cacheLocalVersion(text: String, id: String, dek: ByteArray, serverModifiedAt: Long, serverContentVersion: Long?) {
        if (note(id) == null) throw OfflineException.NotCached()
        acceptServerVersion(content.encryptText(text, dek), id, serverModifiedAt, text.toByteArray().size.toLong(), serverContentVersion)
    }

    /** After a successful upload: the pending blob becomes the cached version and the conflict flag clears. */
    fun clearPendingEdit(id: String, serverModifiedAt: Long, serverContentVersion: Long?) {
        val note = note(id) ?: throw OfflineException.NotCached()
        if (pending(id).exists()) {
            write(pending(id).readBytes(), base(id))
            pending(id).delete()
        }
        upsert(note.copy(pendingEdit = null, conflict = null, serverModifiedAt = serverModifiedAt,
            serverContentVersion = serverContentVersion ?: note.serverContentVersion, cachedAt = System.currentTimeMillis()))
    }

    /**
     * After a *metadata-only* write (rename, star), which bumps `updated_at` without touching content:
     * moves the base forward so the next drain does not report a conflict with the user's own rename.
     * Skipped when the cache already knows a version past the one the write was made against.
     */
    fun rebasePendingEdit(id: String, previousModifiedAt: Long, serverModifiedAt: Long) {
        val note = note(id) ?: return
        if (serverModifiedAt <= note.serverModifiedAt || note.serverModifiedAt > previousModifiedAt) return
        upsert(note.copy(serverModifiedAt = serverModifiedAt, pendingEdit = note.pendingEdit?.copy(baseServerModifiedAt = serverModifiedAt)))
    }

    fun recordFailure(id: String, error: String) {
        val note = note(id) ?: return
        val edit = note.pendingEdit ?: return
        upsert(note.copy(pendingEdit = edit.copy(attemptCount = edit.attemptCount + 1, lastError = error, lastAttemptAt = System.currentTimeMillis())))
    }

    fun markConflict(id: String, serverModifiedAt: Long, serverContentVersion: Long?) {
        val note = note(id) ?: return
        upsert(note.copy(conflict = Conflict(System.currentTimeMillis(), serverModifiedAt, serverContentVersion)))
    }

    // ── Conflicts ───────────────────────────────────────────────────────────────────────────────

    /** Keeps the local edit: rebased onto the newer server version so the next drain overwrites it on purpose. */
    fun resolveKeepingLocal(id: String) {
        val note = note(id) ?: throw OfflineException.NotCached()
        val edit = note.pendingEdit ?: throw OfflineException.NotCached()
        val conflict = note.conflict
        upsert(note.copy(
            serverModifiedAt = maxOf(note.serverModifiedAt, conflict?.serverModifiedAt ?: 0),
            pendingEdit = edit.copy(
                baseServerModifiedAt = conflict?.serverModifiedAt ?: note.serverModifiedAt,
                baseContentVersion = conflict?.serverContentVersion ?: note.serverContentVersion,
                attemptCount = 0, lastError = null, lastAttemptAt = null,
            ),
            conflict = null,
        ))
    }

    /** Keeps the server's version: the local edit is discarded and the note downloaded again. */
    suspend fun resolveKeepingServer(id: String) {
        val note = note(id) ?: throw OfflineException.NotCached()
        pending(id).delete()
        upsert(note.copy(pendingEdit = null, conflict = null))
        download(note.asNoteItem, note.cacheReason)
    }

    // ── Mutation queue ──────────────────────────────────────────────────────────────────────────

    /** Superseded entries go: "rename then delete" is one round trip, not two that can strand the queue. */
    fun enqueue(mutation: PendingMutation) {
        _mutations.value = (_mutations.value.filter { !mutation.supersedes(it) } + mutation).sortedBy { it.queuedAt }
        saveQueue()
    }

    fun removeMutation(id: String) {
        _mutations.value = _mutations.value.filter { it.id != id }
        saveQueue()
    }

    fun recordMutationFailure(id: String, error: String) {
        _mutations.value = _mutations.value.map {
            if (it.id == id) it.copy(attemptCount = it.attemptCount + 1, lastError = error, lastAttemptAt = System.currentTimeMillis()) else it
        }
        saveQueue()
    }

    /** For a queue wedged behind a change the server will never accept. */
    fun clearMutationQueue() {
        _mutations.value = emptyList()
        saveQueue()
    }

    fun drainableMutations(now: Long = System.currentTimeMillis()): List<PendingMutation> =
        _mutations.value.filter { now >= it.nextAttemptAt }.sortedBy { it.queuedAt }

    // ── Metadata mirroring ──────────────────────────────────────────────────────────────────────

    fun updateName(id: String, name: String) {
        val note = note(id) ?: return
        if (note.name != name) upsert(note.copy(name = name))
    }

    fun updateParent(id: String, parentId: String?) {
        val note = note(id) ?: return
        if (note.parentId != parentId) upsert(note.copy(parentId = parentId))
    }

    // ── Plumbing ────────────────────────────────────────────────────────────────────────────────

    /** A missing key is its own error; a missing *version* stays a decrypt failure — the key may yet arrive. */
    private fun unseal(sealed: SealedFileKey): ByteArray = try {
        content.unsealDek(sealed)
    } catch (e: KeyException.NoEncryptionKey) {
        throw e
    } catch (e: Exception) {
        throw OfflineException.DecryptionFailed()
    }

    private fun decrypt(data: ByteArray, dek: ByteArray): String = try {
        content.decryptText(data, dek)
    } catch (e: Exception) {
        throw OfflineException.DecryptionFailed()
    }

    private fun base(id: String) = File(directory, "${safe(id)}.bin")
    private fun pending(id: String) = File(directory, "${safe(id)}.pending.bin")

    /** Ids are UUIDs; anything else is not allowed to name a path. */
    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }

    /** Written to a temporary file and renamed, so a crash mid-write never leaves half a blob. */
    private fun write(data: ByteArray, file: File) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun deleteBlobs(id: String) {
        base(id).delete()
        pending(id).delete()
    }

    private fun upsert(note: OfflineNote) {
        _notes.value = sorted(_notes.value.filter { it.id != note.id } + note)
        saveIndex()
    }

    private fun sorted(notes: List<OfflineNote>) = notes.sortedWith { a, b -> collator.compare(a.displayName, b.displayName).takeIf { it != 0 } ?: a.id.compareTo(b.id) }

    private fun saveIndex() {
        runCatching { write(json.encodeToString(ListSerializer(OfflineNote.serializer()), _notes.value).toByteArray(), File(directory, INDEX)) }
            .onFailure { log.warning("saveIndex failed: ${it.javaClass.simpleName}") }
    }

    /** Best effort: a queue that fails to persist still works this session. */
    private fun saveQueue() {
        runCatching { write(json.encodeToString(ListSerializer(PendingMutation.serializer()), _mutations.value).toByteArray(), File(directory, QUEUE)) }
    }

    companion object {
        private const val INDEX = "index.json"
        private const val QUEUE = "queue.json"
    }
}
