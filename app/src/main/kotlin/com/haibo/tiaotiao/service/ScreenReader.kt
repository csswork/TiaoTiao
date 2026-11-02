package com.haibo.tiaotiao.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.haibo.tiaotiao.core.*

class Observation(val screen: ScreenSnapshot, val handles: Map<String, AccessibilityNodeInfo>) : AutoCloseable {
    @Suppress("DEPRECATION") override fun close() { handles.values.forEach { it.recycle() } }
}

object ScreenReader {
    private data class Task(val parent: AccessibilityNodeInfo?,val index: Int,val path: String,val depth: Int)
    private data class Meta(val node: ScreenNode,val ownLabels: List<String>,val legacyLabels: List<String>,val aggregates: Boolean)

    /** Breadth first: inspect overlay siblings before spending the budget inside long content lists. */
    fun read(root: AccessibilityNodeInfo, versionCode: Long, width: Int, height: Int, nodeLimit: Int = 300,
        budgetMillis: Long = Long.MAX_VALUE): Observation {
        val started=android.os.SystemClock.elapsedRealtime()
        val handles=linkedMapOf("0" to root)
        val metadata=linkedMapOf<String,Meta>()
        val queue=ArrayDeque<Task>().apply {add(Task(null,0,"0",0))}
        var complete=true
        try {
            while(queue.isNotEmpty()) {
                if(android.os.SystemClock.elapsedRealtime()-started>=budgetMillis) {complete=false;break}
                val task=queue.removeFirst()
                if(task.parent!=null && handles.size>=nodeLimit) {complete=false;break}
                val node=if(task.parent==null) root else
                    (if(android.os.Build.VERSION.SDK_INT>=33) task.parent.getChild(task.index,0) else task.parent.getChild(task.index))
                if(node==null) {complete=false;continue}
                handles[task.path]=node
                if(task.depth>20) {complete=false;continue}
                if(!node.isVisibleToUser || node.isPassword || node.isEditable) continue
                val raw=listOf(node.text?.toString().orEmpty(),node.contentDescription?.toString().orEmpty())
                val own=raw.map(SignalText::sanitize).filter(String::isNotBlank).distinct()
                val legacy=raw.map(SignalText::legacySanitize).filter(String::isNotBlank).distinct()
                val rect=Rect().also(node::getBoundsInScreen)
                val area=Area((rect.left.toFloat()/width).coerceIn(0f,1f),(rect.top.toFloat()/height).coerceIn(0f,1f),
                    (rect.right.toFloat()/width).coerceIn(0f,1f),(rect.bottom.toFloat()/height).coerceIn(0f,1f))
                val id=node.viewIdResourceName.orEmpty().takeIf {it.matches(Regex("[A-Za-z0-9_.]+:id/[A-Za-z0-9_]+"))}.orEmpty()
                metadata[task.path]=Meta(ScreenNode(task.path,id,node.className?.toString().orEmpty().take(160),"",
                    node.isClickable && node.isEnabled && node.actionList.any {it.id==AccessibilityNodeInfo.ACTION_CLICK},area),own,legacy,node.isClickable)
                val count=node.childCount
                if(count>100) complete=false
                // Fetch child references lazily, alternating both edges to include overlay siblings.
                repeat(minOf(count,100)) { offset ->
                    val index=if(offset%2==0) offset/2 else count-1-offset/2
                    queue.add(Task(node,index,task.path+"."+index,task.depth+1))
                }
            }
            val children=metadata.keys.filter {it!="0"}.groupBy {it.substringBeforeLast('.')}
            val aggregate=mutableMapOf<String,List<String>>()
            val legacyAggregate=mutableMapOf<String,List<String>>()
            fun labels(path: String,legacy: Boolean=false): List<String> = (if(legacy) legacyAggregate else aggregate).getOrPut(path) {
                val meta=metadata.getValue(path)
                (if(legacy) meta.legacyLabels else meta.ownLabels) + children[path].orEmpty().sortedBy {it.substringAfterLast('.').toInt()}.flatMap {labels(it,legacy)}
            }
            if("0" in metadata) {labels("0");labels("0",true)}
            val nodes=metadata.map { (path,meta) ->
                meta.node.copy(label=if(meta.aggregates) aggregate[path].orEmpty().distinct().take(8).joinToString(" | ")
                    else meta.ownLabels.joinToString(" | "),
                    legacyLabel=if(meta.aggregates) legacyAggregate[path].orEmpty().distinct().take(8).joinToString(" | ") else meta.legacyLabels.joinToString(" | "))
            }.sortedBy {it.path}
            return Observation(ScreenSnapshot(root.packageName?.toString().orEmpty(),versionCode,width,height,
                nodes,System.currentTimeMillis(),root.windowId,complete),handles)
        } catch(error: Exception) {
            @Suppress("DEPRECATION") handles.values.forEach {it.recycle()}
            throw error
        }
    }
}
