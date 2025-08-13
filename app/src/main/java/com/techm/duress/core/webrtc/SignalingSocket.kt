package com.techm.duress.core.webrtc

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SignalingSocket(
    private val websocketUrl: String,
    private val signalingListener: Listener
) {

    interface Listener {
        fun onOfferReceived(sdp: String)
        fun onAnswerReceived(sdp: String)
        fun onIceCandidateReceived(candidateJson: String)
        fun onSocketOpened()
        fun onSocketClosed()
    }

    private val TAG = "DU/WS"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // streaming messages
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val isOpen = AtomicBoolean(false)

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)

    private data class Outgoing(val json: JSONObject)
    private val outbound = ConcurrentLinkedQueue<Outgoing>()

    /** Connects to the signaling server WebSocket */
    fun connect() {
        val request = Request.Builder().url(websocketUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "OPEN @ $websocketUrl code=${response.code}")
                isOpen.set(true)
                signalingListener.onSocketOpened()
                flushQueued()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d(TAG, "RECV: $text")
                parseIncomingMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "FAIL: ${t.message} code=${response?.code}")
                isOpen.set(false)
                signalingListener.onSocketClosed()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "CLOSED code=$code reason=$reason")
                isOpen.set(false)
                signalingListener.onSocketClosed()
            }
        })
    }

    /** Outgoing message helpers **/
    fun sendOffer(sdp: String, roomId: String)    = send("offer", sdp, roomId)
    fun sendAnswer(sdp: String, roomId: String)   = send("answer", sdp, roomId)
    fun sendCandidate(candidateJson: String, roomId: String) =
        send("candidate", candidateJson, roomId)

    private fun send(event: String, data: String, roomId: String) {
        val obj = JSONObject()
            .put("event", event)
            .put("data", data)
            .put("roomId", roomId)

        if (isOpen.get()) {
            val ok = webSocket?.send(obj.toString()) ?: false
            if (!ok) {
                Log.w(TAG, "Send failed; queueing $event")
                outbound.add(Outgoing(obj))
            } else {
                Log.d(TAG, "SEND $event size=${obj.toString().length}")
            }
        } else {
            Log.d(TAG, "WS not open; queueing $event")
            outbound.add(Outgoing(obj))
            scheduleFlushBackoff()
        }
    }

    /** Flush queued messages once connected */
    private fun flushQueued() {
        scope.launch {
            var count = 0
            while (true) {
                val msg = outbound.poll() ?: break
                val ok = webSocket?.send(msg.json.toString()) ?: false
                if (ok) count++ else { outbound.add(msg); break }
            }
            if (count > 0) Log.d(TAG, "Flushed $count queued messages")
        }
    }

    /** Retry flush until connected or give up */
    private var backoffJob: Job? = null
    private fun scheduleFlushBackoff() {
        if (backoffJob?.isActive == true) return
        backoffJob = scope.launch {
            repeat(5) { i ->
                delay(500L * (i + 1))
                if (isOpen.get()) {
                    flushQueued()
                    return@launch
                }
            }
        }
    }

    /** Parse & dispatch server messages safely */
    private fun parseIncomingMessage(text: String) {
        runCatching {
            val json = JSONObject(text)
            val event = json.optString("event", "")
            val dataAny = json.opt("data")

            fun extractSdp(raw: Any?): String = when (raw) {
                is JSONObject -> raw.optString("sdp", "")
                is String -> if (raw.trim().startsWith("{")) {
                    JSONObject(raw).optString("sdp", raw)
                } else raw
                else -> ""
            }

            fun normalizeCandidate(raw: Any?): String = when (raw) {
                is JSONObject -> raw.toString()
                is String -> if (raw.trim().startsWith("{")) raw
                else JSONObject().put("candidate", raw).toString()
                else -> JSONObject().put("candidate", "").toString()
            }

            when (event) {
                "offer" -> signalingListener.onOfferReceived(extractSdp(dataAny))
                "answer" -> signalingListener.onAnswerReceived(extractSdp(dataAny))
                "candidate" -> signalingListener.onIceCandidateReceived(normalizeCandidate(dataAny))
                else -> Log.w(TAG, "Unknown event: $event")
            }
        }.onFailure {
            Log.w(TAG, "Parse error: ${it.message}")
        }
    }

    fun isConnected(): Boolean = isOpen.get()

    fun close(code: Int = 1000, reason: String = "Client closing") {
        runCatching { webSocket?.close(code, reason) }
        webSocket = null
        isOpen.set(false)
        outbound.clear()
        job.cancel()
        Log.d(TAG, "Closed($code): $reason")
    }

    fun disconnect() = close(1000, "Client disconnected")
}
