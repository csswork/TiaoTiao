package com.haibo.tiaotiao.core

data class WindowCandidate(val id: Int, val application: Boolean, val focused: Boolean, val active: Boolean)

/** OEMs sometimes omit both focus flags. A unique application window is still unambiguous. */
object WindowSelection {
    fun choose(windows: List<WindowCandidate>, eventWindowId: Int?): Int? {
        val applications = windows.filter { it.application }
        return applications.singleOrNull { it.focused }?.id
            ?: applications.singleOrNull { it.active }?.id
            ?: applications.singleOrNull { it.id == eventWindowId }?.id
            ?: applications.singleOrNull()?.id
    }
}
