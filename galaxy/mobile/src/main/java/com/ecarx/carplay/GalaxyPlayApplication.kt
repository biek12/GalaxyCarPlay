package com.ecarx.carplay

import com.shilapi.xcertplay.L7Application
import com.shilapi.xcertplay.GalaxyHostAdapter
import com.ecarx.carplay.product.GalaxyStartupDefaults

/** 继承既有产品生命周期；先落实L6默认值，再初始化本地配置/日志，不发起连接。 */
class GalaxyPlayApplication : L7Application() {
    override fun onCreate() {
        GalaxyHostAdapter.install()
        GalaxyStartupDefaults.initialize(this)
        super.onCreate()
    }
}
