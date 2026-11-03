package com.haibo.tiaotiao.touch

import android.content.AttributionSource
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Process

/** app_process is not an AMS-managed application; acquire the provider through shell's API. */
internal object ShellProviderLink {
    fun attach(authority: String,extras: Bundle): Bundle? {
        val manager=Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        val api=Class.forName("android.app.IActivityManager")
        val token=Binder()
        val acquire=api.methods.first {it.name=="getContentProviderExternal"}
        val acquireArgs=if(acquire.parameterCount==4) arrayOf(authority,0,token,"TiaoTiao") else arrayOf(authority,0,token)
        val holder=requireNotNull(acquire.invoke(manager,*acquireArgs))
        try {
            val provider=holder.javaClass.getField("provider").get(holder)
            val call=Class.forName("android.content.IContentProvider").methods.filter {it.name=="call"}.maxBy {it.parameterCount}
            val arguments: Array<out Any?> = when {
                Build.VERSION.SDK_INT>=31 && call.parameterCount==5 -> arrayOf(AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build(),authority,"attach",null,extras)
                Build.VERSION.SDK_INT>=31 -> arrayOf(AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build(),"attach",null,extras)
                call.parameterCount==6 -> arrayOf("com.android.shell",null,authority,"attach",null,extras)
                call.parameterCount==5 -> arrayOf("com.android.shell",authority,"attach",null,extras)
                else -> arrayOf("com.android.shell","attach",null,extras)
            }
            return call.invoke(provider,*arguments) as? Bundle
        } finally {
            runCatching {
                val release=api.methods.firstOrNull {it.name=="removeContentProviderExternalAsUser"}
                if(release!=null) release.invoke(manager,authority,token,0)
                else api.methods.first {it.name=="removeContentProviderExternal"}.invoke(manager,authority,token)
            }
        }
    }
}
