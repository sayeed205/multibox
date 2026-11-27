package com.hitarashi.multibox.data.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

enum class LogCategory(val tag: String) {
    AUDIO("INFO/AUDIO"),
    PLAYBACK("INFO/PLAYBACK"),
    DAC("INFO/DAC"),
    SERVICE("INFO/SERVICE"),
    ERROR("ERROR/PLAYER")
}

data class PlayerLogEntry(
    val timestamp: String,
    val category: LogCategory,
    val message: String
)

@Singleton
class PlayerLogBuffer @Inject constructor() {
    private val maxEntries = 200
    private val buffer = ConcurrentLinkedDeque<PlayerLogEntry>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _logsFlow = MutableStateFlow<List<PlayerLogEntry>>(emptyList())
    val logsFlow: StateFlow<List<PlayerLogEntry>> = _logsFlow.asStateFlow()

    init {
        log(LogCategory.SERVICE, "PlayerLogBuffer initialized")
    }

    fun log(category: LogCategory, message: String) {
        val entry = PlayerLogEntry(
            timestamp = timeFormat.format(Date()),
            category = category,
            message = message
        )
        buffer.addFirst(entry)
        while (buffer.size > maxEntries) {
            buffer.removeLast()
        }
        _logsFlow.update { buffer.toList() }
    }

    fun logAudio(message: String) = log(LogCategory.AUDIO, message)
    fun logPlayback(message: String) = log(LogCategory.PLAYBACK, message)
    fun logDac(message: String) = log(LogCategory.DAC, message)
    fun logService(message: String) = log(LogCategory.SERVICE, message)
    fun logError(message: String) = log(LogCategory.ERROR, message)

    fun clear() {
        buffer.clear()
        _logsFlow.value = emptyList()
    }
}
