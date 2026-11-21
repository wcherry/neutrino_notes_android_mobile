package com.neutrino.notes.core.service

import com.neutrino.notes.core.api.NotesApi
import com.neutrino.notes.core.model.DirectoryUser
import com.neutrino.notes.core.model.NoteItem
import com.neutrino.notes.core.model.SharePermission
import com.neutrino.notes.core.model.ShareRole
import com.neutrino.shared.core.crypto.FileCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class SharingException(message: String) : Exception(message) {
    class UserNotFound(email: String) : SharingException("No Neutrino account found for “$email”.")
    class CannotShareWithYourself : SharingException("You already own this — there is nothing to share with yourself.")
}

/**
 * Shares notes and folders with other Neutrino accounts. Sharing an end-to-end encrypted note is
 * two operations, and both have to happen:
 *
 *  1. `POST /drive/{files,folders}/{id}/permissions` — the recipient may fetch the bytes.
 *  2. The note's DEK, unsealed here and re-sealed to the recipient's public key, handed to
 *     `POST /drive/files/{id}/key/share` — the recipient may read them.
 *
 * Step 2 is impossible for someone with no published key; that is reported, because the only fix
 * is for them to set one up and for the owner to send the key again. The grant goes first: a failed
 * key share is recoverable with Send Key, a failed grant means no access at all.
 */
class SharingService(
    private val api: NotesApi,
    private val content: NoteContentService,
    private val scope: CoroutineScope,
    private val currentUserId: () -> String?,
) {
    /** What a share achieved. A folder touches every note inside, and each may fail on its own. */
    data class ShareResult(var notesShared: Int = 0, var notesFailed: Int = 0, var keysDelivered: Int = 0, var recipientHasNoKey: Boolean = false) {
        val summary: String?
            get() = when {
                recipientHasNoKey -> "Shared, but this person hasn't set up encryption keys yet, so they can't read it. Send the key once they have."
                notesFailed > 0 -> "Shared $notesShared of ${notesShared + notesFailed} notes — the rest couldn't be shared."
                notesShared > 1 -> "Shared $notesShared notes in this folder."
                else -> null
            }
    }

    enum class KeyShare { DELIVERED, RECIPIENT_HAS_NO_KEY, FILE_PLAINTEXT }

    /** Permissions per item id; only ever populated for items this account owns (others get a 403). */
    val permissions = MutableStateFlow<Map<String, List<SharePermission>>>(emptyMap())
    /** Whether each user seen this session has a published key. */
    val hasKey = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val error = MutableStateFlow<String?>(null)

    private val publicKeys = HashMap<String, Pair<String, Int>>()

    fun permissions(item: NoteItem): List<SharePermission> = permissions.value[item.id].orEmpty()

    suspend fun loadPermissions(item: NoteItem) {
        try {
            val list = api.permissions(item.id, item.isFolder).sortedWith(SharePermission.byRoleThenName)
            permissions.update { it + (item.id to list) }
            for (p in list) if (p.role != ShareRole.OWNER && p.userId !in hasKey.value) runCatching { publicKey(p.userId) }
        } catch (e: Exception) {
            error.value = e.message
        }
    }

    suspend fun lookupUser(email: String): DirectoryUser {
        val trimmed = email.trim()
        val user = api.lookupUser(trimmed) ?: throw SharingException.UserNotFound(trimmed)
        return DirectoryUser(user.id, user.email, user.name)
    }

    /** Type-ahead; fewer than two characters is answered locally rather than asking for everybody. */
    suspend fun searchUsers(query: String): List<DirectoryUser> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        return runCatching { api.searchUsers(trimmed) }.getOrDefault(emptyList())
    }

    suspend fun share(item: NoteItem, user: DirectoryUser, role: ShareRole): ShareResult {
        if (user.id == currentUserId()) throw SharingException.CannotShareWithYourself()
        val granted = api.grant(item.id, item.isFolder, user, role)
        permissions.update { map -> map + (item.id to (map[item.id].orEmpty().filter { it.userId != granted.userId } + granted).sortedWith(SharePermission.byRoleThenName)) }
        return if (item.isFolder) applyToFolder(item.id, user, role) else ShareResult(notesShared = 1).also { record(it, shareKey(item.id, user.id)) }
    }

    /**
     * Grants the role on every note in the folder and re-wraps each key: the folder grant gives
     * inherited access to what is added later, but Drive's listings are owner-scoped, so without
     * per-note grants the recipient would see a folder they cannot open.
     */
    private suspend fun applyToFolder(folderId: String, user: DirectoryUser, role: ShareRole): ShareResult {
        val result = ShareResult()
        for (noteId in noteIds(folderId)) {
            try {
                api.grant(noteId, false, user, role)
                result.notesShared++
                record(result, shareKey(noteId, user.id))
            } catch (e: Exception) {
                result.notesFailed++
            }
        }
        return result
    }

    /** Re-sends the key — one note, or every note in a folder. Fixes "shared before they set up their keys". */
    suspend fun sendKey(item: NoteItem, permission: SharePermission): ShareResult {
        publicKeys.remove(permission.userId)
        hasKey.update { it - permission.userId }
        if (item.isFolder) return applyToFolder(item.id, DirectoryUser(permission.userId, permission.userEmail, permission.userName), permission.role)
        return ShareResult(notesShared = 1).also { record(it, shareKey(item.id, permission.userId)) }
    }

    fun updateRole(item: NoteItem, permission: SharePermission, role: ShareRole) {
        if (permission.role == role) return
        setRole(item.id, permission.userId, role)
        scope.launch {
            try {
                api.updateRole(item.id, item.isFolder, permission.userId, role)
            } catch (e: Exception) {
                setRole(item.id, permission.userId, permission.role)
                error.value = e.message
            }
        }
    }

    fun revoke(item: NoteItem, permission: SharePermission) {
        val previous = permissions.value[item.id].orEmpty()
        permissions.update { it + (item.id to previous.filter { p -> p.userId != permission.userId }) }
        scope.launch {
            try {
                api.revoke(item.id, item.isFolder, permission.userId)
            } catch (e: Exception) {
                permissions.update { it + (item.id to previous) }
                error.value = e.message
            }
        }
    }

    /** Unseals a note's DEK with this device's key and re-seals it to the recipient's. The server never sees the DEK. */
    suspend fun shareKey(fileId: String, recipientId: String): KeyShare {
        val (recipientKey, recipientVersion) = publicKey(recipientId) ?: return KeyShare.RECIPIENT_HAS_NO_KEY
        val mine = content.fetchSealedKey(fileId) ?: return KeyShare.FILE_PLAINTEXT
        val dek = content.unsealDek(mine, fileId)
        api.shareFileKey(fileId, recipientId, FileCrypto.sealDek(dek, recipientKey), recipientVersion)
        return KeyShare.DELIVERED
    }

    /** A user's active public key and version, or null when they have none; cached, the "none" answer too. */
    suspend fun publicKey(userId: String): Pair<String, Int>? {
        publicKeys[userId]?.let { return it }
        if (hasKey.value[userId] == false) return null
        val key = api.publishedKey(userId)
        hasKey.update { it + (userId to (key != null)) }
        return key?.let { (it.publicKey to it.version).also { pair -> publicKeys[userId] = pair } }
    }

    /** Every note under a folder, its subfolders included; depth-capped and cycle-safe. */
    suspend fun noteIds(folderId: String): List<String> {
        val out = ArrayList<String>()
        val visited = HashSet<String>()
        val frontier = ArrayDeque(listOf(folderId to 0))
        while (frontier.isNotEmpty()) {
            val (id, depth) = frontier.removeLast()
            if (depth >= MAX_FOLDER_DEPTH || !visited.add(id)) continue
            val listing = api.noteFolder(id)
            out += listing.files.filter { NoteItem.isMarkdown(it.mimeType) }.map { it.id }
            listing.folders.forEach { frontier += it.id to depth + 1 }
        }
        return out
    }

    fun reset() {
        permissions.value = emptyMap()
        hasKey.value = emptyMap()
        publicKeys.clear()
    }

    private fun record(result: ShareResult, outcome: KeyShare) {
        when (outcome) {
            KeyShare.DELIVERED -> result.keysDelivered++
            KeyShare.RECIPIENT_HAS_NO_KEY -> result.recipientHasNoKey = true
            KeyShare.FILE_PLAINTEXT -> Unit
        }
    }

    private fun setRole(itemId: String, userId: String, role: ShareRole) {
        permissions.update { map ->
            map + (itemId to map[itemId].orEmpty().map { if (it.userId == userId) it.copy(role = role) else it }.sortedWith(SharePermission.byRoleThenName))
        }
    }

    companion object {
        /** Drive's own permission inheritance walks 50; a share walk is bounded tighter. */
        const val MAX_FOLDER_DEPTH = 20
    }
}
