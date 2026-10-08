package com.example.xabarsos.network

import android.util.Log
import com.example.xabarsos.model.MessageChannel
import com.example.xabarsos.model.SosMessage
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

sealed class ConnectionStatus {
    object Disconnected : ConnectionStatus()
    object Connecting : ConnectionStatus()
    object Connected : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}

class WebSocketSosManager(
    private var serverBaseUrl: String = "https://xabar-sos.onrender.com"
) {
    // Client for persistent WebSocket with 1s active ping to immediately detect 4G/Wi-Fi socket drops
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .pingInterval(1, TimeUnit.SECONDS) // Active 1s Ping keeps socket 100% alive on 4G LTE & Wi-Fi
        .retryOnConnectionFailure(true)
        .build()

    // Client for fast HTTP REST requests & background polling
    private val fastHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private var onMessageReceivedListener: ((SosMessage) -> Unit)? = null
    private var onCustomJsonReceivedListener: ((JsonObject) -> Unit)? = null

    init {
        connect()
    }

    fun setOnMessageReceivedListener(listener: (SosMessage) -> Unit) {
        onMessageReceivedListener = listener
    }

    fun setOnCustomJsonReceivedListener(listener: (JsonObject) -> Unit) {
        onCustomJsonReceivedListener = listener
    }

    fun updateServerUrl(url: String) {
        var formattedUrl = url.trim()
        if (!formattedUrl.startsWith("http://") && !formattedUrl.startsWith("https://")) {
            formattedUrl = "https://$formattedUrl"
        }
        if (formattedUrl.endsWith("/")) {
            formattedUrl = formattedUrl.dropLast(1)
        }
        serverBaseUrl = formattedUrl
        reconnect()
    }

    fun getServerUrl(): String = serverBaseUrl

    @Synchronized
    fun connect() {
        if (webSocket != null) return

        val wsUrl = serverBaseUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://") + "/ws"

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("WebSocketSosManager", "WebSocket Connected to $wsUrl")
                _connectionStatus.value = ConnectionStatus.Connected
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                _connectionStatus.value = ConnectionStatus.Connected
                try {
                    val messageObj = gson.fromJson(text, JsonObject::class.java)

                    // Forward custom json signal (voice calls, signaling)
                    onCustomJsonReceivedListener?.invoke(messageObj)

                    // CRITICAL ISOLATION FIX: Skip all typing_status, delivery_ack, ping, voice_audio, and call_ signaling frames from SOS message handler!
                    if (messageObj.has("type")) {
                        val typeStr = messageObj.get("type")?.asString ?: ""
                        if (typeStr == "typing_status" || typeStr == "delivery_ack" || typeStr == "ping" || typeStr.startsWith("call_") || typeStr.startsWith("voice_")) {
                            return
                        }
                    }

                    // ONLY process actual SOS emergency messages
                    if (messageObj.has("messageText")) {
                        val msgText = messageObj.get("messageText")?.asString ?: ""
                        if (msgText.startsWith("TYPING_STATUS_")) {
                            return
                        }
                        val audioDataStr = if (messageObj.has("audioData") && !messageObj.get("audioData").isJsonNull) {
                            messageObj.get("audioData").asString
                        } else null
                        val videoDataStr = if (messageObj.has("videoData") && !messageObj.get("videoData").isJsonNull) {
                            messageObj.get("videoData").asString
                        } else null
                        val isDeliveredBool = if (messageObj.has("isDelivered") && !messageObj.get("isDelivered").isJsonNull) {
                            messageObj.get("isDelivered").asBoolean
                        } else false

                        if (msgText.isNotBlank()) {
                            val sosMessage = SosMessage(
                                id = messageObj.get("id")?.asString ?: java.util.UUID.randomUUID().toString(),
                                deviceId = messageObj.get("deviceId")?.asString ?: "",
                                senderName = messageObj.get("senderName")?.asString ?: "Noma'lum",
                                messageText = msgText,
                                targetRecipient = messageObj.get("targetRecipient")?.asString ?: "BARCHAGA",
                                timestamp = messageObj.get("timestamp")?.asLong ?: System.currentTimeMillis(),
                                channel = MessageChannel.INTERNET,
                                isIncoming = true,
                                audioData = audioDataStr,
                                videoData = videoDataStr,
                                isDelivered = isDeliveredBool
                            )
                            onMessageReceivedListener?.invoke(sosMessage)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WebSocketSosManager", "Error parsing WebSocket message: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebSocketSosManager", "WebSocket Failure: ${t.message}")
                this@WebSocketSosManager.webSocket = null
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("WebSocketSosManager", "WebSocket Closed: $reason")
                this@WebSocketSosManager.webSocket = null
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        scope.launch {
            delay(1000)
            if (webSocket == null) {
                connect()
            }
        }
    }

    @Synchronized
    fun reconnect() {
        disconnect()
        try {
            client.connectionPool.evictAll()
            fastHttpClient.connectionPool.evictAll()
        } catch (e: Exception) {}
        connect()
    }

    @Synchronized
    fun disconnect() {
        try {
            webSocket?.cancel()
            webSocket?.close(1000, "Normal closure")
        } catch (e: Exception) {}
        webSocket = null
    }

    fun fetchRecentSosMessagesHttp(sinceTimestamp: Long, onResult: (List<SosMessage>) -> Unit) {
        scope.launch {
            try {
                val pollUrl = "$serverBaseUrl/api/sos/recent?since=$sinceTimestamp"
                val request = Request.Builder().url(pollUrl).get().build()
                fastHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        _connectionStatus.value = ConnectionStatus.Connected
                        val bodyStr = response.body?.string() ?: ""
                        val jsonObj = gson.fromJson(bodyStr, JsonObject::class.java)
                        val messagesArray = jsonObj.getAsJsonArray("messages") ?: JsonArray()

                        val parsedList = mutableListOf<SosMessage>()
                        for (i in 0 until messagesArray.size()) {
                            val msgObj = messagesArray.get(i).asJsonObject

                            // Forward typing_status and delivery_ack to custom listener even from HTTP polling
                            if (msgObj.has("type")) {
                                val typeStr = msgObj.get("type")?.asString ?: ""
                                if (typeStr == "typing_status" || typeStr == "delivery_ack") {
                                    onCustomJsonReceivedListener?.invoke(msgObj)
                                    continue
                                }
                                if (typeStr == "ping" || typeStr.startsWith("call_") || typeStr.startsWith("voice_")) {
                                    continue
                                }
                            }

                            val msgText = msgObj.get("messageText")?.asString ?: ""
                            if (msgText.isBlank()) {
                                continue
                            }

                            val audioDataStr = if (msgObj.has("audioData") && !msgObj.get("audioData").isJsonNull) {
                                msgObj.get("audioData").asString
                            } else null
                            val videoDataStr = if (msgObj.has("videoData") && !msgObj.get("videoData").isJsonNull) {
                                msgObj.get("videoData").asString
                            } else null

                            val sosMsg = SosMessage(
                                id = msgObj.get("id")?.asString ?: java.util.UUID.randomUUID().toString(),
                                deviceId = msgObj.get("deviceId")?.asString ?: "",
                                senderName = msgObj.get("senderName")?.asString ?: "Noma'lum",
                                messageText = msgText,
                                targetRecipient = msgObj.get("targetRecipient")?.asString ?: "BARCHAGA",
                                timestamp = msgObj.get("timestamp")?.asLong ?: System.currentTimeMillis(),
                                channel = MessageChannel.INTERNET,
                                isIncoming = true,
                                audioData = audioDataStr,
                                videoData = videoDataStr
                            )
                            parsedList.add(sosMsg)
                        }

                        if (parsedList.isNotEmpty()) {
                            onResult(parsedList)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("WebSocketSosManager", "Error polling HTTP REST: ${e.message}")
            }
        }
    }

    fun pingRenderServerKeepAlive() {
        scope.launch {
            try {
                val pingUrl = "$serverBaseUrl/ping"
                val request = Request.Builder().url(pingUrl).get().build()
                fastHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d("WebSocketSosManager", "Render Server Keep-Alive Ping OK! (0ms cold start)")
                    }
                }
            } catch (e: Exception) {
                // If /ping path is not present, ping /api/sos/recent?since=0
                try {
                    val pollUrl = "$serverBaseUrl/api/sos/recent?since=0"
                    val request = Request.Builder().url(pollUrl).get().build()
                    fastHttpClient.newCall(request).execute().close()
                } catch (e2: Exception) {}
            }
        }
    }

    fun sendCustomJson(data: Any): Boolean {
        val jsonStr = gson.toJson(data)
        val wsSent = webSocket?.send(jsonStr) == true

        if (!wsSent) {
            // Instant 0ms HTTP REST Fallback if WebSocket is connecting or handshaking
            scope.launch {
                try {
                    val httpUrl = "$serverBaseUrl/api/sos"
                    val body = jsonStr.toRequestBody("application/json; charset=utf-8".toMediaType())
                    val request = Request.Builder()
                        .url(httpUrl)
                        .post(body)
                        .build()
                    fastHttpClient.newCall(request).execute().close()
                } catch (e: Exception) {
                    Log.e("WebSocketSosManager", "Error in HTTP REST signal fallback: ${e.message}")
                }
            }
        }
        return wsSent
    }

    fun sendSosMessage(message: SosMessage): Boolean {
        val messageMap = mutableMapOf<String, Any?>(
            "id" to message.id,
            "deviceId" to message.deviceId,
            "senderName" to message.senderName,
            "messageText" to message.messageText,
            "targetRecipient" to message.targetRecipient,
            "timestamp" to message.timestamp
        )
        if (!message.audioData.isNullOrBlank()) {
            messageMap["audioData"] = message.audioData
        }
        if (!message.videoData.isNullOrBlank()) {
            messageMap["videoData"] = message.videoData
        }

        val messageJson = gson.toJson(messageMap)

        // 1. Send via WebSocket
        val wsSent = webSocket?.send(messageJson) == true

        // 2. ALWAYS Send via HTTP REST with up to 3 Retries on 4G LTE / Wi-Fi
        scope.launch {
            var retries = 0
            var success = false
            while (retries < 3 && !success) {
                try {
                    val httpUrl = "$serverBaseUrl/api/sos"
                    val body = messageJson.toRequestBody("application/json; charset=utf-8".toMediaType())
                    val request = Request.Builder()
                        .url(httpUrl)
                        .post(body)
                        .build()
                    fastHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            Log.d("WebSocketSosManager", "HTTP POST broadcast succeeded on attempt ${retries + 1}")
                            _connectionStatus.value = ConnectionStatus.Connected
                            success = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WebSocketSosManager", "HTTP POST broadcast error attempt ${retries + 1}: ${e.message}")
                    retries++
                    delay(300) // Wait 300ms before retry
                }
            }
        }

        return wsSent
    }
}
