package com.neutrino.notes.core.service

import com.neutrino.shared.core.api.AuthClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The wire format of Drive's file-events relay (`src/shared/file_events/`): a LEB128 message type,
 * then an opaque payload. Type 2 is file-updated — **a doorbell, not a delivery**: the payload is a
 * sender id and nothing else, and the receiver re-reads the note through the key-holding path.
 * Type 1 (awareness) is ignored; this app shows no presence.
 */
object FileEventsMessage {
    const val AWARENESS = 1
    const val FILE_UPDATED = 2

    fun writeVarint(value: Int, out: ByteArrayOutputStream) {
        var remaining = value
        do {
            var byte = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining != 0) byte = byte or 0x80
            out.write(byte)
        } while (remaining != 0)
    }

    /** The value and the offset after it; null for a truncated or absurdly long varint. */
    fun readVarint(data: ByteArray, offset: Int = 0): Pair<Int, Int>? {
        var value = 0
        var shift = 0
        var i = offset
        while (i < data.size) {
            val byte = data[i].toInt() and 0xFF
            value = value or ((byte and 0x7F) shl shift)
            i++
            if (byte and 0x80 == 0) return value to i
            shift += 7
            if (shift > 35) return null
        }
        return null
    }

    fun encodeFileUpdated(clientId: String): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(FILE_UPDATED, out)
        out.write("""{"clientId":"$clientId"}""".toByteArray())
        return out.toByteArray()
    }

    /** The sender if this is a file-updated frame; anything else reads as null and is ignored. */
    fun decodeFileUpdatedSender(data: ByteArray): String? {
        val (type, offset) = readVarint(data) ?: return null
        if (type != FILE_UPDATED || offset >= data.size) return null
        return runCatching {
            Json.parseToJsonElement(data.copyOfRange(offset, data.size).decodeToString()).jsonObject["clientId"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    /**
     * `wss://host/api/v1/files/<id>/ws?token=<jwt>`. A socket cannot carry an Authorization header,
     * and the server slices the token out of the raw query without decoding it — a JWT is base64url,
     * so nothing in it needs escaping, and nothing may be escaped.
     */
    fun socketUrl(serverUrl: String, fileId: String, token: String): String? {
        val base = serverUrl.toHttpUrlOrNull() ?: return null
        val scheme = if (base.isHttps) "wss" else "ws"
        val port = if ((base.isHttps && base.port == 443) || (!base.isHttps && base.port == 80)) "" else ":${base.port}"
        return "$scheme://${base.host}$port/api/v1/files/$fileId/ws?token=$token"
    }
}

/**
 * Keeps one open note in step with edits made elsewhere by holding a socket to the relay. What
 * travels is a signal, never content. Not collaboration: no merge, no cursors — "a change made
 * elsewhere shows up without a manual refresh". Callbacks arrive on [scope]'s dispatcher.
 */
class FileEventsClient(
    http: OkHttpClient,
    private val auth: AuthClient,
    private val scope: CoroutineScope,
    val clientId: String = UUID.randomUUID().toString(),
) {
    /** A *peer* changed the file. Never called for this client's own broadcast, which the relay echoes back. */
    var onRemoteUpdate: (() -> Unit)? = null
    /** The relay re-opened after a gap; it keeps no history, so the owner must ask whether it missed a ring. */
    var onReconnect: (() -> Unit)? = null

    /** An idle relay is normal: no read timeout, and pings keep NATs and the server from dropping it. */
    private val http = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).pingInterval(30, TimeUnit.SECONDS).build()

    private var socket: WebSocket? = null
    private var fileId: String? = null
    private var attempt = 0
    private var reconnect: Job? = null
    private var stopping = false
    var isConnected = false
        private set

    fun connect(fileId: String) {
        if (this.fileId == fileId && socket != null) return
        disconnect()
        this.fileId = fileId
        stopping = false
        attempt = 0
        open()
    }

    fun disconnect() {
        close()
        fileId = null
    }

    /** Leaving the foreground: close, but remember the file for [resume]. */
    fun suspend() = close()

    fun resume() {
        if (fileId == null || socket != null) return
        stopping = false
        attempt = 0
        open()
        onReconnect?.invoke()
    }

    /** Tells every other session on this file that it changed. A no-op while disconnected. */
    fun broadcastFileUpdate() {
        if (!isConnected) return
        socket?.send(FileEventsMessage.encodeFileUpdated(clientId).toByteString())
    }

    private fun close() {
        stopping = true
        reconnect?.cancel()
        reconnect = null
        socket?.close(1001, null)
        socket = null
        isConnected = false
    }

    private fun open() {
        val id = fileId ?: return
        scope.launch {
            // Fresh first: the server closes the handshake on an expired token, and a retry would be identical.
            val token = runCatching { auth.accessToken() }.getOrNull() ?: return@launch
            if (stopping || fileId != id) return@launch
            val url = FileEventsMessage.socketUrl(auth.serverUrl, id, token) ?: return@launch
            socket = http.newWebSocket(Request.Builder().url(url).build(), Listener(id))
        }
    }

    private inner class Listener(private val forFile: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            scope.launch { if (fileId == forFile) { isConnected = true; attempt = 0 } }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val sender = FileEventsMessage.decodeFileUpdatedSender(bytes.toByteArray()) ?: return
            if (sender == clientId) return
            scope.launch { if (fileId == forFile) onRemoteUpdate?.invoke() }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket)
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)

        private fun dropped(webSocket: WebSocket) {
            scope.launch {
                if (socket !== webSocket) return@launch
                socket = null
                isConnected = false
                if (stopping || fileId != forFile || reconnect != null) return@launch
                // 2s, 4s, 8s, 16s, then 30s.
                val wait = minOf(2_000L shl minOf(attempt, 4), 30_000L)
                attempt++
                reconnect = scope.launch {
                    delay(wait)
                    reconnect = null
                    if (stopping) return@launch
                    open()
                    onReconnect?.invoke()
                }
            }
        }
    }
}
