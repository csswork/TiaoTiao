package com.haibo.tiaotiao.touch

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.haibo.tiaotiao.TiaoApplication

/** Only the locally authorized shell/root starter can hand a system Binder to this app. */
class OwnedTouchProvider : ContentProvider() {
    override fun onCreate()=true
    override fun call(method: String,arg: String?,extras: Bundle?): Bundle {
        if(Binder.getCallingUid() !in setOf(0,2000)) throw SecurityException("Only the authorized local starter is accepted")
        val data=requireNotNull(extras)
        require(method=="attach" && data.getInt("protocol")==2)
        val binder=requireNotNull(data.getBinder("service"))
        require(binder.interfaceDescriptor==ISystemTouch::class.java.name && binder.pingBinder())
        val app=requireNotNull(context).applicationContext as TiaoApplication
        app.enhancedTouch.attachOwned(binder)
        return Bundle().apply {putBinder("client",app.enhancedTouch.clientLife)}
    }
    override fun query(uri: Uri,projection: Array<out String>?,selection: String?,args: Array<out String>?,sortOrder: String?): Cursor?=null
    override fun getType(uri: Uri): String?=null
    override fun insert(uri: Uri,values: ContentValues?): Uri?=null
    override fun delete(uri: Uri,selection: String?,args: Array<out String>?)=0
    override fun update(uri: Uri,values: ContentValues?,selection: String?,args: Array<out String>?)=0
}
