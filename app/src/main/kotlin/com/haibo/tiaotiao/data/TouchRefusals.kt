package com.haibo.tiaotiao.data

import android.content.Context
import com.haibo.tiaotiao.core.ruleJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Consecutive launches in which an app ignored TiaoTiao's accessibility touch on a credible skip control. */
@Serializable data class TouchRefusal(val packageName: String,val sessions: Int=0,val lastAt: Long=0,
    /** The user dismissed the dashboard reminder; the record still stops pointless retries. */
    val dismissed: Boolean=false) {
    val confirmed get()=sessions>=CONFIRM_SESSIONS
    companion object { const val CONFIRM_SESSIONS=2 }
}

/**
 * Apps that filter accessibility-injected touches (Android marks them for the receiving app), so only the enhanced
 * touch lane can close their launch ads. A later accessibility touch that does close an ad clears the record.
 */
class TouchRefusals(context: Context) {
    private val prefs=context.getSharedPreferences("touch_refusals",Context.MODE_PRIVATE)
    private val _items=MutableStateFlow(runCatching {
        ruleJson.decodeFromString<List<TouchRefusal>>(prefs.getString("items","[]").orEmpty())
    }.getOrDefault(emptyList()))
    val items=_items.asStateFlow()
    fun confirmed(packageName: String)=_items.value.any {it.packageName==packageName && it.confirmed}
    @Synchronized fun record(packageName: String,now: Long=System.currentTimeMillis()): TouchRefusal {
        val old=_items.value.find {it.packageName==packageName} ?: TouchRefusal(packageName)
        val next=old.copy(sessions=old.sessions+1,lastAt=now)
        save(_items.value.filterNot {it.packageName==packageName}+next)
        return next
    }
    @Synchronized fun clear(packageName: String) {
        if(_items.value.any {it.packageName==packageName}) save(_items.value.filterNot {it.packageName==packageName})
    }
    @Synchronized fun dismissAll() {if(_items.value.any {it.confirmed && !it.dismissed}) save(_items.value.map {if(it.confirmed) it.copy(dismissed=true) else it})}
    @Synchronized fun clear() {save(emptyList())}
    private fun save(next: List<TouchRefusal>) {
        val bounded=next.sortedByDescending {it.lastAt}.take(100)
        prefs.edit().putString("items",ruleJson.encodeToString(bounded)).apply();_items.value=bounded
    }
}
