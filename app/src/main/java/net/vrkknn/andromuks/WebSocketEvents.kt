package net.vrkknn.andromuks

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

object WebSocketEvents {
    private val _messages = MutableSharedFlow<String>()
    val messages = _messages.asSharedFlow()

    @OptIn(DelicateCoroutinesApi::class)
    fun newRawMessage(message: String) {

        GlobalScope.launch { // TODO: don't use this
            _messages.emit(message)
        }
    }
}