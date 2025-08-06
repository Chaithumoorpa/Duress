package com.techm.duress.core.webrtc

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SignalingSocket(
    private val websocketUrl: String,
    private val signalingListener: Listener
) {

    private var webSocket: WebSocket? = null
    private var pendingOffer: JSONObject? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    interface Listener {
        fun onOfferReceived(sdp: String)
        fun onAnswerReceived(sdp: String)
        fun onIceCandidateReceived(candidate: String)
        fun onSocketOpened()
        fun onSocketClosed()
    }

    fun connect() {
        val request = Request.Builder().url(websocketUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d("SignalingSocket", "WebSocket opened: $websocketUrl")
                pendingOffer?.let {
                    Log.d("SignalingSocket", "Flushing pending offer...")
                    webSocket?.send(it.toString())
                    pendingOffer = null
                }
                signalingListener.onSocketOpened()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d("SignalingSocket", "Message received: $text")
                try {
                    val json = JSONObject(text)
                    val event = json.getString("event")
                    val data = json.getString("data")

                    when (event) {
                        "offer" -> signalingListener.onOfferReceived(data)
                        "answer" -> signalingListener.onAnswerReceived(data)
                        "ice-candidate" -> signalingListener.onIceCandidateReceived(data)
                        else -> Log.w("SignalingSocket", "Unknown event: $event")
                    }
                } catch (e: Exception) {
                    Log.e("SignalingSocket", "Error parsing message: ${e.message}", e)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e("SignalingSocket", "WebSocket error: ${t.message}", t)
                signalingListener.onSocketClosed()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d("SignalingSocket", "WebSocket closed: $reason (Code: $code)")
                signalingListener.onSocketClosed()
            }
        })
    }

    fun sendOffer(sdp: String, roomId: String) {
        try {
            val offerJson = JSONObject().apply {
                put("event", "offer")
                put("data", sdp)
                put("roomId", roomId)
            }

            if (isSocketReady()) {
                Log.d("SignalingSocket", "Sending offer immediately...")
                webSocket?.send(offerJson.toString())
            } else {
                Log.w("SignalingSocket", "WebSocket not ready. Queuing offer...")
                pendingOffer = offerJson
                retrySendingOffer(offerJson, attempt = 1)
            }

        } catch (e: Exception) {
            Log.e("SignalingSocket", "Failed to send offer: ${e.message}", e)
        }
    }

    private fun isSocketReady(): Boolean {
        return webSocket != null // You can enhance with custom readyState logic if needed
    }

    private fun retrySendingOffer(offerJson: JSONObject, attempt: Int) {
        val maxAttempts = 5
        val delayMillis = 500L * attempt

        if (attempt > maxAttempts) {
            Log.e("SignalingSocket", "Max retry attempts reached. Offer discarded.")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            delay(delayMillis)
            if (isSocketReady()) {
                Log.d("SignalingSocket", "Retrying offer... attempt $attempt")
                webSocket?.send(offerJson.toString())
                pendingOffer = null
            } else {
                Log.w("SignalingSocket", "WebSocket still not ready. Retrying offer...")
                retrySendingOffer(offerJson, attempt + 1)
            }
        }
    }

    fun sendAnswer(sdp: String, roomId: String) {
        try {
            val json = JSONObject().apply {
                put("event", "answer")
                put("data", sdp)
                put("roomId", roomId)
            }
            Log.d("SignalingSocket", "Sending answer: $sdp to room: $roomId")

            webSocket?.send(json.toString())
                ?: Log.e("SignalingSocket", "WebSocket not connected to send answer")
        } catch (e: Exception) {
            Log.e("SignalingSocket", "Failed to send answer: ${e.message}", e)
        }
    }

    fun sendCandidate(candidate: String, roomId: String) {
        try {
            val json = JSONObject().apply {
                put("event", "ice-candidate")
                put("data", candidate)
                put("roomId", roomId)
            }
            webSocket?.send(json.toString())
                ?: Log.e("SignalingSocket", "WebSocket not connected to send candidate")
        } catch (e: Exception) {
            Log.e("SignalingSocket", "Failed to send candidate: ${e.message}", e)
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "Client disconnected")
        webSocket = null
    }
}
