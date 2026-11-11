package com.ecarx.autoservice

import android.app.Activity
import android.content.Context
import android.os.Bundle
import com.ecarx.autoservice.databinding.ActivityServiceBinding
import com.ecarx.autoservice.ui.AutoClientPageSession
import com.ecarx.autoservice.ui.ServicePageSession
import com.ecarx.autoservice.ui.home.ServiceHomePresenter

/**
 * AutoService 首页：只做布局装配与生命周期转交。
 *
 * 页面不读文件、不发网络、不运行 shell，也不持有第二个后台连接协调器；
 * 状态转换、用户操作与观察者生命周期分别由
 * [com.ecarx.autoservice.ui.home.ServiceHomeState]、
 * [com.ecarx.autoservice.ui.home.ServiceHomeActions]、
 * [ServiceHomePresenter] 负责。离开页面不改变连接或输出所有权。
 */
class ServiceActivity : Activity() {
    /**
     * 观察者工厂；默认创建真实 [AutoClientPageSession]。测试在 `create()` 前替换为记录型 fixture，
     * 页面与 Presenter 仍走生产代码，这不是用户可配置项。
     */
    internal var sessionFactory: (Context, (Bundle) -> Unit, (String) -> Unit) -> ServicePageSession =
        { context, onSnapshot, onState -> AutoClientPageSession(context, onSnapshot, onState) }

    /** 时间来源；远程验证码有效期与授权窗口按它判定。 */
    internal var nowProvider: () -> Long = { System.currentTimeMillis() }

    private lateinit var presenter: ServiceHomePresenter

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val binding = ActivityServiceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        presenter = ServiceHomePresenter(this, binding, sessionFactory, nowProvider)
    }

    /** 前台可见才开始观察；重复 onStart 不会创建第二个 observer。 */
    override fun onStart() {
        super.onStart()
        presenter.start()
    }

    /** 从车辆账号页等返回后重读设置与就绪状态。 */
    override fun onResume() {
        super.onResume()
        presenter.refresh()
    }

    /** 离开页面只停止观察与 ticker，不停止后台工作。 */
    override fun onStop() {
        presenter.stop()
        super.onStop()
    }

    override fun onDestroy() {
        presenter.destroy()
        super.onDestroy()
    }
}
