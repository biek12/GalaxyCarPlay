package com.galaxy.auto.client.vehicle

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import com.galaxy.auto.client.databinding.ActivityVehicleAccountBinding

/**
 * 两个 App 共用的车辆账号页。
 *
 * 只固定密度、装上 XML，并把生命周期交给 [VehicleAccountPresenter]。
 * 不解析 JSON，不发账号请求，也不保存设备凭据或账号所有权。
 */
class VehicleAccountActivity : Activity() {
    private lateinit var presenter: VehicleAccountPresenter

    /** 固定 280dpi，使返回键、按钮和二维码沿用已审定的触控尺度。 */
    override fun attachBaseContext(base: Context) {
        val config = Configuration(base.resources.configuration)
        config.densityDpi = DENSITY_DPI
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    /**
     * 先装布局并绑定点击，再连接唯一 Presenter。
     *
     * 打开页面本身不启用在线服务、不申请绑定码、也不确认或解绑。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        val binding = ActivityVehicleAccountBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val renderer = VehicleQrRenderer()
        val port =
            VehicleAccountPresenter.AutoClientPort(
                this,
                onReady = { presenter.onReady() },
                onState = { presenter.onConnectionState(it) },
            )
        presenter =
            VehicleAccountPresenter(
                binding = binding,
                port = port,
                qr = renderer,
                onBack = { finish() },
            )
        presenter.bindActions()
        presenter.connect()
    }

    /** 开始唯一的两秒轮询。重复进入由 Presenter 忽略。 */
    override fun onStart() {
        super.onStart()
        presenter.onStart()
    }

    /** 离开页面时停止轮询，不暂停在线服务，也不解除绑定。 */
    override fun onStop() {
        presenter.onStop()
        super.onStop()
    }

    /** 销毁前关闭弹窗、二维码和同一份连接。 */
    override fun onDestroy() {
        if (::presenter.isInitialized) {
            presenter.close()
        }
        super.onDestroy()
    }

    private companion object {
        const val DENSITY_DPI = 280
    }
}
