package com.neutrino.notes.core.model

import com.neutrino.notes.core.markdown.WikiLink
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.Collator
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.math.pow

/** A folder or a Markdown note in the user's Drive. */
data class NoteItem(
    val id: String,
    val name: String,
    val type: ItemType,
    /** Null is the root of the drive. */
    val parentId: String?,
    val size: Long? = null,
    val modifiedAt: Instant,
    val isTrashed: Boolean = false,
    val mimeType: String? = null,
    /** Drive's `isStarred` — the Favorites model the web shares, stored on the row itself. */
    val isStarred: Boolean = false,
    /** Drive's `contentVersion` as last heard; sent back as `expectedContentVersion` so the server can refuse a stale write. */
    val contentVersion: Long? = null,
    /** Reached through `shared-with-me`: owned by somebody else. Everything else is owner-scoped server-side. */
    val isShared: Boolean = false,
) {
    enum class ItemType { FOLDER, FILE }

    val isFolder: Boolean get() = type == ItemType.FOLDER

    /** Whether this app opens the body: the listings ask for `type=note`, but a link can name anything. */
    val isNote: Boolean get() = type == ItemType.FILE && isMarkdown(mimeType)

    /** `Meeting.md` is called "Meeting", as a `[[Meeting]]` link spells it. */
    val displayName: String get() = if (isFolder) name else WikiLink.displayTitle(name)

    companion object {
        /**
         * The only file type this app browses. A note *is* a Markdown document: the standard media
         * type, the same value the server's `type=note` filter matches on.
         */
        const val MARKDOWN_MIME = "text/markdown"

        fun isMarkdown(mimeType: String?) = mimeType == MARKDOWN_MIME
    }
}

/** The browser's sections. */
enum class NotesSection(val title: String) {
    MY_NOTES("My Notes"), RECENT("Recent"), FAVORITES("Favorites"), SHARED("Shared"), TRASH("Trash");

    /** Only My Notes navigates folders: Recent and Favorites cut across the tree, and someone else's folders cannot be listed. */
    val supportsFolderNavigation: Boolean get() = this == MY_NOTES

    /** Recent is newest-first by definition; everywhere else a browser reads A–Z. */
    val defaultSortOrder: NoteSortOrder get() = if (this == RECENT) NoteSortOrder.NEWEST else NoteSortOrder.NAME_ASCENDING

    val emptyMessage: String
        get() = when (this) {
            MY_NOTES -> "No notes yet. Tap + to create one."
            RECENT -> "Notes you open or edit will appear here."
            FAVORITES -> "Star a note to keep it here."
            SHARED -> "Notes other people share with you will appear here."
            TRASH -> "Trash is empty."
        }
}

/** How a listing is ordered; stored by name so it survives reordering. */
enum class NoteSortOrder(val title: String) {
    NEWEST("Newest First"), OLDEST("Oldest First"), NAME_ASCENDING("Name (A–Z)"), NAME_DESCENDING("Name (Z–A)");

    /** Folders ahead of notes in every order; by the title the row shows; ties by name then id, so a reload cannot shuffle. */
    fun sorted(items: List<NoteItem>): List<NoteItem> {
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }
        return items.sortedWith { lhs, rhs ->
            if (lhs.type != rhs.type) return@sortedWith if (lhs.isFolder) -1 else 1
            if (this == NEWEST && lhs.modifiedAt != rhs.modifiedAt) return@sortedWith rhs.modifiedAt.compareTo(lhs.modifiedAt)
            if (this == OLDEST && lhs.modifiedAt != rhs.modifiedAt) return@sortedWith lhs.modifiedAt.compareTo(rhs.modifiedAt)
            val byName = collator.compare(lhs.displayName, rhs.displayName)
            if (byName != 0) return@sortedWith if (this == NAME_DESCENDING) -byName else byName
            lhs.id.compareTo(rhs.id)
        }
    }
}

/** What an account may do with a note or folder, as Drive's permissions API defines it. */
enum class ShareRole(val title: String, val summary: String) {
    OWNER("Owner", "Can edit and manage sharing"),
    EDITOR("Editor", "Can edit this note"),
    COMMENTER("Commenter", "Can read this note"),
    VIEWER("Viewer", "Can read this note");

    /** Autosave accepts `owner` or `editor`. A commenter reads: this app has no comment UI. */
    val canEdit: Boolean get() = this == OWNER || this == EDITOR

    val wire: String get() = name.lowercase()

    companion object {
        /** `owner` is granted only through transfer-ownership, which the server keeps separate. */
        val grantable = listOf(EDITOR, COMMENTER, VIEWER)

        /** An unknown role reads as the least privileged: read-only is the safe misreading. */
        fun of(server: String?): ShareRole = entries.firstOrNull { it.wire == server?.lowercase() } ?: VIEWER
    }
}

/** `/files/{id}/info`: the one endpoint that answers for a file the caller can *access*, not only one it owns. */
data class NoteFileInfo(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val folderId: String?,
    val mimeType: String?,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    val yourRole: ShareRole,
    val contentVersion: Long?,
) {
    /** Still somewhere an edit can land. */
    val isLive: Boolean get() = deletedAt == null
}

/** One snapshot in a note's version history. */
data class NoteVersion(
    val id: String,
    val fileId: String,
    val versionNumber: Int,
    val sizeBytes: Long,
    val label: String?,
    val createdAt: Instant,
    val isNamed: Boolean,
) {
    val displayTitle: String get() = if (label.isNullOrBlank()) "Version $versionNumber" else "$label (v$versionNumber)"
    val shortTitle: String get() = "v$versionNumber"
}

/** A device registered against the account — the session row signing in writes. */
data class DeviceSession(
    val id: String,
    val deviceName: String?,
    val userAgent: String?,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val isCurrentDevice: Boolean,
)

/** A Drive tag. Its *name* is stored in the clear, like a file's; note bodies stay encrypted. */
data class NoteTag(val id: String, val name: String, val createdAt: Instant, val fileCount: Int = 0) {
    companion object {
        /** Case-insensitive by name, the id as a tiebreaker so the order is total. */
        val byName: Comparator<NoteTag> = Comparator { a, b ->
            a.name.compareTo(b.name, ignoreCase = true).takeIf { it != 0 } ?: a.id.compareTo(b.id)
        }
    }
}

/**
 * One end of a link in Drive's cross-file link graph. The graph is file-type-agnostic: a note can
 * be linked from a doc, and is shown as one rather than pretending everything is a note.
 */
data class FileLink(val id: String, val title: String, val fileType: String) {
    val isNote: Boolean get() = fileType == "note"
    val displayTitle: String get() = WikiLink.displayTitle(title)
}

/** A Neutrino account, as the directory returns it. */
data class DirectoryUser(val id: String, val email: String, val name: String) {
    val displayName: String get() = name.ifBlank { email }
}

/** One person's access to one file or folder. */
data class SharePermission(val id: String, val userId: String, val userEmail: String, val userName: String, val role: ShareRole) {
    /** Guest permissions from a share link carry an empty email and "Guest"; neither field can be assumed. */
    val displayName: String get() = userName.ifBlank { userEmail.ifBlank { "Unknown user" } }
    val displayDetail: String? get() = userEmail.takeIf { it.isNotBlank() && it != displayName }

    companion object {
        /** Owner first, then by privilege, then by name — stable via the id so the list never jitters. */
        val byRoleThenName: Comparator<SharePermission> = Comparator { a, b ->
            a.role.ordinal.compareTo(b.role.ordinal).takeIf { it != 0 }
                ?: a.displayName.compareTo(b.displayName, ignoreCase = true).takeIf { it != 0 }
                ?: a.id.compareTo(b.id)
        }
    }
}

/** Word and character counts and the reading time the status bar shows. */
data class NoteTextStats(val wordCount: Int, val characterCount: Int, val readingTimeMinutes: Int) {
    val readingTimeText: String get() = if (readingTimeMinutes <= 1) "1 min read" else "$readingTimeMinutes min read"

    val summaryText: String
        get() = "${if (wordCount == 1) "1 word" else "$wordCount words"} · " +
            "${if (characterCount == 1) "1 character" else "$characterCount characters"} · $readingTimeText"

    companion object {
        /** 200 wpm, the low end of the usual range: an estimate that runs long is the kinder error. */
        fun compute(text: String): NoteTextStats {
            val words = text.split(Regex("\\s+")).count { it.isNotEmpty() }
            val minutes = if (words == 0) 0 else maxOf(1, kotlin.math.ceil(words / 200.0).toInt())
            return NoteTextStats(words, text.codePointCount(0, text.length), minutes)
        }
    }
}

// ── Offline cache ───────────────────────────────────────────────────────────────────────────────

/** Why a note is cached. Pinned ones are the user's choice and are never evicted automatically. */
@Serializable
enum class CacheReason(val title: String) {
    @SerialName("pinned") PINNED("Kept offline"),
    @SerialName("opened") OPENED("Opened"),
    @SerialName("recent") RECENT("Recent"),
    @SerialName("favorite") FAVORITE("Favorite");

    val isEvictable: Boolean get() = this != PINNED
}

/** Exponential retry backoff: 0s, 5s, 15s, 45s, 135s, … capped at 300s. Pure — no clock. */
object SyncBackoff {
    const val MAXIMUM_DELAY_SECONDS = 300.0

    fun delaySeconds(attempt: Int): Double {
        if (attempt <= 0) return 0.0
        return minOf(5 * 3.0.pow(attempt - 1), MAXIMUM_DELAY_SECONDS)
    }
}

/** A body edit not yet on the server. Times are epoch milliseconds. */
@Serializable
data class PendingEdit(
    val editedAt: Long,
    /** The server version this edit was written against; a server past it is a conflict. */
    val baseServerModifiedAt: Long,
    /** The same base as Drive's `contentVersion` — what the server is asked to guard the write with. */
    val baseContentVersion: Long? = null,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val lastAttemptAt: Long? = null,
) {
    /** Measured from the last failure, so an old edit is still held back between attempts. */
    val nextAttemptAt: Long get() = (lastAttemptAt ?: editedAt) + (SyncBackoff.delaySeconds(attemptCount) * 1000).toLong()
}

/** The server moved past the version a pending edit was based on. */
@Serializable
data class Conflict(val detectedAt: Long, val serverModifiedAt: Long, val serverContentVersion: Long? = null)

/**
 * A note cached for offline use: `<id>.bin` is the server's ciphertext byte for byte and
 * `<id>.pending.bin` a local edit under the same DEK. Nothing is stored as plaintext.
 */
@Serializable
data class OfflineNote(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val mimeType: String? = null,
    /** base64url `crypto_box_seal`, exactly as `/files/{id}/key` returned it. */
    val sealedDek: String,
    /** The identity version it is sealed to — an offline read cannot ask the server. */
    val keyVersion: Int = 1,
    val serverModifiedAt: Long,
    val serverContentVersion: Long? = null,
    val cachedAt: Long,
    /** Plaintext size, for display only. */
    val sizeBytes: Long,
    val pendingEdit: PendingEdit? = null,
    val conflict: Conflict? = null,
    val cacheReason: CacheReason = CacheReason.OPENED,
    /** Reached through `shared-with-me`, so a cached copy still opens read-only when the role is unknown. */
    val isShared: Boolean = false,
) {
    val displayName: String get() = WikiLink.displayTitle(name)

    val asNoteItem: NoteItem
        get() = NoteItem(
            id = id, name = name, type = NoteItem.ItemType.FILE, parentId = parentId, size = sizeBytes,
            modifiedAt = Instant.ofEpochMilli(serverModifiedAt), mimeType = mimeType ?: NoteItem.MARKDOWN_MIME,
            contentVersion = serverContentVersion, isShared = isShared,
        )
}

/** A metadata change made offline, replayed in order when the connection is back. */
@Serializable
data class PendingMutation(
    val id: String = UUID.randomUUID().toString(),
    val itemId: String,
    val kind: Kind,
    val isFolder: Boolean,
    /** For a move; null is the drive root. */
    val targetFolderId: String? = null,
    /** For a rename. */
    val newName: String? = null,
    val queuedAt: Long,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val lastAttemptAt: Long? = null,
) {
    @Serializable
    enum class Kind { @SerialName("delete") DELETE, @SerialName("move") MOVE, @SerialName("rename") RENAME }

    val nextAttemptAt: Long get() = (lastAttemptAt ?: queuedAt) + (SyncBackoff.delaySeconds(attemptCount) * 1000).toLong()

    val summary: String
        get() = when (kind) {
            Kind.DELETE -> "Delete"
            Kind.MOVE -> if (targetFolderId == null) "Move to My Notes" else "Move"
            Kind.RENAME -> "Rename to ${newName?.let(WikiLink::displayTitle)}"
        }

    /** A delete makes an earlier move or rename pointless; two moves or two renames collapse to the last. */
    fun supersedes(other: PendingMutation): Boolean {
        if (other.itemId != itemId) return false
        return kind == Kind.DELETE || kind == other.kind
    }
}
