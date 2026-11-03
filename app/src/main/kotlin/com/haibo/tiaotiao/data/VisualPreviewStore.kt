package com.haibo.tiaotiao.data

import android.graphics.BitmapFactory
import android.util.AtomicFile
import com.haibo.tiaotiao.core.*
import com.haibo.tiaotiao.service.VisualFrame
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable enum class PreviewKind { RECOGNITION, ACTION, MANUAL }
@Serializable data class VisualPreview(val ruleId: String,val kind: PreviewKind,val file: String,val width: Int,val height: Int,
    val area: Area,val at: Long,val contextHash: String="")

/** Local masked evidence; neither images nor this index are part of rule exports. */
class VisualPreviewStore(files: File) {
    private val folder=File(files,"visual_previews").apply {mkdirs()}
    private val index=AtomicFile(File(folder,"index.json"))
    private val _entries=MutableStateFlow(runCatching {ruleJson.decodeFromString<List<VisualPreview>>(String(index.readFully()))}
        .getOrDefault(emptyList()).filter {safe(it.file) && File(folder,it.file).exists()})
    val entries=_entries.asStateFlow()
    private fun safe(name: String)=name.matches(Regex("[A-Za-z0-9_-]{1,100}-(?:RECOGNITION|ACTION|MANUAL)\\.jpg"))
    fun file(preview: VisualPreview): File {
        require(safe(preview.file) && preview in _entries.value) {"截图已更新，请重新打开"}
        return File(folder,preview.file)
    }
    @Synchronized fun save(id: String,jpeg: ByteArray,area: Area,kind: PreviewKind,contextHash: String="") {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")) && jpeg.size in 1..700_000 && area.valid())
        val options=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(jpeg,0,jpeg.size,options)
        require(options.outWidth in 1..4096 && options.outHeight in 1..4096)
        val name="$id-${kind.name}.jpg"
        val image=AtomicFile(File(folder,name));val stream=image.startWrite()
        try {stream.write(jpeg);image.finishWrite(stream)} catch(t: Throwable) {image.failWrite(stream);throw t}
        commit(_entries.value.filterNot {it.ruleId==id && it.kind==kind}+VisualPreview(id,kind,name,options.outWidth,options.outHeight,area,System.currentTimeMillis(),contextHash))
    }
    fun patch(jpeg: ByteArray,area: Area): String {
        val bitmap=requireNotNull(BitmapFactory.decodeByteArray(jpeg,0,jpeg.size))
        return VisualFrame(ScreenSnapshot("preview.local",0,bitmap.width,bitmap.height,emptyList(),0,0),
            bitmap,VisualSignals(false,null,emptyList(),false),jpeg,0).use {it.patch(area)}
    }
    @Synchronized fun remove(id: String) {
        _entries.value.filter {it.ruleId==id}.forEach {File(folder,it.file).delete()}
        commit(_entries.value.filterNot {it.ruleId==id})
    }
    @Synchronized fun clear() {folder.listFiles().orEmpty().forEach {it.delete()};commit(emptyList())}
    private fun commit(entries: List<VisualPreview>) {
        val stream=index.startWrite()
        try {stream.write(ruleJson.encodeToString(entries).toByteArray());index.finishWrite(stream)}
        catch(t: Throwable) {index.failWrite(stream);throw t}
        _entries.value=entries
    }
}
