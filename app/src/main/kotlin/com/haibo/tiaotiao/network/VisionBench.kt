package com.haibo.tiaotiao.network

import com.haibo.tiaotiao.core.VisionProvider
import com.haibo.tiaotiao.data.AppRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.io.File

/** Debug-only: runs both official image providers over the same labeled images. Keys never leave the vault. */
internal object VisionBench {
    @Volatile var status="idle"
        private set
    suspend fun run(dir: File,repository: AppRepository,repeats: Int) {
        val client=VisualClient()
        val keys=mapOf(VisionProvider.JEV_OFFICIAL to repository.vault.get("jevKey"),VisionProvider.DEEPSEEK_OFFICIAL to repository.vault.get("deepseekKey"))
        val images=File(dir,"recognize").listFiles().orEmpty().filter {it.extension=="jpg"}.sortedBy {it.name}
        val cases=File(dir,"verify").listFiles().orEmpty().filter {it.isDirectory}.sortedBy {it.name}
        val out=File(dir,"results.jsonl").apply {delete()}
        var done=0;val total=(images.size+cases.size)*keys.size*repeats
        for(round in 1..repeats) for((provider,key) in keys) {
            for(image in images) {
                status="running $done/$total"
                val row=buildJsonObject {
                    put("kind","recognize");put("name",image.name);put("provider",provider.name);put("round",round)
                    try {
                        val d=client.recognize(image.readBytes(),key,provider.defaultModel,provider)
                        put("advertisement",d.advertisement);put("confidence",d.confidence);put("label",d.label)
                        put("left",d.left);put("top",d.top);put("right",d.right);put("bottom",d.bottom)
                        put("accepted",d.target()!=null);put("elapsedMs",d.elapsedMs ?: -1);put("inputTokens",d.inputTokens ?: -1)
                    } catch(c: CancellationException) {throw c} catch(e: Exception) {put("error",e.message ?: e.javaClass.simpleName)}
                }
                out.appendText(row.toString()+"\n");done++
            }
            for(case in cases) {
                status="running $done/$total"
                val row=buildJsonObject {
                    put("kind","verify");put("name",case.name);put("provider",provider.name);put("round",round)
                    try {
                        val v=client.verify((0..2).map {File(case,"$it.jpg").readBytes()},key,provider.defaultModel,provider=provider)
                        put("normalHome",v.normalHome);put("advertisementGone",v.advertisementGone);put("landingPage",v.landingPage)
                        put("confidence",v.confidence);put("accepted",v.accepted());put("certain",v.certain())
                        put("elapsedMs",v.elapsedMs ?: -1);put("inputTokens",v.inputTokens ?: -1)
                    } catch(c: CancellationException) {throw c} catch(e: Exception) {put("error",e.message ?: e.javaClass.simpleName)}
                }
                out.appendText(row.toString()+"\n");done++
            }
        }
        status="done $done/$total"
    }
}
