package com.haibo.tiaotiao.touch

internal object SystemTouchGuard {
    fun allows(pkg: String,x: Int,y: Int,width: Int,height: Int,windowDump: String): Boolean {
        if(width !in 1..16384 || height !in 1..16384 || x !in 0 until width || y !in 0 until height) return false
        val current=windowDump.lineSequence().firstOrNull {"mCurrentFocus=" in it}.orEmpty()
        if(!current.contains(" $pkg/")) return false
        val size=Regex("\\bcur=(\\d+)x(\\d+)\\b").find(windowDump) ?: return false
        return size.groupValues[1].toIntOrNull()==width && size.groupValues[2].toIntOrNull()==height
    }
}
