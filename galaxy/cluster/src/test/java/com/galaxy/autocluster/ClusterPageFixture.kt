package com.galaxy.autocluster

import android.content.ComponentName
import android.os.Bundle
import android.widget.Button
import java.util.concurrent.Executor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.galaxy.auto.contract.AutoContract
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import com.galaxy.auto.ui.GalaxySettingRow
import com.galaxy.autocluster.ui.ClusterPageSession
import java.io.File

/**
 * 记录型观察者替身：只隔离 AutoClient 连接边界；快照与断连由测试按生产同型
 * 合同主动投递，connect/close 计数用于生命周期断言。不复制任何业务模型。
 */
internal class RecordingClusterPageSession : ClusterPageSession {
    var connectCount = 0
        private set
    var closeCount = 0
        private set
    private var snapshotListener: ((Bundle) -> Unit)? = null
    private var stateListener: ((String) -> Unit)? = null

    override fun connect() {
        connectCount++
    }

    override fun close() {
        closeCount++
    }

    /** 由 ClusterActivity.sessionFactory 装配路径调用；不对外暴露业务回调签名以外的内容。 */
    fun attach(
        onSnapshot: (Bundle) -> Unit,
        onState: (String) -> Unit,
    ) {
        snapshotListener = onSnapshot
        stateListener = onState
    }

    /** 按生产合同投递一条观察快照（主线程同步回调）。 */
    fun push(value: Bundle) {
        requireNotNull(snapshotListener).invoke(value)
    }

    /** 按生产合同投递一条连接状态回调（含断连取值）。 */
    fun pushState(value: String) {
        requireNotNull(stateListener).invoke(value)
    }
}

/**
 * 可控执行器：只替换导入调度的边界，提交的命令保持排队，由测试显式驱动；
 * 用于验证容量 1 队列替换与关闭后失效，不复制任何 IO 逻辑。
 */
internal class QueuedImportExecutor : Executor {
    private val commands = mutableListOf<Runnable>()

    override fun execute(command: Runnable) {
        commands.add(command)
    }

    /** 依次执行已排队命令；执行期间新提交的命令留待下一次驱动。 */
    fun runAll() {
        val snapshot = commands.toList()
        commands.clear()
        snapshot.forEach { it.run() }
    }
}

/** C1 用例共享 fixture：替身装配、视图遍历、设置行查找与宿主离线截图。 */
internal object ClusterPageFixture {
    /** 声明 AutoService 组件不可绑定，防止任何真实连接尝试越出替身边界。 */
    fun declareServiceUnbindable() {
        Shadows
            .shadowOf(RuntimeEnvironment.getApplication())
            .declareComponentUnbindable(
                ComponentName(AutoContract.SERVICE_PACKAGE, AutoContract.SERVICE_CLASS),
            )
    }

    /** 在 `setup()` 前为真实 ClusterActivity 注入记录型会话与直执行导入调度（结果仍经主线程交付）。 */
    fun install(activity: ClusterActivity): RecordingClusterPageSession {
        val session = RecordingClusterPageSession()
        activity.sessionFactory = { _, onSnapshot, onState ->
            session.attach(onSnapshot, onState)
            session
        }
        activity.importExecutor = Executor { it.run() }
        return session
    }

    /** 注入记录型会话与可控排队导入调度（只替换调度边界）。 */
    fun installWithQueuedImport(activity: ClusterActivity): Pair<RecordingClusterPageSession, QueuedImportExecutor> {
        val session = RecordingClusterPageSession()
        val executor = QueuedImportExecutor()
        activity.sessionFactory = { _, onSnapshot, onState ->
            session.attach(onSnapshot, onState)
            session
        }
        activity.importExecutor = executor
        return session to executor
    }

    /** 深度优先遍历视图树。 */
    fun walk(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup) {
                (0 until view.childCount).flatMap { walk(view.getChildAt(it)) }
            } else {
                emptyList()
            }

    /** 按标题查找唯一设置行。 */
    fun row(
        root: View,
        name: String,
    ): GalaxySettingRow =
        walk(root).filterIsInstance<GalaxySettingRow>().single { it.titleView.text.toString() == name }

    /** 按可见文字查找唯一按钮。 */
    fun button(
        root: View,
        label: String,
    ): Button =
        walk(root).filterIsInstance<Button>().single { it.text.toString() == label }

    /** 是否存在指定可见文案。 */
    fun hasText(
        root: View,
        value: String,
    ): Boolean = walk(root).filterIsInstance<TextView>().any { it.text.toString() == value }

    /** 宿主离线渲染截图；不是模拟器、车机截图或显示验收。 */
    fun capture(
        root: View,
        name: String,
    ) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1000, 1800)
        val image = Bitmap.createBitmap(1000, 1800, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(image))
            val directory = File("build/outputs/refactor-ui").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            image.recycle()
        }
    }
}
