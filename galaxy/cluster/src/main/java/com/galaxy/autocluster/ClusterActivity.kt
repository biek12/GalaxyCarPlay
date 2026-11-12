package com.galaxy.autocluster

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import com.galaxy.autocluster.databinding.ActivityClusterBinding
import com.galaxy.autocluster.runtime.ClusterAppearance
import com.galaxy.autocluster.runtime.ClusterDisplayStore
import com.galaxy.autocluster.ui.AutoClientClusterPageSession
import com.galaxy.autocluster.ui.ClusterAppPresenter
import com.galaxy.autocluster.ui.ClusterDisplayActions
import com.galaxy.autocluster.ui.ClusterPageSession
import java.util.concurrent.Executor

/**
 * 独立 App 主页面：只做 Context 包装、布局装配与生命周期转交。
 *
 * 页面状态、路由与用户动作分别由 [ClusterAppPresenter]、[ClusterDisplayActions]
 * 承担；观察连接经 `sessionFactory` 创建（测试可注入记录型替身，页面与 Presenter
 * 仍走生产代码）。离开 App 本身不停止后台输出；关闭显示必须来自原显式操作。
 */
class ClusterActivity : Activity() {
    /** 外观包装沿用既有 `ClusterAppearance`；普通 App 外观与仪表输出分开。 */
    override fun attachBaseContext(base: Context) = super.attachBaseContext(ClusterAppearance.wrap(base))

    /**
     * 观察者工厂；默认创建真实 [AutoClientClusterPageSession]。测试在 `create()` 前替换为
     * 记录型 fixture，这不是用户可配置项。
     */
    internal var sessionFactory: (Context, (Bundle) -> Unit, (String) -> Unit) -> ClusterPageSession =
        { context, onSnapshot, onState -> AutoClientClusterPageSession(context, onSnapshot, onState) }

    /** 图片导入调度；默认生产单线程，测试可注入可控执行器（只替换调度边界）。 */
    internal var importExecutor: Executor? = null

    private lateinit var store: ClusterDisplayStore
    private lateinit var actions: ClusterDisplayActions
    private lateinit var binding: ActivityClusterBinding
    private lateinit var presenter: ClusterAppPresenter

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = ClusterDisplayStore(this)
        actions = ClusterDisplayActions(this, store)
        binding = ActivityClusterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        presenter = ClusterAppPresenter(this, binding, store, actions, sessionFactory, importExecutor)
        presenter.start(state?.getString(SAVED_PAGE), state?.getBoolean(SAVED_READING_IMAGE) ?: false)
    }

    /** 保存旧 `page`、`readingImage` 键；外观变化重建后仍在原页。 */
    override fun onSaveInstanceState(state: Bundle) {
        state.putString(SAVED_PAGE, presenter.savedPage)
        state.putBoolean(SAVED_READING_IMAGE, presenter.isReadingImage)
        super.onSaveInstanceState(state)
    }

    /** 配置变化：按新 Context 重建壳与当前页；保留路由与选择，不隐式提交。 */
    override fun onConfigurationChanged(config: Configuration) {
        super.onConfigurationChanged(config)
        binding = ActivityClusterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        presenter.rebind(binding)
    }

    /** 车机系统仍走旧回调；合同判定与导入交给 Presenter/导入器。 */
    @Deprecated("兼容Android 10/11")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        presenter.onPickResult(requestCode, resultCode, data)
    }

    /** 测试经此驱动选图回执（系统回调为 protected）；生产路径不受影响。 */
    internal fun deliverActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        onActivityResult(requestCode, resultCode, data)
    }

    /** 硬件返回关系由页面枚举决定；home 退出，music/background 回原厂增强，其余回首页。 */
    @Deprecated("兼容Android 10/11")
    override fun onBackPressed() {
        if (!presenter.onHardwareBack()) super.onBackPressed()
    }

    /** 销毁只关闭观察会话与导入 IO；不停止后台显示输出。 */
    override fun onDestroy() {
        presenter.destroy()
        super.onDestroy()
    }

    private companion object {
        /** 与旧版一致的保存状态键：当前页面存储值。 */
        const val SAVED_PAGE = "page"

        /** 与旧版一致的保存状态键：选图进行中。 */
        const val SAVED_READING_IMAGE = "readingImage"
    }
}
