package com.darmawan.dronebridge

import android.app.Application
import android.content.Context
import com.cySdkyc.clx.Helper

class MApplication : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        Helper.install(this) // WAJIB untuk MSDK V5, harus di sini
    }

    override fun onCreate() {
        super.onCreate()
        DjiSdk.init(this)    // SDK hidup di level Application, bukan Activity
    }
}
