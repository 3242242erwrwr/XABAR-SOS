package com.example.xabarsos.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xabarsos.audio.VoiceEffect
import com.example.xabarsos.ui.theme.EmergencyRed

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickSosButtons(
    friendsList: List<String>,
    selectedRecipient: String = "",
    typingSenderName: String? = null,
    typingStatusType: String? = null,
    onSelectRecipient: (String) -> Unit = {},
    isRecordingVoiceNote: Boolean = false,
    recordingDurationSeconds: Int = 0,
    selectedVoiceEffect: VoiceEffect = VoiceEffect.NORMAL,
    onSelectVoiceEffect: (VoiceEffect) -> Unit = {},
    onStartVoiceNoteRecording: () -> Unit = {},
    onStopVoiceNoteAndSend: () -> Unit = {},
    onCancelVoiceNoteRecording: () -> Unit = {},
    onSendTypingStatus: (String) -> Unit = {},
    onOpenAddFriendDialog: () -> Unit = {},
    onShowSelectFriendPrompt: () -> Unit = {},
    onSendSos: (text: String, recipient: String) -> Unit,
    onStopAllAlerts: () -> Unit = {}
) {
    var customMessage by remember { mutableStateOf("") }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    val infiniteTransition = rememberInfiniteTransition(label = "neonPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    androidx.compose.runtime.LaunchedEffect(isRecordingVoiceNote, selectedRecipient) {
        if (isRecordingVoiceNote && selectedRecipient.isNotBlank()) {
            while (isRecordingVoiceNote) {
                onSendTypingStatus("typing_voice")
                kotlinx.coroutines.delay(2500)
            }
        } else {
            onSendTypingStatus("idle")
        }
    }

    androidx.compose.runtime.LaunchedEffect(customMessage, selectedRecipient) {
        if (customMessage.isNotBlank() && selectedRecipient.isNotBlank()) {
            while (customMessage.isNotBlank()) {
                onSendTypingStatus("typing_text")
                kotlinx.coroutines.delay(3000)
            }
        } else {
            onSendTypingStatus("idle")
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                border = BorderStroke(
                    width = 1.2.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.08f))
                    )
                ),
                shape = RoundedCornerShape(20.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0F1424).copy(alpha = 0.38f)
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            // HEADER: DO'STLAR BILAN ULANISH
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🤝 DO'STLAR BILAN ULANISH",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // SIDE-BY-SIDE TELEGRAM-STYLE LAYOUT
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // LEFT SIDE PANEL: TELEGRAM-STYLE FRIENDS SPISOK (40% Width - Full Height 340dp)
                Card(
                    modifier = Modifier
                        .weight(0.40f)
                        .height(340.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF111420)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🤝 SPISOK",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = Color(0xFF00B0FF)
                            )
                            Text(
                                text = "${friendsList.size} ta",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(friendsList) { friendName ->
                                val isSelected = selectedRecipient.equals(friendName, ignoreCase = true)
                                val isThisFriendTyping = typingSenderName != null && (typingSenderName.equals(friendName, ignoreCase = true) || typingSenderName.contains(friendName, ignoreCase = true))
                                val isVoiceTyping = isThisFriendTyping && typingStatusType == "typing_voice"
                                val isTextTyping = isThisFriendTyping && typingStatusType == "typing_text"

                                val neonColor = when {
                                    isVoiceTyping -> Color(0xFF00E676) // Neon Green for Voice
                                    isTextTyping -> Color(0xFFFF1744)  // Neon Red for Text
                                    else -> Color.Transparent
                                }

                                val cardBgColor = when {
                                    isThisFriendTyping -> neonColor.copy(alpha = pulseAlpha * 0.4f)
                                    isSelected -> Color(0xFF00B0FF)
                                    else -> Color(0xFF1E2230)
                                }

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (isSelected) {
                                                onSelectRecipient("")
                                            } else {
                                                onSelectRecipient(friendName)
                                            }
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = cardBgColor
                                    ),
                                    border = if (isThisFriendTyping) BorderStroke(2.5.dp, neonColor.copy(alpha = pulseAlpha)) else null,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = "👤 $friendName",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            color = Color.White,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Button(
                            onClick = onOpenAddFriendDialog,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00B0FF).copy(alpha = 0.25f),
                                contentColor = Color(0xFF00B0FF)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                        ) {
                            Text("➕ Qo'shish", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // RIGHT SIDE PANEL: TARGET BADGE & VOICE EFFECTS (60% Width)
                Column(
                    modifier = Modifier
                        .weight(0.60f)
                        .height(340.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        // Selected Target Indicator Badge
                        Text(
                            text = "🎯 Kimga: ${if (selectedRecipient.isBlank()) "Tanlanmagan ⚠️" else "👤 $selectedRecipient"}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // VOICE EFFECT SELECTOR CHIPS (🎭 OVOZ EFFEKTI)
                        Text(
                            text = "🎭 OVOZ EFFEKTI:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.LightGray
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            VoiceEffect.entries.forEach { effect ->
                                val isSelected = selectedVoiceEffect == effect
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onSelectVoiceEffect(effect) },
                                    label = {
                                        Text(
                                            text = "${effect.emoji} ${effect.displayName}",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFF00E676),
                                        selectedLabelColor = Color.Black,
                                        containerColor = Color(0xFF1E2230),
                                        labelColor = Color.LightGray
                                    )
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Custom Message Section (Only shown when a friend is selected)
            if (selectedRecipient.isNotBlank()) {
                Text(
                    text = "Boshqa maxsus xabar yozish:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.LightGray
                )

                Spacer(modifier = Modifier.height(4.dp))

                if (isRecordingVoiceNote) {
                    // Ultra-Modern Voice Recording Live Bar with Frequency Waveforms (Galasavoy)
                    val formattedRecDuration = String.format(java.util.Locale.getDefault(), "%02d:%02d", recordingDurationSeconds / 60, recordingDurationSeconds % 60)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = EmergencyRed.copy(alpha = 0.22f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, EmergencyRed.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "🔴 $formattedRecDuration",
                                    color = EmergencyRed,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )

                                Spacer(modifier = Modifier.width(4.dp))

                                // LIVE ANIMATED VOICE AUDIO FREQUENCY WAVEFORM VISUALIZER
                                LiveAudioWaveformVisualizer(
                                    isRecording = true,
                                    width = 50.dp,
                                    height = 22.dp
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = {
                                        onSendTypingStatus("idle")
                                        onCancelVoiceNoteRecording()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF333344),
                                        contentColor = Color.LightGray
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text("Bekor", fontSize = 10.sp, maxLines = 1, softWrap = false)
                                }

                                Spacer(modifier = Modifier.width(4.dp))

                                Button(
                                    onClick = {
                                        onSendTypingStatus("idle")
                                        onStopVoiceNoteAndSend()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF00E676),
                                        contentColor = Color.Black
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text("YUBORISH 📤", fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = customMessage,
                            onValueChange = {
                                customMessage = it
                                if (it.isNotBlank()) {
                                    onSendTypingStatus("typing_text")
                                } else {
                                    onSendTypingStatus("idle")
                                }
                            },
                            singleLine = true,
                            cursorBrush = SolidColor(Color.White),
                            textStyle = TextStyle(
                                fontSize = 13.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .background(Color(0xFF121218).copy(alpha = 0.8f), RoundedCornerShape(10.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp),
                            decorationBox = { innerTextField ->
                                Box(
                                    contentAlignment = Alignment.CenterStart,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    if (customMessage.isEmpty()) {
                                        Text(
                                            text = "Xabaringizni yozing...",
                                            color = Color.Gray,
                                            fontSize = 13.sp
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        Button(
                            onClick = {
                                if (selectedRecipient.isBlank()) {
                                    onShowSelectFriendPrompt()
                                } else if (customMessage.isNotBlank()) {
                                    onSendTypingStatus("idle")
                                    onSendSos(customMessage, selectedRecipient)
                                    customMessage = ""
                                    focusManager.clearFocus()
                                }
                            },
                            enabled = customMessage.isNotBlank(),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmergencyRed,
                                disabledContainerColor = Color(0xFF333344)
                            ),
                            modifier = Modifier
                                .height(44.dp)
                                .width(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Yuborish",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // GALASAVOY BUTTON (Ovozli Xabar)
                        Button(
                            onClick = {
                                if (selectedRecipient.isBlank()) {
                                    onShowSelectFriendPrompt()
                                } else {
                                    onSendTypingStatus("typing_voice")
                                    onStartVoiceNoteRecording()
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00E676),
                                contentColor = Color.Black
                            ),
                            modifier = Modifier
                                .height(44.dp)
                                .width(42.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Galasavoy (Ovozli Xabar)",
                                tint = Color.Black,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1E2230)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "💬 Xabar yozish uchun chap tarafdagi SPISOK bo'limidan do'stingizni tanlang",
                        color = Color(0xFF00B0FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}
