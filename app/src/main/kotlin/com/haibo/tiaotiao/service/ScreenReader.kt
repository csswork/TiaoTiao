package com.haibo.tiaotiao.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.haibo.tiaotiao.core.*

class Observation(val screen: ScreenSnapshot, val handles: Map<String, AccessibilityNodeInfo>) : AutoCloseable {
    @Suppress("DEPRECATION") override fun close() { handles.values.forEach { it.recycle() } }
}

object ScreenReader {
    /** Traversal is bounded, editable/password subtrees are omitted, retained labels use an allowlist. */
    fun read(root: AccessibilityNodeInfo, versionCode: Long, width: Int, height: Int): Observation {
        val nodes = mutableListOf<ScreenNode>()
        val handles = linkedMapOf<String, AccessibilityNodeInfo>()
        var complete = true
        fun visit(node: AccessibilityNodeInfo, path: String, depth: Int): List<String> {
            handles[path] = node
            if (depth > 20 || handles.size >= 300 || node.childCount > 100) { complete = false; return emptyList() }
            if (!node.isVisibleToUser || node.isPassword || node.isEditable) return emptyList()
            val labels = listOf(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty())
                .map(SignalText::sanitize).filter(String::isNotBlank).toMutableList()
            for (index in 0 until node.childCount.coerceAtMost(100)) {
                if (handles.size >= 300) { complete = false; break }
                node.getChild(index)?.let { labels += visit(it, "$path.$index", depth + 1) }
            }
            val rect = Rect().also(node::getBoundsInScreen)
            val area = Area((rect.left.toFloat()/width).coerceIn(0f,1f), (rect.top.toFloat()/height).coerceIn(0f,1f),
                (rect.right.toFloat()/width).coerceIn(0f,1f), (rect.bottom.toFloat()/height).coerceIn(0f,1f))
            val id = node.viewIdResourceName.orEmpty().takeIf { it.matches(Regex("[A-Za-z0-9_.]+:id/[A-Za-z0-9_]+")) }.orEmpty()
            val ownLabel = if (node.isClickable) labels.distinct().take(8).joinToString(" | ") else
                listOf(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty()).map(SignalText::sanitize).filter(String::isNotBlank).distinct().joinToString(" | ")
            nodes += ScreenNode(path, id, node.className?.toString().orEmpty().take(160), ownLabel,
                node.isClickable && node.isEnabled && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }, area)
            return labels
        }
        try {
            visit(root, "0", 0)
            return Observation(ScreenSnapshot(root.packageName?.toString().orEmpty(), versionCode, width, height,
                nodes.sortedBy { it.path }, System.currentTimeMillis(), root.windowId, complete), handles)
        } catch (error: Exception) {
            @Suppress("DEPRECATION") handles.values.forEach { it.recycle() }
            throw error
        }
    }
}
