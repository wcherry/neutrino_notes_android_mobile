package com.neutrino.notes.core.service

import com.neutrino.notes.core.api.NotesApi
import com.neutrino.notes.core.model.NoteFileInfo
import com.neutrino.notes.core.model.NoteItem
import com.neutrino.shared.core.api.DriveDate
import com.neutrino.shared.core.api.FileDto
import com.neutrino.shared.core.crypto.FileCrypto
import com.neutrino.shared.core.keys.DeviceKeyCheck
import com.neutrino.shared.core.keys.KeyException
import com.neutrino.shared.core.keys.Keyring
import com.neutrino.shared.core.keys.openDek
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.logging.Logger

/** A note's DEK as the server holds it, with the identity version it is sealed to — meaningful only together. */
data class SealedFileKey(val sealed: String, val keyVersion: Int)

sealed class NoteContentException(message: String) : Exception(message) {
    /** The device holds an identity, but not the version this note's DEK was sealed to. */
    class MissingKeyVersion(val version: Int) : NoteContentException(
        "This note needs encryption key version $version, which this device does not have. On a device that holds " +
            "your key, open Settings › Encryption and back up your older keys, then reopen this app.",
    )
    class DecryptionFailed : NoteContentException("Failed to decrypt the note.")
}

/**
 * Encrypts, uploads, downloads and decrypts the Markdown body of a note — Drive's E2EE protocol
 * (secretstream content under a per-file DEK sealed to the account's key) through the shared
 * crypto, so a note written here opens on the web and iOS and the other way round. The body is the
 * Markdown text, UTF-8, and nothing else. Nothing here logs a key or plaintext.
 */
class NoteContentService(
    private val api: NotesApi,
    private val keyring: () -> Keyring?,
    private val keyCheck: DeviceKeyCheck,
) : NoteSyncing {
    private val log = Logger.getLogger("NoteContentService")
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The outcome of a save: the server's view afterwards. */
    data class SaveResult(val updatedAt: Instant, val contentVersion: Long?)

    /** Creates a note under a fresh DEK, with `text` as its body (empty by default). */
    suspend fun createNote(name: String, parentId: String?, text: String = ""): NoteItem {
        val (entry, version) = keyCheck.sealingVersion()
        val dek = FileCrypto.generateDek()
        val content = withContext(Dispatchers.Default) { FileCrypto.encryptFile(text.toByteArray(), dek) }
        val created = api.create(
            ciphertext = content, fileName = name, folderId = parentId,
            encryptedMetadata = FileCrypto.encryptMetadata(name, NoteItem.MARKDOWN_MIME, dek),
            sealedFileKey = FileCrypto.sealDek(dek, entry.publicKey), keyVersion = version,
        )
        log.fine("createNote: ${created.id}")
        return item(created)
    }

    /** Downloads and decrypts a note. The DEK comes back with the text: hold it for the session so autosaves need no re-unseal. */
    suspend fun loadContent(item: NoteItem): Pair<String, ByteArray> {
        val sealed = fetchSealedKey(item.id) ?: throw NoteContentException.DecryptionFailed()
        val dek = unsealDek(sealed, item.id)
        return decryptText(api.download(item.id), dek) to dek
    }

    /** The still-encrypted body and its sealed key, so the offline cache holds the server's bytes verbatim. */
    suspend fun downloadEncrypted(item: NoteItem): Pair<ByteArray, SealedFileKey> {
        val sealed = fetchSealedKey(item.id) ?: throw NoteContentException.DecryptionFailed()
        return api.download(item.id) to sealed
    }

    override suspend fun fileInfo(fileId: String): NoteFileInfo? = api.fileInfo(fileId)

    /**
     * Encrypts `text` under the note's DEK and autosaves it. `expectedContentVersion` makes the server
     * refuse the write if anything landed since ([NotesApi.ContentVersionConflict]); null saves unguarded.
     */
    override suspend fun saveContent(text: String, item: NoteItem, dek: ByteArray, expectedContentVersion: Long?): SaveResult {
        val ciphertext = withContext(Dispatchers.Default) { FileCrypto.encryptFile(text.toByteArray(), dek) }
        val updated = api.autosave(item.id, item.name, item.mimeType ?: NoteItem.MARKDOWN_MIME, ciphertext, expectedContentVersion)
        return SaveResult(DriveDate.parse(updated.updatedAt) ?: Instant.now(), updated.contentVersion)
    }

    // ── Crypto ──────────────────────────────────────────────────────────────────────────────────

    fun encryptText(text: String, dek: ByteArray): ByteArray = FileCrypto.encryptFile(text.toByteArray(), dek)

    /** Reads every body shape the platform writes (v0, NEB1, chunked) through the shared reader. */
    fun decryptText(data: ByteArray, dek: ByteArray): String = try {
        FileCrypto.decryptFile(data, dek).decodeToString(throwOnInvalidSequence = true)
    } catch (e: FileCrypto.CryptoException) {
        throw NoteContentException.DecryptionFailed()
    } catch (e: CharacterCodingException) {
        throw NoteContentException.DecryptionFailed()
    }

    /** The caller's sealed DEK, or null when the file has no key ref — a plaintext upload. */
    suspend fun fetchSealedKey(fileId: String): SealedFileKey? =
        api.fileKey(fileId)?.let { SealedFileKey(it.encryptedFileKey, it.keyVersion ?: 1) }

    /**
     * Opens a sealed DEK with whichever key this device holds, the named version first. A version the
     * device lacks is reported as such; a ref filed under the wrong version is re-filed in the background.
     */
    fun unsealDek(sealed: SealedFileKey, fileId: String? = null): ByteArray {
        val ring = keyring() ?: throw KeyException.NoEncryptionKey()
        val (dek, version) = ring.openDek(sealed.sealed, sealed.keyVersion)
            ?: throw if (ring.entry(sealed.keyVersion) == null) NoteContentException.MissingKeyVersion(sealed.keyVersion) else NoteContentException.DecryptionFailed()
        if (version != sealed.keyVersion && fileId != null) {
            log.info("refiling $fileId's key under v$version")
            background.launch { runCatching { api.setFileKey(fileId, sealed.sealed, version) } }
        }
        return dek
    }

    companion object {
        fun item(file: FileDto, isShared: Boolean = false): NoteItem = NoteItem(
            id = file.id, name = file.name, type = NoteItem.ItemType.FILE, parentId = file.folderId,
            size = file.sizeBytes, modifiedAt = DriveDate.parse(file.updatedAt) ?: Instant.EPOCH,
            mimeType = file.mimeType, isStarred = if (isShared) false else file.isStarred ?: false,
            contentVersion = file.contentVersion, isShared = isShared,
        )
    }
}
