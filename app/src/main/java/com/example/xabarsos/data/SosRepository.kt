package com.example.xabarsos.data

import android.content.Context
import com.example.xabarsos.audio.SosAlertManager
import com.example.xabarsos.audio.SosSoundType
import com.example.xabarsos.audio.VoiceNoteManager
import com.example.xabarsos.bluetooth.BluetoothSosManager
import com.example.xabarsos.model.MessageChannel
import com.example.xabarsos.model.SosMessage
import com.example.xabarsos.network.WebSocketSosManager
import com.example.xabarsos.notification.SosNotificationManager
import com.example.xabarsos.update.AppUpdateManager
import com.example.xabarsos.update.AppVersionInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class SosRepository(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: SosRepository? = null

        fun getInstance(context: Context): SosRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SosRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs = context.getSharedPreferences("xabar_sos_prefs", Context.MODE_PRIVATE)

    val alertManager = SosAlertManager(context)
    val notificationManager = SosNotificationManager(context)
    val webSocketManager = WebSocketSosManager(getServerUrl())
    val bluetoothManager = BluetoothSosManager(context)
    val voiceNoteManager = VoiceNoteManager(context)
    val appUpdateManager = AppUpdateManager(context)

    val availableAppUpdate = MutableStateFlow<AppVersionInfo?>(null)

    private val _messages = MutableStateFlow<List<SosMessage>>(emptyList())
    val messages: StateFlow<List<SosMessage>> = _messages.asStateFlow()

    private val _activeIncomingAlert = MutableStateFlow<SosMessage?>(null)
    val activeIncomingAlert: StateFlow<SosMessage?> = _activeIncomingAlert.asStateFlow()

    private val _userName = MutableStateFlow(getUserName())
    val userName: StateFlow<String> = _userName.asStateFlow()

    private val _serverUrl = MutableStateFlow(getServerUrl())
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _soundType = MutableStateFlow(getSoundType())
    val soundType: StateFlow<SosSoundType> = _soundType.asStateFlow()

    private val _friendsList = MutableStateFlow(getFriendsList())
    val friendsList: StateFlow<List<String>> = _friendsList.asStateFlow()

    private val _broadcastToAllEnabled = MutableStateFlow(getBroadcastToAllEnabled())
    val broadcastToAllEnabled: StateFlow<Boolean> = _broadcastToAllEnabled.asStateFlow()

    private val _peerTypingStatus = MutableStateFlow<String?>(null)
    val peerTypingStatus: StateFlow<String?> = _peerTypingStatus.asStateFlow()

    private val _typingSenderName = MutableStateFlow<String?>(null)
    val typingSenderName: StateFlow<String?> = _typingSenderName.asStateFlow()

    private val _typingStatusType = MutableStateFlow<String?>(null)
    val typingStatusType: StateFlow<String?> = _typingStatusType.asStateFlow()

    private val processedMessageIds = HashSet<String>()
    private var typingJob: kotlinx.coroutines.Job? = null
    @Volatile
    private var isUserDismissedTyping: Boolean = false
    @Volatile
    private var lastMessageReceivedTime: Long = 0L
    @Volatile
    private var lastTypingEventTimestamp: Long = 0L
    @Volatile
    var lastReceivedTimestamp: Long = System.currentTimeMillis() - 86400000L
    @Volatile
    private var lastMutedTimestamp: Long = 0L
    @Volatile
    private var lastClearedTimestamp: Long = prefs.getLong("last_cleared_ts", 0L)

    init {
        // Setup listener for custom signaling (Delivery ACK & Typing Status)
        webSocketManager.setOnCustomJsonReceivedListener { jsonObj ->
            try {
                if (jsonObj.has("type") && !jsonObj.get("type").isJsonNull) {
                    val type = jsonObj.get("type").asString
                    when (type) {
                        "delivery_ack" -> {
                            val msgId = if (jsonObj.has("messageId") && !jsonObj.get("messageId").isJsonNull) jsonObj.get("messageId").asString else ""
                            if (msgId.isNotEmpty()) {
                                val currentList = _messages.value.toMutableList()
                                val index = currentList.indexOfFirst { it.id == msgId }
                                if (index != -1) {
                                    currentList[index] = currentList[index].copy(isDelivered = true)
                                    _messages.value = currentList
                                }
                            }
                        }
                        "typing_status" -> {
                            if (isUserDismissedTyping || System.currentTimeMillis() - lastMessageReceivedTime < 200L) {
                                return@setOnCustomJsonReceivedListener
                            }

                            val sender = if (jsonObj.has("senderName") && !jsonObj.get("senderName").isJsonNull) jsonObj.get("senderName").asString else "Do'st"
                            val status = if (jsonObj.has("status") && !jsonObj.get("status").isJsonNull) jsonObj.get("status").asString else "idle"
                            val target = if (jsonObj.has("targetRecipient") && !jsonObj.get("targetRecipient").isJsonNull) jsonObj.get("targetRecipient").asString.trim() else ""
                            val senderDevId = if (jsonObj.has("senderDeviceId") && !jsonObj.get("senderDeviceId").isJsonNull) jsonObj.get("senderDeviceId").asString else ""
                            val eventTs = if (jsonObj.has("timestamp") && !jsonObj.get("timestamp").isJsonNull) jsonObj.get("timestamp").asLong else 0L

                            if (eventTs < lastTypingEventTimestamp) {
                                return@setOnCustomJsonReceivedListener // Ignore out-of-order older packets!
                            }

                            if (status == "idle") {
                                lastTypingEventTimestamp = eventTs
                                isUserDismissedTyping = false
                                _peerTypingStatus.value = null
                                _typingSenderName.value = null
                                _typingStatusType.value = null
                                typingJob?.cancel()
                                return@setOnCustomJsonReceivedListener
                            }

                            val myName = getUserName().trim()
                            val isForMe = target.isEmpty()
                                    || target.equals(myName, ignoreCase = true)
                                    || myName.contains(target, ignoreCase = true)
                                    || target.contains(myName, ignoreCase = true)
                                    || (senderDevId.isNotBlank() && senderDevId != getDeviceId())

                            if (senderDevId != getDeviceId() && isForMe) {
                                lastTypingEventTimestamp = eventTs
                                typingJob?.cancel()
                                when (status) {
                                    "typing_text" -> {
                                        _typingSenderName.value = sender
                                        _typingStatusType.value = "typing_text"
                                        _peerTypingStatus.value = "✏️ $sender SIZGA MATNLI XABAR YOZMOQDA..."
                                        typingJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                            kotlinx.coroutines.delay(15000)
                                            _typingSenderName.value = null
                                            _typingStatusType.value = null
                                            _peerTypingStatus.value = null
                                        }
                                    }
                                    "typing_voice" -> {
                                        _typingSenderName.value = sender
                                        _typingStatusType.value = "typing_voice"
                                        _peerTypingStatus.value = "🎙️ $sender SIZGA GALASAVOY YOZMOQDA..."
                                        typingJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                            kotlinx.coroutines.delay(15000)
                                            _typingSenderName.value = null
                                            _typingStatusType.value = null
                                            _peerTypingStatus.value = null
                                        }
                                    }
                                    else -> {
                                        _typingSenderName.value = null
                                        _typingStatusType.value = null
                                        _peerTypingStatus.value = null
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SosRepository", "Error handling custom json: ${e.message}")
            }
        }

        // Setup listener for WebSocket messages
        webSocketManager.setOnMessageReceivedListener { sosMessage ->
            processIncomingSosMessage(sosMessage)
        }

        // Setup listener for Bluetooth messages
        bluetoothManager.setOnMessageReceivedListener { sosMessage ->
            processIncomingSosMessage(sosMessage)
        }

        // Connect WebSocket and Bluetooth Scan
        webSocketManager.connect()
        bluetoothManager.startListeningForNearbySos()

        // Check for In-App Auto-Update (Play Market-Free Direct Installer)
        appUpdateManager.checkAppUpdate(getServerUrl()) { info ->
            availableAppUpdate.value = info
        }
    }

    fun getDeviceId(): String {
        var devId = prefs.getString("device_id", null)
        if (devId.isNullOrEmpty()) {
            devId = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", devId).apply()
        }
        return devId
    }

    @Synchronized
    fun processIncomingSosMessage(sosMessage: SosMessage) {
        lastMessageReceivedTime = System.currentTimeMillis()
        // Stop typing indicator and reset mute when real message arrives!
        isUserDismissedTyping = false
        _peerTypingStatus.value = null
        _typingSenderName.value = null
        _typingStatusType.value = null
        typingJob?.cancel()

        // Track latest message timestamp for fast 0.01s HTTP sync
        if (sosMessage.timestamp > lastReceivedTimestamp) {
            lastReceivedTimestamp = sosMessage.timestamp
        }

        // Ignore messages older than last cleared timestamp
        if (sosMessage.timestamp <= lastClearedTimestamp) {
            return
        }

        // Check if message ID was ALREADY processed
        if (processedMessageIds.contains(sosMessage.id)) {
            return
        }
        processedMessageIds.add(sosMessage.id)

        val currentList = _messages.value.toMutableList()
        val myDeviceId = getDeviceId()
        val myName = getUserName().trim()

        // Check if message is a STOP signal
        val isStopSignal = sosMessage.messageText.contains("STOP", ignoreCase = true)
                || sosMessage.messageText.contains("BEKOR QILINDI", ignoreCase = true)

        if (isStopSignal) {
            lastMutedTimestamp = maxOf(lastMutedTimestamp, sosMessage.timestamp, System.currentTimeMillis())
            dismissActiveAlert()

            if (currentList.none { it.id == sosMessage.id }) {
                currentList.add(0, sosMessage)
                _messages.value = currentList
            }
            return // DO NOT PLAY SOUND OR NOTIFICATION FOR STOP SIGNAL!
        }

        // 1. IF THIS MESSAGE WAS SENT FROM THIS EXACT PHYSICAL DEVICE ID, DO NOT PLAY ALARM OR NOTIFICATION!
        val isOurOwnMessage = sosMessage.senderName.equals(myName, ignoreCase = true) 
                && (sosMessage.deviceId == myDeviceId || System.currentTimeMillis() - sosMessage.timestamp < 4000L)

        if (isOurOwnMessage) {
            if (currentList.none { it.id == sosMessage.id }) {
                currentList.add(0, sosMessage.copy(isIncoming = false))
                _messages.value = currentList
            }
            return
        }

        // 2. INCOMING MESSAGE FROM ANOTHER DEVICE
        if (currentList.none { it.id == sosMessage.id }) {
            val incomingMsg = sosMessage.copy(isIncoming = true)
            currentList.add(0, incomingMsg)
            _messages.value = currentList

            // Send Delivery ACK back to sender so sender sees ✓✓ Delivered!
            if (sosMessage.deviceId.isNotBlank() && sosMessage.deviceId != myDeviceId) {
                webSocketManager.sendCustomJson(
                    mapOf(
                        "type" to "delivery_ack",
                        "messageId" to sosMessage.id,
                        "senderName" to myName,
                        "senderDeviceId" to myDeviceId,
                        "targetRecipient" to sosMessage.senderName
                    )
                )
            }

            // Ignore old messages created BEFORE last STOP/mute timestamp!
            if (sosMessage.timestamp <= lastMutedTimestamp) {
                return
            }

            _activeIncomingAlert.value = incomingMsg

            if (!incomingMsg.audioData.isNullOrBlank()) {
                // IF GALASAVOY (Voice Note): Automatically play sender's voice out loud at 100% max volume!
                notificationManager.showHeadsUpSosNotification(incomingMsg)
                voiceNoteManager.playVoiceNote(incomingMsg.audioData)
            } else {
                // IF TEXT SOS: Play emergency siren alarm sound & vibration
                alertManager.playAlertSoundAndVibrate(_soundType.value)
                notificationManager.showHeadsUpSosNotification(incomingMsg)
            }
        }
    }

    fun dismissActiveAlert() {
        lastMutedTimestamp = System.currentTimeMillis()
        isUserDismissedTyping = true
        _activeIncomingAlert.value = null
        _peerTypingStatus.value = null
        _typingSenderName.value = null
        _typingStatusType.value = null
        typingJob?.cancel()
        SosAlertManager.stopAllAlerts()
        voiceNoteManager.stopPlaying()
        notificationManager.cancelEmergencyNotification()
    }

    fun sendSos(messageText: String, targetRecipient: String = "BARCHAGA") {
        sendTypingStatus("idle", targetRecipient)
        val currentSender = getUserName()
        val myDeviceId = getDeviceId()

        val isStop = messageText.contains("STOP", ignoreCase = true) || messageText.contains("BEKOR QILINDI", ignoreCase = true)
        val formattedTarget = if (isStop) "BARCHAGA" else targetRecipient.trim().ifEmpty { "BARCHAGA" }

        val sosMessage = SosMessage(
            senderName = currentSender,
            deviceId = myDeviceId,
            messageText = messageText,
            targetRecipient = formattedTarget,
            channel = MessageChannel.INTERNET,
            isIncoming = false
        )

        // Mark own message ID as processed
        processedMessageIds.add(sosMessage.id)

        // 1. Add to local list
        val currentList = _messages.value.toMutableList()
        currentList.add(0, sosMessage)
        _messages.value = currentList

        // If sending STOP message, update mute timestamp locally & stop all sound
        if (isStop) {
            dismissActiveAlert()
        }

        // 2. Broadcast via WebSocket (Wi-Fi / 4G Internet)
        webSocketManager.connect()
        webSocketManager.sendSosMessage(sosMessage)

        // 3. Broadcast via Bluetooth LE (Offline local mesh)
        bluetoothManager.broadcastSosOffline(currentSender, myDeviceId, formattedTarget, messageText)
    }

    fun sendTypingStatus(status: String, targetRecipient: String = "BARCHAGA") {
        val payload = mapOf(
            "id" to UUID.randomUUID().toString(),
            "type" to "typing_status",
            "status" to status,
            "senderName" to getUserName(),
            "senderDeviceId" to getDeviceId(),
            "targetRecipient" to targetRecipient,
            "timestamp" to System.currentTimeMillis(),
            "messageText" to "TYPING_STATUS_$status"
        )
        webSocketManager.sendCustomJson(payload)
    }

    fun saveUserName(name: String) {
        val trimmed = name.trim().ifEmpty { "Foydalanuvchi" }
        prefs.edit().putString("user_name", trimmed).apply()
        _userName.value = trimmed
    }

    fun getUserName(): String {
        return prefs.getString("user_name", "SAIDBEK") ?: "SAIDBEK"
    }

    fun saveServerUrl(url: String) {
        val trimmed = url.trim().ifEmpty { "https://xabar-sos.onrender.com" }
        prefs.edit().putString("server_url", trimmed).apply()
        _serverUrl.value = trimmed
        webSocketManager.updateServerUrl(trimmed)
    }

    fun getServerUrl(): String {
        return prefs.getString("server_url", "https://xabar-sos.onrender.com") ?: "https://xabar-sos.onrender.com"
    }

    fun saveSoundType(type: SosSoundType) {
        prefs.edit().putString("sound_type", type.name).apply()
        _soundType.value = type
    }

    fun getSoundType(): SosSoundType {
        val savedName = prefs.getString("sound_type", SosSoundType.ALARM.name) ?: SosSoundType.ALARM.name
        return try {
            SosSoundType.valueOf(savedName)
        } catch (e: Exception) {
            SosSoundType.ALARM
        }
    }

    fun getBroadcastToAllEnabled(): Boolean {
        return prefs.getBoolean("broadcast_to_all", true)
    }

    fun saveBroadcastToAllEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("broadcast_to_all", enabled).apply()
        _broadcastToAllEnabled.value = enabled
    }

    fun getFriendsList(): List<String> {
        val savedSet = prefs.getStringSet("friends_set", emptySet()) ?: emptySet()
        return savedSet.toList().sorted()
    }

    fun addFriend(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotBlank()) {
            val currentList = getFriendsList().toMutableList()
            if (!currentList.contains(trimmed)) {
                currentList.add(trimmed)
            }
            // SharedPreferences requires a NEW Set instance
            val newSet = HashSet(currentList)
            prefs.edit().remove("friends_set").apply() // Clear existing reference first
            prefs.edit().putStringSet("friends_set", newSet).apply()
            _friendsList.value = currentList.sorted()
        }
    }

    fun removeFriend(name: String) {
        val currentList = getFriendsList().toMutableList()
        currentList.remove(name)
        val newSet = HashSet(currentList)
        prefs.edit().remove("friends_set").apply()
        prefs.edit().putStringSet("friends_set", newSet).apply()
        _friendsList.value = currentList.sorted()
    }

    fun deleteMessageById(id: String) {
        val currentList = _messages.value.toMutableList()
        currentList.removeAll { it.id == id }
        _messages.value = currentList
    }

    fun sendVoiceNote(targetRecipient: String = "BARCHAGA") {
        sendTypingStatus("idle", targetRecipient)
        val base64Audio = voiceNoteManager.stopRecordingAndGetBase64()
        if (!base64Audio.isNullOrBlank()) {
            val currentSender = getUserName()
            val myDeviceId = getDeviceId()
            val formattedTarget = targetRecipient.trim().ifEmpty { "BARCHAGA" }

            val sosMessage = SosMessage(
                senderName = currentSender,
                deviceId = myDeviceId,
                messageText = "🎙️ OVOZLI XABAR (GALASAVOY)",
                targetRecipient = formattedTarget,
                channel = MessageChannel.INTERNET,
                isIncoming = false,
                audioData = base64Audio
            )

            processedMessageIds.add(sosMessage.id)

            val currentList = _messages.value.toMutableList()
            currentList.add(0, sosMessage)
            _messages.value = currentList

            webSocketManager.connect()
            webSocketManager.sendSosMessage(sosMessage)
            bluetoothManager.broadcastSosOffline(currentSender, myDeviceId, formattedTarget, "🎙️ OVOZLI XABAR (GALASAVOY)")
        }
    }

    fun clearHistory() {
        lastClearedTimestamp = System.currentTimeMillis()
        prefs.edit().putLong("last_cleared_ts", lastClearedTimestamp).apply()
        _messages.value = emptyList()
        processedMessageIds.clear()
    }
}
