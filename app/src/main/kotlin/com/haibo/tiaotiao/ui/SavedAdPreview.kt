package com.haibo.tiaotiao.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.haibo.tiaotiao.TiaoApplication
import com.haibo.tiaotiao.core.VisualRule
import com.haibo.tiaotiao.core.PreviewGeometry
import com.haibo.tiaotiao.data.*
import kotlinx.coroutines.*
import kotlin.math.roundToInt

@Composable internal fun AdRuleThumbnail(item: RuleItem) {
    val store=(LocalContext.current.applicationContext as TiaoApplication).repository.previews
    val entries by store.entries.collectAsState()
    val preview=entries.filter {p -> item.variants.any {it.id==p.ruleId}}.maxByOrNull {it.at}
    val bitmap by produceState<Bitmap?>(null,preview?.file,preview?.at) {
        var owned: Bitmap?=null
        try {
            if(preview!=null) withContext(Dispatchers.IO) {owned=runCatching {
                BitmapFactory.decodeFile(store.file(preview).absolutePath,BitmapFactory.Options().apply {inSampleSize=4})
            }.getOrNull()}
            value=owned;awaitCancellation()
        } finally {value=null;owned?.recycle()}
    }
    Box(Modifier.width(64.dp).height(136.dp),contentAlignment=Alignment.Center) {
        val image=bitmap
        if(image!=null && preview!=null) {
            val ratio=preview.width.toFloat()/preview.height
            MarkedAdImage(image,preview.area.center(),Modifier.width(minOf(64.dp,136.dp*ratio)).aspectRatio(ratio)
                .semantics {contentDescription="实际广告缩略图"},markRadius=4.dp)
        } else Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
            QingIcon(QingGlyph.RULES,size=24.dp)
            Text("暂无截图",style=MaterialTheme.typography.labelSmall,color=Color.Gray)
        }
    }
}

@Composable internal fun SavedAdPreviews(rule: VisualRule,busy: Boolean,
    onAdjust: (VisualRule,VisualPreview,Float,Float) -> Unit,fallback: List<VisualPreview> = emptyList()) {
    val store=(LocalContext.current.applicationContext as TiaoApplication).repository.previews
    val all by store.entries.collectAsState()
    val own=all.filter {it.ruleId==rule.id}
    val previews=(own.ifEmpty {fallback.sortedByDescending {it.at}.take(1)}).sortedByDescending {it.at}
    if(previews.isEmpty()) {
        Text("尚无保存截图；下次识别或点击时会自动补充。",style=MaterialTheme.typography.bodySmall)
        return
    }
    Text("广告实际截图",style=MaterialTheme.typography.titleMedium)
    if(own.isEmpty()) Text("这个旧样本没有原图，以下使用本任务最近广告截图作参考。",style=MaterialTheme.typography.bodySmall)
    Text("双指或双击放大，拖动画面；单击图片选择新的点击位置。",style=MaterialTheme.typography.bodySmall)
    for(preview in previews) {
        val bitmap by produceState<Bitmap?>(null,preview.file,preview.at) {
            var owned: Bitmap?=null
            try {
                withContext(Dispatchers.IO) {owned=runCatching {BitmapFactory.decodeFile(store.file(preview).absolutePath)}.getOrNull()}
                value=owned;awaitCancellation()
            } finally {value=null;owned?.recycle()}
        }
        val image=bitmap
        if(image!=null) {
            var expanded by remember(preview.file) {mutableStateOf(false)}
            var editing by remember(preview.file,preview.at,rule.behavior()) {mutableStateOf(false)}
            var point by remember(preview.file,preview.at,rule.behavior()) {mutableStateOf(rule.target.center())}
            val label=if(own.isEmpty()) "参考广告截图" else when(preview.kind) {
                PreviewKind.MANUAL -> if(rule.userAdjusted) "手动调整后的方案位置" else "历史手动调整位置";PreviewKind.ACTION -> "最近实际点击";PreviewKind.RECOGNITION -> "识别时的位置"
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(label,Modifier.weight(1f),style=MaterialTheme.typography.labelLarge)
                TextButton(onClick={editing=true;point=rule.target.center()},enabled=!busy) {Text("调整点击位置")}
            }
            val displayedArea=if(own.isEmpty()) rule.target else preview.area
            ZoomableAdImage(image,if(editing) point else displayedArea.center(),Modifier.fillMaxWidth().height(340.dp)) {
                if(!busy) {editing=true;point=it}
            }
            if(editing) {
                Text("距左侧 ${(point.x*100).roundToInt()}% · 距顶部 ${(point.y*100).roundToInt()}%",style=MaterialTheme.typography.bodySmall)
                Text("点选后保存；旧验证会清除，也可使用“确认方案正确”直接启用。",style=MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick={editing=false},enabled=!busy) {Text("取消调整")}
                    Button(onClick={onAdjust(rule,preview,point.x,point.y);editing=false},enabled=!busy) {Text("保存点击位置")}
                }
            }
            TextButton(onClick={expanded=true}) {Text("全屏查看")}
            if(expanded) PreviewDialog(image,displayedArea.center()) {expanded=false}
        }
    }
}

private fun com.haibo.tiaotiao.core.Area.center()=Offset((left+right)/2,(top+bottom)/2)

@Composable private fun MarkedAdImage(bitmap: Bitmap,point: Offset,modifier: Modifier,
    markRadius: androidx.compose.ui.unit.Dp=12.dp) {
    val image=remember(bitmap) {bitmap.asImageBitmap()}
    Box(modifier) {
        Image(image,"已遮挡文字的广告截图",Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val center=Offset(point.x*size.width,point.y*size.height)
            val radius=markRadius.toPx();val arm=radius*1.4f
            drawCircle(Color.White,radius,center,style=Stroke(4.dp.toPx()))
            drawCircle(Color(0xFFE53935),radius,center,style=Stroke(2.dp.toPx()))
            drawLine(Color(0xFFE53935),center-Offset(arm,0f),center+Offset(arm,0f),2.dp.toPx())
            drawLine(Color(0xFFE53935),center-Offset(0f,arm),center+Offset(0f,arm),2.dp.toPx())
        }
    }
}

@Composable private fun ZoomableAdImage(bitmap: Bitmap,point: Offset,modifier: Modifier,onPoint: ((Offset)->Unit)?=null) {
    var zoom by remember(bitmap) {mutableFloatStateOf(1f)}
    var pan by remember(bitmap) {mutableStateOf(Offset.Zero)}
    var viewport by remember(bitmap) {mutableStateOf(IntSize.Zero)}
    val select by rememberUpdatedState(onPoint)
    val aspect=bitmap.width.toFloat()/bitmap.height
    val transform=rememberTransformableState {factor,delta,_ ->
        val next=(zoom*factor).coerceIn(1f,5f)
        val shifted=pan*(next/zoom)+delta
        val (bx,by)=PreviewGeometry.panBounds(viewport.width.toFloat(),viewport.height.toFloat(),aspect,next)
        pan=Offset(shifted.x.coerceIn(-bx,bx),shifted.y.coerceIn(-by,by));zoom=next
    }
    BoxWithConstraints(modifier.clipToBounds().background(Color(0xFFE9EEE9))
        .onSizeChanged {viewport=it}
        .semantics {contentDescription="广告缩放画布"}
        .transformable(transform,canPan={zoom>1f})
        .pointerInput(bitmap) {
            detectTapGestures(onDoubleTap={tap ->
                val next=if(zoom>1f) 1f else 3f;val ratio=next/zoom
                val center=Offset(size.width/2f,size.height/2f)
                val shifted=pan*ratio+(tap-center)*(1-ratio)
                val (bx,by)=PreviewGeometry.panBounds(size.width.toFloat(),size.height.toFloat(),aspect,next)
                pan=Offset(shifted.x.coerceIn(-bx,bx),shifted.y.coerceIn(-by,by));zoom=next
            },onTap={tap ->
                PreviewGeometry.point(tap.x,tap.y,size.width.toFloat(),size.height.toFloat(),aspect,zoom,pan.x,pan.y)?.let {select?.invoke(Offset(it.first,it.second))}
            })
        },contentAlignment=Alignment.Center) {
        val width=minOf(maxWidth,maxHeight*aspect)
        MarkedAdImage(bitmap,point,Modifier.size(width,width/aspect).graphicsLayer {
            scaleX=zoom;scaleY=zoom;translationX=pan.x;translationY=pan.y
        }.semantics {contentDescription=if(select!=null) "可调整的广告点击位置" else "广告实际截图画面"},markRadius=12.dp/zoom)
        Text("缩放 ${(zoom*100).roundToInt()}%",Modifier.align(Alignment.BottomEnd).background(Color.White.copy(alpha=.9f)).padding(6.dp),style=MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun PreviewDialog(bitmap: Bitmap,point: Offset,onDismiss: ()->Unit) {
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.94f),shape=MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("广告截图与点击位置",style=MaterialTheme.typography.titleLarge)
                Text("双指或双击放大，拖动查看。调整位置请使用详情页。",style=MaterialTheme.typography.bodySmall)
                ZoomableAdImage(bitmap,point,Modifier.weight(1f).fillMaxWidth())
                TextButton(onClick=onDismiss) {Text("关闭")}
            }
        }
    }
}
