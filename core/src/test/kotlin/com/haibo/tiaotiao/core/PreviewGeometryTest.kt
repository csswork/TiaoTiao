package com.haibo.tiaotiao.core

import org.junit.Assert.*
import org.junit.Test

class PreviewGeometryTest {
    @Test fun `fitted image margins do not become click coordinates`() {
        assertNull(PreviewGeometry.point(5f,200f,300f,400f,.5f,1f,0f,0f))
        val p=requireNotNull(PreviewGeometry.point(150f,200f,300f,400f,.5f,1f,0f,0f))
        assertEquals(.5f,p.first,.0001f);assertEquals(.5f,p.second,.0001f)
    }
    @Test fun `zoom and pan are inverted before saving the target`() {
        val p=requireNotNull(PreviewGeometry.point(90f,160f,300f,400f,.5f,3f,30f,20f))
        assertEquals(.35f,p.first,.0001f);assertEquals(.45f,p.second,.0001f)
    }
    @Test fun `image panning never exposes empty margins after zoom`() {
        val bounds=PreviewGeometry.panBounds(300f,400f,.5f,3f)
        assertEquals(150f,bounds.first,.0001f);assertEquals(400f,bounds.second,.0001f)
        assertNull(PreviewGeometry.point(Float.NaN,0f,300f,400f,.5f,1f,0f,0f))
    }
}
