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
        fun onOfferReceived(sdp: String)        // raw SDP
        fun onAnswerReceived(sdp: String)       // raw SDP
        fun onIceCandidateReceived(candidate: String) // JSON string of IceCandidateInit
        fun onSocketOpened()
        fun onSocketClosed()
    }

    private val TAG = "DU/WS"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val isOpen = AtomicBoolean(false)

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)

    private data class Outgoing(val json: JSONObject)
    private val outbound = ConcurrentLinkedQueue<Outgoing>()

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
                try {
                    val root = JSONObject(text)
                    val event = root.optString("event", "")
                    val rawData = root.opt("data")

                    // Normalize "data" so downstream always gets what it expects:
                    // - For offer/answer: pass RAW SDP string
                    // - For candidate: pass JSON string of IceCandidateInit
                    val normalized: String = when (rawData) {
                        is String -> {
                            val s = rawData.trim()
                            if (s.startsWith("{") && s.endsWith("}")) {
                                val obj = JSONObject(s)
                                when (event) {
                                    "offer", "answer" -> obj.optString("sdp", "")
                                    "candidate" -> obj.toString()
                                    else -> s
                                }
                            } else {
                                // Plain string (rare on this server, but keep for safety)
                                if (event == "candidate") {
                                    // Wrap as candidate JSON with just "candidate"
                                    JSONObject().put("candidate", s).toString()
                                } else s
                            }
                        }
                        is JSONObject -> {
                            when (event) {
                                "offer", "answer" -> rawData.optString("sdp", "")
                                "candidate" -> rawData.toString()
                                else -> rawData.toString()
                            }
                        }
                        else -> ""
                    }

                    when (event) {
                        "offer"     -> signalingListener.onOfferReceived(normalized)
                        "answer"    -> signalingListener.onAnswerReceived(normalized)
                        "candidate" -> signalingListener.onIceCandidateReceived(normalized)
                        else        -> Log.w(TAG, "Unknown event: $event")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Parse error: ${t.message}")
                }
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

    // ---------- Public send helpers ----------
    // Server expects JSON objects for SDP/candidates
    fun sendOffer(sdp: String, roomId: String) =
        send("offer", JSONObject().put("type", "offer").put("sdp", sdp), roomId)

    fun sendAnswer(sdp: String, roomId: String) =
        send("answer", JSONObject().put("type", "answer").put("sdp", sdp), roomId)

    // Preferred (structured) candidate
    fun sendCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String, roomId: String) =
        send(
            "candidate",
            JSONObject()
                .put("candidate", candidate)
                .put("sdpMid", sdpMid ?: JSONObject.NULL)
                .put("sdpMLineIndex", sdpMLineIndex),
            roomId
        )

    // Legacy fallback (still wraps as JSON)
    fun sendCandidate(candidate: String, roomId: String) =
        send("candidate", JSONObject().put("candidate", candidate), roomId)

    // ---------- Core send ----------
    private fun send(event: String, data: JSONObject, roomId: String) {
        val obj = JSONObject()
            .put("event", event)
            .put("data", data)           // NOTE: JSON object, not a plain string
            .put("roomId", roomId)

        if (isOpen.get()) {
            val ok = webSocket?.send(obj.toString()) ?: false
            if (!ok) {
                Log.w(TAG, "Send failed (ws null); queueing $event")
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

    fun isConnected(): Boolean = isOpen.get()

    fun close(code: Int = 1000, reason: String = "Client closing") {
        try { webSocket?.close(code, reason) } catch (_: Throwable) {}
        webSocket = null
        isOpen.set(false)
        outbound.clear()
        job.cancel()
        Log.d(TAG, "Closed($code): $reason")
    }

    fun disconnect() = close(1000, "Client disconnected")
}
