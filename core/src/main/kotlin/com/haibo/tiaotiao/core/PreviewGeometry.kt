package com.haibo.tiaotiao.core

object PreviewGeometry {
    fun imageSize(width: Float,height: Float,aspect: Float): Pair<Float,Float> {
        val w=minOf(width,height*aspect)
        return w to w/aspect
    }
    fun panBounds(width: Float,height: Float,aspect: Float,zoom: Float): Pair<Float,Float> {
        val (w,h)=imageSize(width,height,aspect)
        return ((w*zoom-width)/2).coerceAtLeast(0f) to ((h*zoom-height)/2).coerceAtLeast(0f)
    }
    /** Undo the exact fitted-image, centre-zoom and pan transform before saving coordinates. */
    fun point(x: Float,y: Float,width: Float,height: Float,aspect: Float,zoom: Float,panX: Float,panY: Float): Pair<Float,Float>? {
        if(!listOf(x,y,width,height,aspect,zoom,panX,panY).all {it.isFinite()} || width<=0 || height<=0 || aspect<=0 || zoom<1) return null
        val (w,h)=imageSize(width,height,aspect)
        val nx=((x-width/2-panX)/zoom+w/2)/w
        val ny=((y-height/2-panY)/zoom+h/2)/h
        return if(nx in 0f..1f && ny in 0f..1f) nx.coerceIn(.012f,.988f) to ny.coerceIn(.006f,.994f) else null
    }
}
