package com.ecarx.autoservice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.ecarx.autoservice.databinding.ActivityDiagnosticBinding
import com.ecarx.autoservice.ui.AutoClientPageSession
import com.ecarx.autoservice.ui.ServicePageSession
import com.ecarx.autoservice.ui.diagnostic.DiagnosticPageController
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 偶尔使用的诊断入口：只 inflate 布局，把生命周期与文件选择结果转交控制器。
 *
 * 所有连接、Shell、扫描与车辆分页仍由服务执行，页面不拥有第二个连接器。
 * 线程：控制器在 `onStart` 建立观察者、`onStop` 关闭，`onDestroy` 释放导入线程。
 */
class DiagnosticActivity : Activity() {
    /** 观察者工厂；测试在 `create()` 前替换，页面与控制器仍走生产代码。 */
    internal var sessionFactory: (Context, (Bundle) -> Unit, (String) -> Unit) -> ServicePageSession =
        { context, onSnapshot, onState -> AutoClientPageSession(context, onSnapshot, onState) }

    /** 有界单线程：日志读取与认证导入；默认 daemon，destroy 时关闭。 */
    internal var ioExecutor: Executor =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "AutoService-import").apply { isDaemon = true }
        }

    private lateinit var controller: DiagnosticPageController

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val binding = ActivityDiagnosticBinding.inflate(layoutInflater)
        setContentView(binding.root)
        controller = DiagnosticPageController(this, binding, sessionFactory, ioExecutor)
    }

    override fun onStart() {
        super.onStart()
        controller.start()
    }

    override fun onStop() {
        controller.stop()
        super.onStop()
    }

    override fun onDestroy() {
        controller.destroy()
        super.onDestroy()
    }

    /** 平台文件选择器结果；请求码与 MIME 由 [com.ecarx.autoservice.settings.ServicePageCommands] 归属。 */
    @Deprecated("平台文件选择器")
    override fun onActivityResult(request: Int, code: Int, data: Intent?) {
        super.onActivityResult(request, code, data)
        deliverDocumentResult(request, code, data)
    }

    /** 把文件选择结果转交控制器；测试据此验证真实导入路径，不反射改私有状态。 */
    internal fun deliverDocumentResult(requestCode: Int, resultCode: Int, data: Intent?) =
        controller.onDocumentPicked(requestCode, resultCode, data)
}
