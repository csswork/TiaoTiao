package com.haibo.tiaotiao.touch

internal class PairingInput(val port: Int,val code: String) {
    override fun toString()="PairingInput(port=$port, code=REDACTED)"
    companion object {
        fun parse(text: String,discoveredPort: Int,manualPort: Int?=null): PairingInput? {
            val words=text.trim().split(Regex("\\s+"))
            if(words.size !in 1..2) return null
            val code=words.last()
            if(!code.matches(Regex("[0-9]{6}"))) return null
            val explicit=if(words.size==2) words.first().toIntOrNull() ?: return null else null
            val port=manualPort ?: explicit ?: discoveredPort
            return if(port in 1..65535) PairingInput(port,code) else null
        }
    }
}
