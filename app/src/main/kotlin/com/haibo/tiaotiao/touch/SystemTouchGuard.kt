package com.haibo.tiaotiao.touch

internal object SystemTouchGuard {
    fun allows(pkg: String,x: Int,y: Int,width: Int,height: Int,windowDump: String): Boolean {
        return rejection(pkg,x,y,width,height,windowDump)==null
    }
    fun rejection(pkg: String,x: Int,y: Int,width: Int,height: Int,windowDump: String): String? {
        if(width !in 1..16384 || height !in 1..16384 || x !in 0 until width || y !in 0 until height) return "invalid_coordinates"
        val current=windowDump.lineSequence().firstOrNull {"mCurrentFocus=" in it}.orEmpty()
        if(current.isEmpty() || current.substringAfter("mCurrentFocus=").trim()=="null") return "focus_unavailable"
        if(!current.contains(" $pkg/")) return "foreground_changed"
        val size=Regex("\\bcur=(\\d+)x(\\d+)\\b").find(windowDump) ?: return "display_unavailable"
        return if(size.groupValues[1].toIntOrNull()==width && size.groupValues[2].toIntOrNull()==height) null else "display_changed"
    }
}
