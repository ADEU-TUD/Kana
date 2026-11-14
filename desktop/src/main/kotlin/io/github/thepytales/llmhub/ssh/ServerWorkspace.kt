package io.github.thepytales.llmhub.ssh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * Several sessions side by side in tabs (AP-131 c): each tab is its own [ServerTerminal] with its own connection,
 * terminal, files and Lora – Lora always helps in the tab that is open. A line goes to several sessions only through
 * [broadcast], which the screen calls after the user confirmed the exact list.
 */
class ServerWorkspace(val dir: File, private val helper: () -> ServerHelper? = { null }) {
    private val _tabs = MutableStateFlow(listOf(ServerTerminal(dir, helper())))
    val tabs: StateFlow<List<ServerTerminal>> = _tabs
    private val _active = MutableStateFlow(0)
    val active: StateFlow<Int> = _active

    val current: ServerTerminal get() = _tabs.value[_active.value.coerceIn(0, _tabs.value.lastIndex)]

    /** A new, not yet connected tab; it becomes the open one. */
    fun add(): ServerTerminal {
        val t = ServerTerminal(dir, helper())
        _tabs.update { it + t }
        _active.value = _tabs.value.lastIndex
        return t
    }

    fun select(index: Int) {
        if (index in _tabs.value.indices) _active.value = index
    }

    /** Closes the tab (and its connection); there is always at least one tab. */
    fun close(index: Int) {
        val t = _tabs.value.getOrNull(index) ?: return
        t.close()
        _tabs.update { list -> (list - t).ifEmpty { listOf(ServerTerminal(dir, helper())) } }
        _active.update { a -> if (a > index || a > _tabs.value.lastIndex) (a - 1).coerceAtLeast(0) else a }
    }

    /** The tabs with an open connection and terminal – the ones a line can go to. */
    fun connected(): List<ServerTerminal> = _tabs.value.filter { it.state.value.connected && it.tty != null }

    /** Types [line] with Enter into each of [targets] – only after the user confirmed exactly these. Returns how many. */
    fun broadcast(line: String, targets: List<ServerTerminal>): Int = targets.distinct().count { it in _tabs.value && it.sendLine(line) }

    fun closeAll() {
        _tabs.value.forEach { it.close() }
        _tabs.value = listOf(ServerTerminal(dir, helper()))
        _active.value = 0
    }
}
