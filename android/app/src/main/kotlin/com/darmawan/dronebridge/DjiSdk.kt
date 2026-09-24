package com.darmawan.dronebridge

import android.content.Context
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback

object DjiSdk {
    @Volatile var registered = false
    @Volatile var productConnected = false

    fun init(ctx: Context) {
        SDKManager.getInstance().init(ctx, object : SDKManagerCallback {
            override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) {
                if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                    SDKManager.getInstance().registerApp()
                }
            }
            override fun onRegisterSuccess() {
                registered = true; BridgeEvents.sdk("SDK registered")
            }
            override fun onRegisterFailure(error: IDJIError?) {
                registered = false; BridgeEvents.sdk("Register gagal: ${error?.description()}")
            }
            override fun onProductConnect(productId: Int) {
                productConnected = true; BridgeEvents.sdk("Drone terhubung (id=$productId)")
            }
            override fun onProductDisconnect(productId: Int) {
                productConnected = false; BridgeEvents.sdk("Drone terputus")
            }
            override fun onProductChanged(productId: Int) {}
            override fun onDatabaseDownloadProgress(current: Long, total: Long) {}
        })
    }
}
