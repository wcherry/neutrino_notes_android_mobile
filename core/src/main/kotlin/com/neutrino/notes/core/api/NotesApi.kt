package com.neutrino.notes.core.api

import com.neutrino.notes.core.model.DeviceSession
import com.neutrino.notes.core.model.DirectoryUser
import com.neutrino.notes.core.model.FileLink
import com.neutrino.notes.core.model.NoteFileInfo
import com.neutrino.notes.core.model.NoteItem
import com.neutrino.notes.core.model.NoteTag
import com.neutrino.notes.core.model.NoteVersion
import com.neutrino.notes.core.model.SharePermission
import com.neutrino.notes.core.model.ShareRole
import com.neutrino.shared.core.api.ApiException
import com.neutrino.shared.core.api.AuthClient
import com.neutrino.shared.core.api.DriveDate
import com.neutrino.shared.core.api.FileDto
import com.neutrino.shared.core.api.FolderContentsDto
import com.neutrino.shared.core.api.FolderDto
import com.neutrino.shared.core.api.NeutrinoApi
import com.neutrino.shared.core.api.apiJson
import com.neutrino.shared.core.api.httpError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

/**
 * Notes has no backend of its own: everything is Drive's folder, file, trash, version, tag and
 * permission APIs, the link graph and the account directory. Every listing passes `type=note`, so
 * the server returns only Markdown; folders come back unfiltered, because a folder may hold notes
 * whatever else is in it.
 */
class NotesApi(http: OkHttpClient, auth: AuthClient) : NeutrinoApi(http, auth) {

    // ── Listings ────────────────────────────────────────────────────────────────────────────────

    /** One folder's folders and notes; null is the drive root, whose id is the user's. */
    suspend fun noteFolder(folderId: String?): FolderContentsDto {
        val id = folderId ?: rootFolderId()
        return live(json(FolderContentsDto.serializer(), get(url("/drive/folders/$id").newBuilder().addQueryParameter("type", NOTE_TYPE).build())))
    }

    suspend fun recent(limit: Int = 50): FolderContentsDto =
        live(json(FolderContentsDto.serializer(), get(url("/drive/recent").newBuilder().addQueryParameter("type", NOTE_TYPE).addQueryParameter("limit", limit.toString()).build())))

    suspend fun starred(): FolderContentsDto =
        live(json(FolderContentsDto.serializer(), get(url("/drive/starred").newBuilder().addQueryParameter("type", NOTE_TYPE).build())))

    suspend fun sharedWithMe(): FolderContentsDto =
        live(json(FolderContentsDto.serializer(), get(url("/drive/shared-with-me").newBuilder().addQueryParameter("type", NOTE_TYPE).build())))

    suspend fun trash(): TrashContentsDto =
        json(TrashContentsDto.serializer(), get(url("/drive/trash").newBuilder().addQueryParameter("type", NOTE_TYPE).build()))

    private fun live(listing: FolderContentsDto) = listing.copy(files = listing.files.filter { it.deletedAt.isNullOrEmpty() })

    // ── One file ────────────────────────────────────────────────────────────────────────────────

    /** `/files/{id}/info`, with the caller's role. Null when the file is gone (404) or not shared with this account (403). */
    suspend fun fileInfo(fileId: String): NoteFileInfo? {
        val dto = try {
            json(FileInfoDto.serializer(), get("/drive/files/$fileId/info"))
        } catch (e: ApiException.Http) {
            if (e.status == 404 || e.status == 403) return null
            throw e
        }
        return NoteFileInfo(
            id = dto.id, name = dto.name, sizeBytes = dto.sizeBytes ?: 0, folderId = dto.folderId, mimeType = dto.mimeType,
            updatedAt = DriveDate.parse(dto.updatedAt) ?: Instant.EPOCH, deletedAt = DriveDate.parse(dto.deletedAt),
            // Absent means the caller owns it: `/info` omits the field for a file in their own drive.
            yourRole = ShareRole.of(dto.yourRole ?: "owner"), contentVersion = dto.contentVersion,
        )
    }

    // ── Organising ──────────────────────────────────────────────────────────────────────────────

    suspend fun updateFolder(id: String, name: String? = null, isStarred: Boolean? = null): FolderDto =
        json(FolderDto.serializer(), method("PATCH", "/drive/folders/$id", buildJsonObject {
            name?.let { put("name", it) }
            isStarred?.let { put("isStarred", it) }
        }))

    suspend fun updateFile(id: String, name: String? = null, isStarred: Boolean? = null): FileDto =
        json(FileDto.serializer(), method("PATCH", "/drive/files/$id", buildJsonObject {
            name?.let { put("name", it) }
            isStarred?.let { put("isStarred", it) }
        }))

    suspend fun bulkTrash(fileIds: List<String>, folderIds: List<String>) {
        text(method("POST", "/drive/bulk/trash", buildJsonObject {
            put("fileIds", strings(fileIds))
            put("folderIds", strings(folderIds))
        }))
    }

    suspend fun bulkMove(fileIds: List<String>, folderIds: List<String>, targetFolderId: String?) {
        text(method("POST", "/drive/bulk/move", buildJsonObject {
            put("fileIds", strings(fileIds))
            put("folderIds", strings(folderIds))
            put("targetFolderId", targetFolderId?.let(::JsonPrimitive) ?: JsonNull)
        }))
    }

    suspend fun restoreFromTrash(id: String, isFolder: Boolean) {
        text(method("POST", "/drive/trash/${if (isFolder) "folders" else "files"}/$id/restore", null as String?))
    }

    suspend fun deleteForever(id: String, isFolder: Boolean) {
        text(method("DELETE", "/drive/trash/${if (isFolder) "folders" else "files"}/$id", null as String?))
    }

    suspend fun emptyTrash() {
        text(method("DELETE", "/drive/trash", null as String?))
    }

    // ── Content ─────────────────────────────────────────────────────────────────────────────────

    /** A file's stored bytes — ciphertext for an encrypted one. */
    suspend fun download(fileId: String, versionId: String? = null): ByteArray = openBlob(fileId, versionId).use { response ->
        withContext(Dispatchers.IO) { response.body?.bytes() ?: ByteArray(0) }
    }

    /**
     * Creates a file. **Part order is load-bearing**: the server reads scalar fields as it walks the
     * stream and stops at the file part, so every field precedes the blob. The sealed key rides in
     * the same request, so a note can never exist without the key that opens it.
     */
    suspend fun create(ciphertext: ByteArray, fileName: String, folderId: String?, encryptedMetadata: String, sealedFileKey: String, keyVersion: Int): FileDto {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            addFormDataPart("encrypted_metadata", encryptedMetadata)
            folderId?.let { addFormDataPart("folder_id", it) }
            addFormDataPart("encrypted_file_key", sealedFileKey)
            addFormDataPart("key_version", keyVersion.toString())
            addFormDataPart("file", fileName, ciphertext.toRequestBody(MARKDOWN))
        }.build()
        return upload(body)
    }

    /**
     * The autosave endpoint: the same multipart `file` part as create. `expectedContentVersion` makes
     * the server refuse the write atomically if anything landed since. Autosave does not snapshot.
     */
    suspend fun autosave(fileId: String, fileName: String, mimeType: String, ciphertext: ByteArray, expectedContentVersion: Long?): FileDto {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, ciphertext.toRequestBody(mimeType.toMediaTypeOrNull() ?: MARKDOWN)).build()
        val u = url("/drive/files/$fileId/autosave").newBuilder().apply {
            expectedContentVersion?.let { addQueryParameter("expectedContentVersion", it.toString()) }
        }.build()
        return withContext(Dispatchers.IO) {
            send { Request.Builder().url(u).put(body) }.use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code == 409 && isContentVersionConflict(text)) throw ContentVersionConflict(currentVersion(text))
                    throw httpError(response.code, text)
                }
                apiJson.decodeFromString(FileDto.serializer(), text)
            }
        }
    }

    // ── Versions ────────────────────────────────────────────────────────────────────────────────

    suspend fun versions(fileId: String): List<NoteVersion> =
        json(VersionsDto.serializer(), get("/drive/files/$fileId/versions")).versions.map { it.model() }

    /** A named snapshot; the server also makes it the current content, like the web's explicit save. */
    suspend fun saveVersion(fileId: String, fileName: String, mimeType: String, ciphertext: ByteArray, label: String?): NoteVersion {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            label?.let { addFormDataPart("label", it) }
            addFormDataPart("file", fileName, ciphertext.toRequestBody(mimeType.toMediaTypeOrNull() ?: MARKDOWN))
        }.build()
        return json(VersionDto.serializer()) { Request.Builder().url(url("/drive/files/$fileId/versions")).post(body) }.model()
    }

    /** The server snapshots the pre-restore content first, so a restore is itself undoable. */
    suspend fun restoreVersion(fileId: String, versionId: String): FileDto =
        json(FileDto.serializer(), method("POST", "/drive/files/$fileId/versions/$versionId/restore", null as String?))

    // ── Tags ────────────────────────────────────────────────────────────────────────────────────

    suspend fun tags(): List<NoteTag> = json(TagsDto.serializer(), get("/drive/tags")).tags.map { it.model() }

    /** A 409 means the name is taken. */
    suspend fun createTag(name: String): NoteTag =
        json(TagDto.serializer(), method("POST", "/drive/tags", buildJsonObject { put("name", name) })).model()

    suspend fun renameTag(id: String, name: String): NoteTag =
        json(TagDto.serializer(), method("PATCH", "/drive/tags/$id", buildJsonObject { put("name", name) })).model()

    suspend fun deleteTag(id: String) {
        text(method("DELETE", "/drive/tags/$id", null as String?))
    }

    suspend fun fileTags(fileId: String): List<NoteTag> =
        json(kotlinx.serialization.builtins.ListSerializer(TagDto.serializer()), get("/drive/files/$fileId/tags")).map { it.model() }

    /** Idempotent server-side: re-attaching an attached tag is a no-op. */
    suspend fun attachTag(fileId: String, tagId: String) {
        text(method("POST", "/drive/files/$fileId/tags/$tagId", null as String?))
    }

    suspend fun detachTag(fileId: String, tagId: String) {
        text(method("DELETE", "/drive/files/$fileId/tags/$tagId", null as String?))
    }

    /** One page of the notes carrying a tag; `total` counts every accessible note before paging. */
    suspend fun taggedNotes(tagId: String, limit: Int, offset: Int): TaggedFilesDto =
        json(TaggedFilesDto.serializer(), get(url("/drive/tags/$tagId/files").newBuilder()
            .addQueryParameter("limit", limit.toString()).addQueryParameter("offset", offset.toString())
            .addQueryParameter("type", NOTE_TYPE).build()))

    // ── Link graph ──────────────────────────────────────────────────────────────────────────────

    /** What links *to* a file. Read access is enough. */
    suspend fun backlinks(fileId: String): List<FileLink> =
        json(BacklinksDto.serializer(), get("/links/$fileId/backlinks")).backlinks.map { FileLink(it.id, it.title, it.fileType) }

    /**
     * Replaces the titles a file links *out* to; answered with its backlinks. Needs owner or editor.
     * `linkedIds`/`linkedRanges` exist in the server's DTO and answer 400, so they are never sent.
     */
    suspend fun updateLinks(fileId: String, titles: List<String>): List<FileLink> =
        json(BacklinksDto.serializer(), method("PATCH", "/links/$fileId", buildJsonObject { put("linkedTitles", strings(titles)) }))
            .backlinks.map { FileLink(it.id, it.title, it.fileType) }

    // ── Sharing ─────────────────────────────────────────────────────────────────────────────────

    suspend fun permissions(id: String, isFolder: Boolean): List<SharePermission> =
        json(PermissionsDto.serializer(), get("/drive/${resource(isFolder)}/$id/permissions")).permissions.map { it.model() }

    suspend fun grant(id: String, isFolder: Boolean, user: DirectoryUser, role: ShareRole): SharePermission =
        json(PermissionDto.serializer(), method("POST", "/drive/${resource(isFolder)}/$id/permissions", buildJsonObject {
            put("userId", user.id); put("userEmail", user.email); put("userName", user.name); put("role", role.wire)
        })).model()

    suspend fun updateRole(id: String, isFolder: Boolean, userId: String, role: ShareRole) {
        text(method("PATCH", "/drive/${resource(isFolder)}/$id/permissions/$userId", buildJsonObject { put("role", role.wire) }))
    }

    /** The server also deletes their key ref, so the note stops being readable as well as reachable. */
    suspend fun revoke(id: String, isFolder: Boolean, userId: String) {
        text(method("DELETE", "/drive/${resource(isFolder)}/$id/permissions/$userId", null as String?))
    }

    /** Hands the recipient their copy of a DEK, sealed on this device to their public key. */
    suspend fun shareFileKey(fileId: String, recipientId: String, sealed: String, keyVersion: Int) {
        text(method("POST", "/drive/files/$fileId/key/share", buildJsonObject {
            put("recipientId", recipientId); put("encryptedFileKey", sealed); put("keyVersion", keyVersion)
        }))
    }

    /** Type-ahead over the account directory. */
    suspend fun searchUsers(query: String): List<DirectoryUser> =
        json(kotlinx.serialization.builtins.ListSerializer(DirectoryUserDto.serializer()),
            get(url("/auth/users/search").newBuilder().addQueryParameter("q", query).build())).map { DirectoryUser(it.id, it.email, it.name) }

    // ── Devices ─────────────────────────────────────────────────────────────────────────────────

    /** The account's sessions. The server does not mark the caller's own; the registered name is the only join. */
    suspend fun sessions(currentDeviceName: String): List<DeviceSession> =
        json(SessionsDto.serializer(), get("/auth/sessions")).sessions.map {
            DeviceSession(it.id, it.deviceName, it.userAgent, DriveDate.parse(it.createdAt) ?: Instant.EPOCH, DriveDate.parse(it.lastUsedAt),
                isCurrentDevice = it.deviceName == currentDeviceName)
        }

    suspend fun revokeSession(id: String) {
        text(method("DELETE", "/auth/sessions/$id", null as String?))
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    /** The server rejected the write because the note moved on; `current` is its version, when stated. */
    class ContentVersionConflict(val current: Long?) :
        Exception("This note changed on another device since it was opened. Reload to get the latest version, or keep your copy.")

    companion object {
        /** Drive's `type` filter for Markdown notes. */
        const val NOTE_TYPE = "note"
        private val MARKDOWN = NoteItem.MARKDOWN_MIME.toMediaType()

        private fun resource(isFolder: Boolean) = if (isFolder) "folders" else "files"
        private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))

        /** A 409 is a version conflict only when the body says so: Drive uses 409 for other faults too. */
        fun isContentVersionConflict(body: String): Boolean = runCatching {
            apiJson.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content == "CONTENT_VERSION_CONFLICT"
        }.getOrDefault(false)

        /** The server states the version it holds in the message: "… found 7". A nicety; callers tolerate null. */
        fun currentVersion(body: String): Long? = runCatching {
            val message = apiJson.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: return null
            message.substringAfter("found ", "").takeWhile { it.isDigit() }.toLongOrNull()
        }.getOrNull()
    }
}

// Shapes from `neutrino/src/drive/storage/dto.rs`, `src/links/`, and `auth/dto.rs`; camelCase on the wire.

@Serializable
data class FileInfoDto(
    val id: String,
    val name: String,
    val sizeBytes: Long? = null,
    val folderId: String? = null,
    val mimeType: String? = null,
    val updatedAt: String? = null,
    val deletedAt: String? = null,
    val yourRole: String? = null,
    val contentVersion: Long? = null,
)

@Serializable
data class TrashFileDto(val id: String, val name: String, val sizeBytes: Long? = null, val mimeType: String? = null, val deletedAt: String? = null)

@Serializable
data class TrashFolderDto(val id: String, val name: String, val deletedAt: String? = null)

@Serializable
data class TrashContentsDto(val files: List<TrashFileDto> = emptyList(), val folders: List<TrashFolderDto> = emptyList())

@Serializable
data class VersionDto(
    val id: String,
    val fileId: String,
    val versionNumber: Int,
    val sizeBytes: Long = 0,
    val label: String? = null,
    val createdAt: String,
    val isNamed: Boolean = false,
) {
    fun model() = NoteVersion(id, fileId, versionNumber, sizeBytes, label, DriveDate.parse(createdAt) ?: Instant.EPOCH, isNamed)
}

@Serializable
data class VersionsDto(val versions: List<VersionDto> = emptyList())

/** `fileCount` arrived after tags shipped; an older server omits it. */
@Serializable
data class TagDto(val id: String, val name: String, val createdAt: String? = null, val fileCount: Int = 0) {
    fun model() = NoteTag(id, name, DriveDate.parse(createdAt) ?: Instant.EPOCH, fileCount)
}

@Serializable
data class TagsDto(val tags: List<TagDto> = emptyList(), val total: Int = 0)

@Serializable
data class TaggedFilesDto(val files: List<FileDto> = emptyList(), val total: Int = 0)

@Serializable
data class FileLinkDto(val id: String, val title: String, val fileType: String = "file")

@Serializable
data class BacklinksDto(val backlinks: List<FileLinkDto> = emptyList())

/** `createdAt` is a `NaiveDateTime::to_string()` with a space, not the `T` DriveDate reads — and nothing needs it. */
@Serializable
data class PermissionDto(val id: String, val userId: String, val userEmail: String? = null, val userName: String? = null, val role: String) {
    fun model() = SharePermission(id, userId, userEmail.orEmpty(), userName.orEmpty(), ShareRole.of(role))
}

@Serializable
data class PermissionsDto(val permissions: List<PermissionDto> = emptyList())

@Serializable
data class DirectoryUserDto(val id: String, val email: String = "", val name: String = "")

@Serializable
data class SessionDto(
    val id: String,
    val deviceName: String? = null,
    val userAgent: String? = null,
    val createdAt: String? = null,
    val lastUsedAt: String? = null,
)

@Serializable
data class SessionsDto(val sessions: List<SessionDto> = emptyList())
