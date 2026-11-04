package com.haibo.tiaotiao.data

import android.content.Context
import com.haibo.tiaotiao.core.ruleJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.LocalDate

@Serializable data class SkipEvent(val packageName: String,val at: Long)
@Serializable data class SkipTally(val day: String="",val today: Int=0,val total: Int=0,val recent: List<SkipEvent> = emptyList())

/** Ads actually closed by TiaoTiao: what the dashboard reports, apart from the diagnostic log. */
class SkipStats(context: Context) {
    private val prefs=context.getSharedPreferences("skip_stats",Context.MODE_PRIVATE)
    private val _tally=MutableStateFlow(rolled(runCatching {
        ruleJson.decodeFromString<SkipTally>(prefs.getString("tally","").orEmpty())
    }.getOrDefault(SkipTally())))
    val tally=_tally.asStateFlow()
    private fun rolled(t: SkipTally)=LocalDate.now().toString().let {if(t.day==it) t else t.copy(day=it,today=0)}
    @Synchronized fun record(packageName: String,now: Long=System.currentTimeMillis()) {
        val t=rolled(_tally.value)
        save(t.copy(today=t.today+1,total=t.total+1,recent=(listOf(SkipEvent(packageName,now))+t.recent).take(30)))
    }
    /** Re-evaluates the day boundary when the dashboard is opened after midnight. */
    @Synchronized fun refresh() {if(_tally.value.day!=LocalDate.now().toString()) save(rolled(_tally.value))}
    @Synchronized fun clear() {save(SkipTally(day=LocalDate.now().toString()))}
    private fun save(t: SkipTally) {prefs.edit().putString("tally",ruleJson.encodeToString(t)).apply();_tally.value=t}
}
