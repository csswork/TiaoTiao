package com.haibo.tiaotiao

import android.app.Application
import com.haibo.tiaotiao.data.AppRepository

class TiaoApplication : Application() {
    val repository by lazy { AppRepository(this) }
}
