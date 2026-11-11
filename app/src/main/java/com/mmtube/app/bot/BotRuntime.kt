package com.mmtube.app.bot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live state of the owner bot, shown in the hidden owner screen. */
data class BotStatus(
    val running: Boolean = false,
    val username: String? = null,
    val ownerChatId: Long? = null,
    val lastError: String? = null,
    val lastActivityAt: Long = 0L,
    val jobTitle: String? = null,
    val jobProgress: Float = 0f,
    val jobLabel: String? = null
)

object BotRuntime {

    private val _status = MutableStateFlow(BotStatus())
    val status: StateFlow<BotStatus> = _status.asStateFlow()

    fun update(transform: (BotStatus) -> BotStatus) {
        _status.value = transform(_status.value)
    }

    fun reset() {
        _status.value = BotStatus()
    }
}
