package com.ecarx.autoservice.ui.diagnostic

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.ecarx.autoservice.AutoService
import com.ecarx.autoservice.R
import com.ecarx.autoservice.databinding.ActivityDiagnosticBinding
import com.ecarx.autoservice.platform.QnxCredentials
import com.ecarx.autoservice.settings.ServiceFeature
import com.ecarx.autoservice.settings.ServiceFeatures
import com.ecarx.autoservice.settings.ServicePageCommands
import com.ecarx.autoservice.startup.AutoStartup
import com.ecarx.autoservice.ui.ServicePageSession
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService

/**
 * 诊断页控制器：按钮动作、观察者、扫描状态、分页偏移与认证覆盖导入都在这里。
 *
 * 页面只保留 inflate 与生命周期转交，控制器不创建第二个后台连接协调器：
 * Shell、扫描与车辆分页都经同一 [ServicePageSession]。
 *
 * 线程/所有者：UI 动作与回调在主线程；日志读取与认证导入在构造注入的有界单线程上执行，
 * 结果回主线程且页面销毁后不再触碰 View。`destroy` 关闭会话并释放自有 executor。
 */
internal class DiagnosticPageController(
    private val activity: Activity,
    private val binding: ActivityDiagnosticBinding,
    private val sessionFactory: (Context, (Bundle) -> Unit, (String) -> Unit) -> ServicePageSession,
    private val io: Executor,
) {
    private var session: ServicePageSession? = null
    private var scanning = false
    private var offset = 0
    private var destroyed = false

    init {
        bindActions()
    }

    /** 建立观察者；重复调用或销毁后不再创建。 */
    fun start() {
        if (destroyed || session != null) return
        val next = sessionFactory(activity, ::onSnapshot, ::onState)
        session = next
        next.connect()
    }

    /** 页面不可见时关闭观察者；按既有客户端语义退出，不停止服务。 */
    fun stop() {
        session?.close()
        session = null
    }

    /** 页面销毁：失效一切迟到回调并释放自有线程。 */
    fun destroy() {
        destroyed = true
        stop()
        (io as? ExecutorService)?.shutdown()
    }

    /** 文件选择结果：只在请求码匹配时处理，导入流由 `QnxCredentials` 关闭。 */
    fun onDocumentPicked(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != ServicePageCommands.AUTH_DOCUMENT_REQUEST || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        io.execute {
            val stored =
                runCatching {
                    val stream = activity.contentResolver.openInputStream(uri)
                    checkNotNull(stream) { "open_stream_failed" }
                    QnxCredentials.store(activity, stream)
                }.isSuccess
            activity.runOnUiThread {
                if (destroyed) return@runOnUiThread
                binding.diagnosticResult.setText(
                    if (stored) R.string.diagnostic_auth_saved else R.string.diagnostic_auth_failed,
                )
                if (stored) {
                    activity.startService(
                        Intent(
                            activity,
                            AutoService::class.java,
                        ).setAction(ServicePageCommands.PREPARE),
                    )
                }
            }
        }
    }

    /** 绑定全部按钮；每一项只做一件事，副作用留给既有设置/服务入口。 */
    private fun bindActions() {
        binding.diagnosticBack.setOnClickListener { activity.finish() }
        binding.diagnosticPrepare.setOnClickListener { prepare() }
        binding.diagnosticLogs.setOnClickListener { readLogs() }
        binding.diagnosticRunRoot.setOnClickListener {
            execute(TRANSPORT_ROOT, ServiceFeature.ROOT, R.string.service_feature_root)
        }
        binding.diagnosticRunAdb.setOnClickListener {
            execute(TRANSPORT_ADB, ServiceFeature.ADB, R.string.service_feature_adb)
        }
        binding.diagnosticScan.setOnClickListener { toggleScan() }
        binding.diagnosticVehiclePage.setOnClickListener { pageVehicle() }
        binding.diagnosticAuthOverride.setOnClickListener { pickDocument() }
        binding.diagnosticModelL6.setOnClickListener { configureModel(MODEL_L6) }
        binding.diagnosticModelL7.setOnClickListener { configureModel(MODEL_L7) }
    }

    /** 重新初始化已开启的功能；后台暂停时不发命令。 */
    private fun prepare() {
        if (!AutoStartup.enabled(activity)) {
            binding.diagnosticResult.setText(R.string.diagnostic_paused)
            return
        }
        activity.startService(
            Intent(activity, AutoService::class.java).setAction(
                ServicePageCommands.PREPARE,
            ),
        )
    }

    /** 读取有界日志尾部；工作线程读文件，主线程更新结果。 */
    private fun readLogs() {
        io.execute {
            val text =
                LOG_FILES.joinToString("\n\n") { name ->
                    val file = File(activity.filesDir, "diagnostics/$name")
                    val body =
                        if (file.exists()) {
                            file.readText().takeLast(LOG_TAIL_CHARS)
                        } else {
                            activity.getString(R.string.diagnostic_log_empty)
                        }
                    name + "\n" + body
                }
            activity.runOnUiThread { if (!destroyed) binding.diagnosticResult.text = text }
        }
    }

    /** 执行一次 Shell；功能关闭或正文为空时不发送。 */
    private fun execute(transport: String, feature: ServiceFeature, title: Int) {
        if (!AutoStartup.enabled(activity) || !ServiceFeatures.read(activity).enabled(feature)) {
            binding.diagnosticResult.text =
                activity.getString(
                    R.string.diagnostic_feature_off,
                    activity.getString(title),
                )
            return
        }
        val command = binding.diagnosticShellInput.text.toString()
        if (command.isBlank()) return
        session?.executeShell(transport, command) { value ->
            if (destroyed) return@executeShell
            binding.diagnosticResult.text =
                activity.getString(
                    R.string.diagnostic_shell_result,
                    value.getInt("exitCode", -1),
                    value.getBoolean("timedOut"),
                    value.getString("error").orEmpty(),
                    value.getString("output").orEmpty(),
                )
        }
    }

    /** 切换全目录扫描：点击只请求一次，快照回填不重复请求。 */
    private fun toggleScan() {
        scanning = !scanning
        session?.setVehicleScan(scanning)
        renderScan()
        binding.diagnosticResult.setText(
            if (scanning) R.string.diagnostic_scan_started else R.string.diagnostic_scan_stopped,
        )
    }

    /** 读取下一页车辆属性；到达末尾后回到第一页。 */
    private fun pageVehicle() {
        session?.vehiclePage(offset) { value ->
            if (destroyed) return@vehiclePage
            val items = value.getParcelableArrayList<Bundle>("items").orEmpty()
            val header =
                activity.getString(
                    R.string.diagnostic_vehicle_header,
                    offset,
                    value.getInt("total"),
                )
            binding.diagnosticResult.text = header + "\n" +
                items.joinToString("\n") { item ->
                    "${item.getString("name")} [${item.getInt("propertyId")}/${item.getInt("areaId")}] " +
                        "${item.getString("status")}\n${item.getString("value")}"
                }
            offset = if (offset + items.size >= value.getInt("total")) 0 else offset + items.size
        }
    }

    /** 打开系统文件选择器导入诊断认证覆盖。 */
    private fun pickDocument() {
        activity.startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType(ServicePageCommands.AUTH_DOCUMENT_MIME)
                .addCategory(Intent.CATEGORY_OPENABLE),
            ServicePageCommands.AUTH_DOCUMENT_REQUEST,
        )
    }

    /** 写入车型偏好并通知既有 `model` 分支重新应用。 */
    private fun configureModel(model: String) {
        activity
            .getSharedPreferences("platform", 0)
            .edit()
            .putString(ServicePageCommands.EXTRA_MODEL, model)
            .apply()
        activity.startService(
            Intent(activity, AutoService::class.java).setAction(
                ServicePageCommands.MODEL,
            ),
        )
    }

    /** 服务快照回填：扫描开关以服务端状态为准，不在本地假设。 */
    private fun onSnapshot(snapshot: Bundle) {
        if (destroyed) return
        val actual = snapshot.getBoolean("vehicleScanning")
        if (actual != scanning) {
            scanning = actual
            renderScan()
        }
    }

    /** 观察者状态文本。 */
    private fun onState(state: String) {
        if (destroyed) return
        binding.diagnosticResult.text = state
    }

    /** 扫描按钮文案只反映当前状态。 */
    private fun renderScan() {
        binding.diagnosticScan.setText(if (scanning) R.string.diagnostic_scan_on else R.string.diagnostic_scan_off)
    }

    private companion object {
        const val TRANSPORT_ROOT = "ROOT"
        const val TRANSPORT_ADB = "ADB"
        const val MODEL_L6 = "L6"
        const val MODEL_L7 = "L7"

        /** 只迁既有日志文件名与读取上限。 */
        val LOG_FILES = listOf("app.log", "root.log")
        const val LOG_TAIL_CHARS = 16000
    }
}
