package com.haibo.tiaotiao.data

import android.content.Context
import com.haibo.tiaotiao.core.ruleJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** A launch ad that opened another app by itself (shake/flip sensors), not through a TiaoTiao touch. */
@Serializable data class AdJump(val packageName: String,val count: Int=0,val lastAt: Long=0,val lastTarget: String="",val dismissedCount: Int=0) {
    val unread get()=count>dismissedCount
}

/** Per-app record that drives the sensor pause and the dashboard reminder. Kept apart from rule exports. */
class AdJumps(context: Context) {
    private val prefs=context.getSharedPreferences("ad_jumps",Context.MODE_PRIVATE)
    private val _items=MutableStateFlow(runCatching {
        ruleJson.decodeFromString<List<AdJump>>(prefs.getString("items","[]").orEmpty())
    }.getOrDefault(emptyList()))
    val items=_items.asStateFlow()
    fun known(packageName: String)=_items.value.any {it.packageName==packageName && it.count>0}
    @Synchronized fun record(packageName: String,target: String,now: Long=System.currentTimeMillis()) {
        val old=_items.value.find {it.packageName==packageName} ?: AdJump(packageName)
        save(_items.value.filterNot {it.packageName==packageName}+old.copy(count=old.count+1,lastAt=now,lastTarget=target.take(40)))
    }
    @Synchronized fun dismiss(packageName: String) {
        save(_items.value.map {if(it.packageName==packageName) it.copy(dismissedCount=it.count) else it})
    }
    @Synchronized fun clear() {save(emptyList())}
    private fun save(next: List<AdJump>) {
        val bounded=next.sortedByDescending {it.lastAt}.take(100)
        prefs.edit().putString("items",ruleJson.encodeToString(bounded)).apply();_items.value=bounded
    }
}
