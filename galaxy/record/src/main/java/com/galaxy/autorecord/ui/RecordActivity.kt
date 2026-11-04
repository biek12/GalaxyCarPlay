package com.galaxy.autorecord.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.Surface
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.galaxy.autorecord.BuildConfig
import com.galaxy.autorecord.core.RecordAccessGate
import com.galaxy.autorecord.core.AutoServiceRecordFeed
import com.galaxy.autorecord.core.L6RecordCameraAdapter
import com.galaxy.autorecord.core.EmulatorRecordCameraAdapter
import com.galaxy.autorecord.core.AndroidStorageVolumes
import com.galaxy.autorecord.core.CameraChannelSource
import com.galaxy.autorecord.core.CameraLayout
import com.galaxy.autorecord.core.CameraLayoutStore
import com.galaxy.autorecord.core.DirectCameraSource
import com.galaxy.autorecord.core.SurfaceCameraSource
import com.galaxy.autorecord.core.DeviceRecordingEnvironment
import com.galaxy.autorecord.core.LibraryState
import com.galaxy.autorecord.core.LocalLibrary
import com.galaxy.autorecord.core.PrefsSettingsStore
import com.galaxy.autorecord.core.RecordSettings
import com.galaxy.autorecord.core.RecordingController
import com.galaxy.autorecord.core.RecordingSnapshot
import com.galaxy.autorecord.core.RecordingState
import com.galaxy.autorecord.core.SegmentEntry
import com.galaxy.autorecord.core.SegmentLayout
import com.galaxy.autorecord.core.SegmentMarks
import com.galaxy.autorecord.core.SettingsStore
import com.galaxy.autorecord.core.StorageState
import com.galaxy.autorecord.core.StorageVolumes
import com.galaxy.autorecord.core.UnavailableCameraSource
import com.galaxy.autorecord.core.VehicleFeed
import com.galaxy.autorecord.core.VehicleKey
import com.galaxy.autorecord.core.VehicleSnapshot
import com.galaxy.autorecord.core.VolumeInfo
import java.io.File
import java.util.concurrent.Executors

/**
 * AutoRecord 应用壳。
 *
 * 结构（2026-11-04 用户修订）：首页、录像库与设置统一为左侧 164dp 导航；
 * 应用资源密度沿 CarPlay 默认 280dpi，保留系统字体缩放，不改系统或相机分辨率；
 * - **P3 同步回放**：二级沉浸页，只有左上返回。
 *
 * 职责边界：
 * - 页面只读模型与快照，不持有任何采集/编码资源；
 * - 录制状态只来自 [RecordingController] 的真实状态机；引擎未接入就停在异常态，界面不伪造"录制中"；
 * - [RecordAccessGate] 完成服务授权与本地复核后，才开启摄像头及 [VehicleFeed] 车辆订阅。
 */
class RecordActivity : Activity() {

    internal lateinit var ui: RecordUi
        private set
    internal lateinit var io: RecordIo
        private set
    internal lateinit var storageVolumes: StorageVolumes
        private set

    private lateinit var store: SettingsStore

    /** 当前设置：改动立即落盘，影响编码/写盘的参数说明为"下一段生效"。 */
    internal var settings: RecordSettings = RecordSettings()
        private set

    /** 录制状态机快照（只读，来自 controller）。 */
    internal var snapshot: RecordingSnapshot = RecordingSnapshot()
        private set

    internal var library: LibraryState = LibraryState()
        private set

    internal lateinit var controller: RecordingController
        private set

    /**
     * 摄像头来源：CAMERA 已授予时用 Camera2 直连（并发四路 JPEG），否则退回"未接入"实现。
     *
     * 决策依据：CAMERA 若是预授予/已授权，就没有必要让 AutoService 逐帧转发 4 路 6 MB 缓冲；
     * AutoService 只需在"代为授权"和"能力扫描"上帮忙（用户 2026-11-03 明确要求）。
     */
    internal var cameraSource: CameraChannelSource = UnavailableCameraSource("等待 AutoService 连接与权限准备")
        private set

    private var directCamera: DirectCameraSource? = null
    private var surfaceCamera: SurfaceCameraSource? = null
    private var recordingEngine: com.galaxy.autorecord.recording.RecordingEngine? = null
    private val previewSurfaces = mutableMapOf<String, Surface>()

    /** 最近一帧（按方向缓存）；页面只读，不持有生命周期。 */
    private val previewFrames = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

    /** CAMERA 权限状态的可读描述，诊断页直接用。 */
    internal var cameraPermissionState: String = "未检查"
        private set

    /** 是否已经自动弹过一次授权框：避免"被拒→再请求→再被拒"的循环。 */
    private var cameraPermissionAsked = false

    /**
     * 方位 ↔ cameraId 标定（实测默认假设 A）。
     *
     * 为什么独立于设置：这是设备标定数据，不是用户偏好；"恢复默认设置"不应把它冲掉，
     * 而标定结果必须能只改数据不改代码（实测建议 §7.1）。
     */
    private lateinit var cameraLayoutStore: CameraLayoutStore
    internal var cameraLayout: CameraLayout = CameraLayout.DEFAULT
        private set

    /** 正常构建消费 AutoService 订阅；模拟器构建不连接车辆，未知值不补零。 */
    internal lateinit var vehicleFeed: VehicleFeed
        private set
    private var serviceFeed: AutoServiceRecordFeed? = null
    private lateinit var accessGate: RecordAccessGate
    private var foreground = false
    private var permissionCompletion: ((Boolean, String) -> Unit)? = null
    private val permissionTimeout = Runnable { accessGate.block("授权等待超时，请检查 AutoService 的自动运行与 Root 开关") }

    internal var vehicle: VehicleSnapshot = VehicleSnapshot()
        private set

    /** 当前正在回放的片段 id（非空即处于 P3 沉浸页）。 */
    internal var playingSegmentId: String? = null
        private set

    private lateinit var navRail: RecordNavRail
    private lateinit var titleBar: RecordTitleBar
    private lateinit var contentHost: FrameLayout
    private lateinit var column: LinearLayout

    private var mainPage = MainPage.LIVE
    private val mainHistory = mutableListOf(MainPage.LIVE)
    private var subPage: SubPage? = null
    private val pageCache = mutableMapOf<String, RecordPage>()
    private var currentPage: RecordPage? = null
    private var systemNightAtBuild = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val preflightWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "record-preflight").apply { isDaemon = true }
    }
    private var badgeView: android.widget.TextView? = null
    private val ticker = object : Runnable {
        override fun run() {
            controller.tick(System.currentTimeMillis())
            snapshot = controller.snapshot
            renderBadge()
            val nextVehicle = vehicleFeed.snapshot()
            if (vehicle != nextVehicle) {
                vehicle = nextVehicle
                currentPage?.onDataChanged()
            }
            currentPage?.onTick()
            uiHandler.postDelayed(this, TICK_MS)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        // 与 CarPlay L7UiDensity.DEFAULT=280 一致；只改应用资源密度，不改系统/相机像素。
        val base = Configuration(newBase.resources.configuration)
        val ratio = base.densityDpi.toFloat() / 280f
        val density = Configuration().apply {
            setLocales(base.locales)
            densityDpi = 280
            screenWidthDp = (base.screenWidthDp * ratio).toInt()
            screenHeightDp = (base.screenHeightDp * ratio).toInt()
            smallestScreenWidthDp = (base.smallestScreenWidthDp * ratio).toInt()
        }
        val mode = runCatching { PrefsSettingsStore(newBase).load().themeMode }
            .getOrDefault(RecordSettings.THEME_SYSTEM)
        if (mode != RecordSettings.THEME_SYSTEM) {
            val override = Configuration(newBase.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (mode == RecordSettings.THEME_DARK) Configuration.UI_MODE_NIGHT_YES
                    else Configuration.UI_MODE_NIGHT_NO
            }
            density.uiMode = override.uiMode
        }
        runCatching { applyOverrideConfiguration(density) }
                .onFailure { Log.w(TAG, "uiMode 覆盖失败，本次按系统日夜显示：$mode", it) }
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = RecordUi(this)
        io = RecordIo()
        store = PrefsSettingsStore(this)
        settings = store.load()
        storageVolumes = AndroidStorageVolumes(this)
        cameraLayoutStore = CameraLayoutStore(this)
        cameraLayout = cameraLayoutStore.load()
        configureAccess()
        vehicle = vehicleFeed.snapshot()
        systemNightAtBuild = isNight(resources.configuration)

        controller = RecordingController(
            env = DeviceRecordingEnvironment(
                volumes = storageVolumes,
                cameraSourceProvider = { cameraSource },
                layoutProvider = { cameraLayout },
                engineAvailable = !BuildConfig.EMULATOR_CAMERA,
            ),
            settingsProvider = { settings },
            worker = preflightWorker,
            callbackExecutor = java.util.concurrent.Executor { task -> uiHandler.post(task) },
        ).also { c ->
            c.listener = { next -> snapshot = next; dispatchSnapshot() }
            c.preflightListener = { outcome -> (currentPage as? LivePage)?.onPreflight(outcome) }
            // B3 录制引擎：模拟器构建不挂（JPEG 通路无法直喂编码 Surface，如实保持"引擎不可用"）
            if (!BuildConfig.EMULATOR_CAMERA) {
                recordingEngine = com.galaxy.autorecord.recording.RecordingEngine(
                    volumes = storageVolumes,
                    feed = vehicleFeed,
                    surfaceBinder = object : com.galaxy.autorecord.core.RecordSurfaceBinder {
                        override fun bindRecordSurface(direction: String, surface: Surface?, previous: Surface?) {
                            surfaceCamera?.bindRecordSurface(direction, surface, previous)
                        }
                    },
                    settingsProvider = { settings },
                    appVersionName = BuildConfig.VERSION_NAME,
                )
                c.engine = recordingEngine
            }
        }
        vehicleFeed.setInterval(settings.vehicleIntervalMs)

        restoreState(savedInstanceState)
        buildShell()
        showCurrent()
        refreshLibrary()
        savedInstanceState?.let { state ->
            val scroll = state.getInt(KEY_SCROLL, 0)
            if (scroll > 0) currentPage?.view()?.post { currentPage?.restoreScroll(scroll) }
        }
        maybeShowOnboarding()
        if (BuildConfig.EMULATOR_CAMERA || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) accessGate.begin()
        serviceFeed?.connect()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (accessGate.state == RecordAccessGate.State.READY && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            accessGate.reset()
            serviceFeed?.authorize(false)
            stopPreview()
        }
        serviceFeed?.foreground(true)
        if (BuildConfig.EMULATOR_CAMERA || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED || serviceFeed?.connected == true) accessGate.begin()
        uiHandler.removeCallbacks(ticker)
        uiHandler.postDelayed(ticker, TICK_MS)
        vehicle = vehicleFeed.snapshot()
        // 回到前台：让当前页按需重新开流（页面自己决定要几路）
        currentPage?.onShown()
    }

    override fun onPause() {
        foreground = false
        serviceFeed?.foreground(false)
        uiHandler.removeCallbacks(ticker)
        // 离开前台就释放相机：不占相机、不烧电，符合"最少资源"
        currentPage?.onHidden()
        stopPreview()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_MAIN, mainPage.name)
        subPage?.let { outState.putString(KEY_SUB, it.name) }
        playingSegmentId?.let { outState.putString(KEY_PLAYING, it) }
        outState.putStringArrayList(KEY_HISTORY, ArrayList(mainHistory.map { it.name }))
        outState.putInt(KEY_SCROLL, currentPage?.scrollY() ?: 0)
    }

    private fun restoreState(state: Bundle?) {
        if (state == null) return
        state.getString(KEY_MAIN)?.let { name ->
            MainPage.entries.firstOrNull { it.name == name }?.let { mainPage = it }
        }
        state.getString(KEY_SUB)?.let { name ->
            subPage = SubPage.entries.firstOrNull { it.name == name }
        }
        playingSegmentId = state.getString(KEY_PLAYING)
        state.getStringArrayList(KEY_HISTORY)?.let { names ->
            mainHistory.clear()
            names.forEach { name ->
                MainPage.entries.firstOrNull { it.name == name }?.let { mainHistory += it }
            }
            if (mainHistory.isEmpty()) mainHistory += mainPage
        }
    }

    private fun buildShell() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        navRail = RecordNavRail(this, ui) { page -> openMainPage(page) }
        root.addView(navRail, LinearLayout.LayoutParams(ui.dp(164), LinearLayout.LayoutParams.MATCH_PARENT))

        val content = FrameLayout(this).apply { background = ui.contentGradient() }
        root.addView(content, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(column, FrameLayout.LayoutParams(-1, -1))

        titleBar = RecordTitleBar(this, ui)
        column.addView(titleBar, LinearLayout.LayoutParams(-1, ui.dp(64)))

        contentHost = FrameLayout(this)
        column.addView(contentHost, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun applyLayoutMode() {
        navRail.visibility = if (playingSegmentId != null) View.GONE else View.VISIBLE
    }

    // ---- 导航 ----

    internal fun openMainPage(page: MainPage) {
        playingSegmentId = null
        subPage = null
        if (mainPage != page) {
            mainPage = page
            val existing = mainHistory.indexOf(page)
            if (existing >= 0) {
                while (mainHistory.size > existing + 1) mainHistory.removeAt(mainHistory.lastIndex)
            } else {
                if (mainHistory.size >= MAX_HISTORY) mainHistory.removeAt(0)
                mainHistory += page
            }
        }
        showCurrent()
    }

    internal fun openSubPage(page: SubPage) {
        playingSegmentId = null
        subPage = page
        showCurrent()
    }

    /** 进入 P3 沉浸回放。 */
    internal fun openPlayback(segmentId: String) {
        playingSegmentId = segmentId
        showCurrent()
    }

    internal fun backToSettings() {
        playingSegmentId = null
        subPage = null
        mainPage = MainPage.SETTINGS
        if (mainHistory.lastOrNull() != MainPage.SETTINGS) {
            if (mainHistory.size >= MAX_HISTORY) mainHistory.removeAt(0)
            mainHistory += MainPage.SETTINGS
        }
        showCurrent()
    }

    private fun showCurrent() {
        // 换页前先让上一页释放预览，避免两页同时抢相机
        currentPage?.onHidden()
        stopPreview()
        val page = pageFor()
        currentPage = page
        val view = page.view()
        contentHost.removeAllViews()
        contentHost.addView(view, FrameLayout.LayoutParams(-1, -1))
        applyLayoutMode()
        val immersive = playingSegmentId != null
        titleBar.bind(
            title = page.title,
            showBack = subPage != null || immersive,
            backLabel = "返回",
            onBack = { goBack() },
        )
        titleBar.bindRight { container ->
            page.rightSlot(container)
            // 录制徽标常驻：任何页面都能看到"到底在不在录"，并且点开就是操作条（v1.1 RV-07）。
            val badge = ui.badge("", ui.muted).apply {
                isClickable = true
                isFocusable = true
                contentDescription = "录制操作"
                setOnClickListener { showActionSheet() }
            }
            badgeView = badge
            container.addView(badge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(8) })
        }
        renderBadge()
        if (subPage == null && !immersive) {
            navRail.select(mainPage)
        } else {
            navRail.select(MainPage.SETTINGS.takeIf { subPage != null } ?: mainPage)
        }
        page.onShown()
    }

    /**
     * 录制徽标：只反映真实状态机；录制中附当前片段进度与**录像健康点**
     * （绿=四路齐全且写盘正常、黄=缺路/写入异常、红=异常状态），未录制显示灰态"未录制"。
     */
    private fun renderBadge() {
        val badge = badgeView ?: return
        val snap = controller.snapshot
        val state = snap.state
        val base = when (state) {
            RecordingState.IDLE -> "未录制"
            RecordingState.STARTING -> "预检中"
            RecordingState.RECORDING -> "录制中"
            RecordingState.STOPPING -> "收尾中"
            RecordingState.ERROR -> "录制异常"
        }
        val suffix = when (state) {
            RecordingState.RECORDING -> " " + RecordTime.clock(snap.segmentElapsedMillis)
            RecordingState.STOPPING -> " …"
            else -> ""
        }
        val text = base + suffix
        val healthDot: String? = when {
            state == RecordingState.RECORDING &&
                snap.missingDirections.isEmpty() &&
                snap.storage == StorageState.OK -> "● "
            state == RecordingState.RECORDING -> "● "
            state == RecordingState.ERROR -> "● "
            else -> null
        }
        if (healthDot == null) {
            badge.text = text
        } else {
            val healthColor = when {
                state == RecordingState.ERROR -> android.graphics.Color.parseColor("#E5484D")
                snap.missingDirections.isNotEmpty() ||
                    snap.storage != StorageState.OK -> android.graphics.Color.parseColor("#F5A524")
                else -> android.graphics.Color.parseColor("#2EC471")
            }
            val spanned = android.text.SpannableString(healthDot + text)
            spanned.setSpan(
                android.text.style.ForegroundColorSpan(healthColor),
                0, healthDot.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            badge.text = spanned
        }
        badge.setTextColor(
            when (state) {
                RecordingState.RECORDING, RecordingState.ERROR -> ui.danger
                RecordingState.STARTING, RecordingState.STOPPING -> ui.warning
                RecordingState.IDLE -> ui.muted
            },
        )
    }

    private fun pageFor(): RecordPage {
        playingSegmentId?.let { id ->
            return pageCache.getOrPut("play:$id") { PlaybackPage(this, ui, id) }
        }
        val key = subPage?.name ?: mainPage.name
        return pageCache.getOrPut(key) {
            val sub = subPage
            when {
                sub == SubPage.STORAGE -> StorageSettingsPage(this, ui)
                sub == SubPage.CODEC -> CodecSettingsPage(this, ui)
                sub == SubPage.RECORDING -> RecordingSettingsPage(this, ui)
                sub == SubPage.VEHICLE -> VehicleSettingsPage(this, ui)
                sub == SubPage.PLAYBACK -> PlaybackSettingsPage(this, ui)
                sub == SubPage.DIAGNOSE -> DiagnosePage(this, ui)
                sub == SubPage.ABOUT -> AboutPage(this, ui)
                sub != null -> PlaceholderPage(this, ui, sub)
                mainPage == MainPage.LIVE -> LivePage(this, ui)
                mainPage == MainPage.LIBRARY -> LibraryPage(this, ui)
                else -> SettingsIndexPage(this, ui)
            }
        }
    }

    private fun goBack() {
        if (currentPage?.onBack() == true) return
        if (playingSegmentId != null) {
            playingSegmentId = null
            showCurrent()
            return
        }
        if (subPage != null) {
            subPage = null
            showCurrent()
            return
        }
        if (mainPage != MainPage.LIVE) {
            mainPage = MainPage.LIVE
            mainHistory.clear()
            mainHistory += MainPage.LIVE
            showCurrent()
            return
        }
        finish()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        goBack()
    }

    // ---- 录制与取证 ----

    /** 开始录制：进入两阶段预检；结果由 [LivePage.onPreflight] 呈现，不在这里假装开始。 */
    internal fun requestStartRecording() {
        controller.requestStart()
        snapshot = controller.snapshot
        dispatchSnapshot()
    }

    internal fun requestStopRecording() {
        controller.requestStop()
        snapshot = controller.snapshot
        dispatchSnapshot()
        toastLine("录制已停止")
    }

    // ---- 导出（B5）：SAF 选位置 → 后台执行 → 结果回执 ----

    private class PendingExport(
        val segments: List<java.io.File>,
        val grid: Boolean,
        val direction: String,
        val overlay: Boolean,
        val suggestedName: String,
    )

    private var pendingExport: PendingExport? = null

    internal fun requestExport(
        segments: List<java.io.File>,
        grid: Boolean,
        direction: String,
        overlay: Boolean,
        suggestedName: String,
    ) {
        pendingExport = PendingExport(segments, grid, direction, overlay, suggestedName)
        val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "video/mp4"
            putExtra(android.content.Intent.EXTRA_TITLE, suggestedName)
        }
        runCatching { startActivityForResult(intent, REQUEST_EXPORT) }
            .onFailure {
                pendingExport = null
                toastLine("无法打开系统文件选择器：${it.javaClass.simpleName}")
            }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT) return
        val pending = pendingExport
        pendingExport = null
        if (resultCode != RESULT_OK || data?.data == null || pending == null) {
            toastLine("已取消导出：未生成文件，原始片段保持不动")
            return
        }
        val uri = data.data!!
        toastLine("导出中…（只读源段；完成后回执）")
        val task = com.galaxy.autorecord.export.ExportTask(
            context = this,
            request = com.galaxy.autorecord.export.ExportTask.ExportRequest(
                segmentDirs = pending.segments,
                grid = pending.grid,
                direction = pending.direction,
                overlay = pending.overlay,
                outputUri = uri,
            ),
            onProgress = { message -> uiHandler.post { toastLine(message) } },
        )
        Thread({
            val result = task.execute()
            uiHandler.post { toastLine(result.message) }
        }, "record-export").start()
    }

    internal fun clearRecordingError() {
        controller.clearError()
        snapshot = controller.snapshot
        dispatchSnapshot()
    }

    /** 一键锁定最近片段：当前段 + 最近一条已完成段（v1.1 §2.3-9、§14.1）。 */
    internal fun lockRecent(): String {
        val marks = marksForCurrentVolume() ?: return "存储卷不可用，无法锁定"
        val current = if (controller.snapshot.state == RecordingState.RECORDING) controller.snapshot.segmentIndex else null
        val sorted = library.segments.sortedByDescending { it.startedAtMillis }
        val targets = buildList {
            current?.let { add("$it") }
            sorted.firstOrNull { it.completeness != com.galaxy.autorecord.core.SegmentCompleteness.WRITING }?.let { add(it.id) }
        }.distinct()
        if (targets.isEmpty()) return "还没有片段可锁定：本次录制尚未产生可收尾的片段"
        val result = marks.setLocked(targets, true) ?: return "锁定失败：索引目录不可写"
        refreshLibrary()
        val lockedCount = result.size
        return if (targets.size == 1) "已锁定 1 段（当前片段较多时请再锁一次）" else "已锁定 ${targets.size} 段（共 $lockedCount 段受保护）"
    }

    internal fun toggleLock(entry: SegmentEntry, locked: Boolean): String? {
        val marks = marksForCurrentVolume() ?: return "存储卷不可用，无法修改锁定"
        marks.setLocked(listOf(entry.id), locked) ?: return "锁定状态写入失败（索引目录不可写）"
        refreshLibrary()
        return null
    }

    internal fun markPlayed(entry: SegmentEntry) {
        marksForCurrentVolume()?.markPlayed(entry.id)
        refreshLibrary()
    }

    /** 删除片段（只删本 App 段目录，永不格式化、不碰其他文件）。 */
    internal fun deleteSegment(entry: SegmentEntry): String? {
        if (entry.isProtected) return "该片段受保护（已锁定或含事件），请先解锁"
        if (entry.completeness == com.galaxy.autorecord.core.SegmentCompleteness.WRITING) return "该片段仍在写入中"
        val ok = runCatching { entry.dir.deleteRecursively() }.getOrDefault(false)
        if (!ok) return "删除失败：目录不可写或文件被占用"
        refreshLibrary()
        return null
    }

    internal fun refreshLibrary() {
        val baseDir = resolveAppDirSync()
        io.run({ LocalLibrary.load(baseDir, baseDir?.let { SegmentMarks(SegmentLayout.indexDir(it)) }, volumeLabel()) }) { result ->
            library = result.getOrElse { LibraryState(scanned = true, error = "读取片段失败：${it.message ?: "未知错误"}") }
            currentPage?.onDataChanged()
        }
    }

    private fun marksForCurrentVolume(): SegmentMarks? {
        val dir = resolveAppDirSync() ?: return null
        return SegmentMarks(SegmentLayout.indexDir(dir))
    }

    private fun resolveAppDirSync(): File? {
        val volumes = runCatching { storageVolumes.list() }.getOrNull() ?: return null
        val uuid = settings.storageVolumeUuid
        val volume = if (uuid == null) volumes.firstOrNull { !it.removable && it.id == null }
        else volumes.firstOrNull { it.id == uuid }
        return volume?.appDir
    }

    internal fun volumeLabel(): String {
        val volumes = runCatching { storageVolumes.list() }.getOrNull() ?: return "内置存储"
        val uuid = settings.storageVolumeUuid
        val volume = if (uuid == null) volumes.firstOrNull { !it.removable && it.id == null }
        else volumes.firstOrNull { it.id == uuid }
        return volume?.label ?: if (uuid == null) "内置存储不可用" else "所选介质已移除"
    }

    /** 当前配额的保留窗口（估算）。界面只显示这个函数的结果，不各自换算。 */
    internal fun backtraceLabel(quotaBytes: Long): String = com.galaxy.autorecord.core.RetentionPlan.backtraceLabel(
        quotaBytes, settings.requestedHeight, settings.fps, settings.bitrateMode,
    )

    /**
     * 行驶中只读判定（RV-07）：挡位可用且非 P 才认为在行驶。
     * **挡位不可用时不锁**——宁可少限制，也不因数据缺失把用户挡在设置外。
     */
    internal fun isDriving(): Boolean {
        val gear = vehicle.values[VehicleKey.GEAR] ?: return false
        if (gear.status != com.galaxy.autorecord.core.VehicleValueStatus.LIVE) return false
        val value = gear.value.trim().uppercase()
        return value.isNotEmpty() && value != "—" && value != "P"
    }

    /**
     * 把一个方位改到指定 cameraId。
     *
     * 校验口径：只接受实测可用 ID（0–3），且不允许两个方向指向同一个 ID。
     * 由诊断页明确展示两个方向并二次确认后交换；整套映射一次落盘，不产生重复 ID。
     */
    internal fun updateCameraDirection(direction: String, cameraId: Int): String? {
        if (cameraId !in com.galaxy.autorecord.core.CameraProfile.USABLE_IDS) {
            return "cameraId $cameraId 未通过实测（仅 0–3 可出帧）"
        }
        val next = cameraLayout.withCameraId(direction, cameraId) ?: return "标定映射不完整或方向无效"
        if (!cameraLayoutStore.save(next, calibrated = false)) return "标定写入失败（映射不完整）"
        cameraLayout = cameraLayoutStore.load()
        currentPage?.onDataChanged()
        return null
    }

    /** 四路均经现场核对后由用户明确确认，单次调整不代表整车标定完成。 */
    internal fun confirmCameraLayout(): Boolean {
        if (!cameraLayoutStore.save(cameraLayout, calibrated = true)) return false
        cameraLayout = cameraLayoutStore.load()
        currentPage?.onDataChanged()
        return true
    }

    /** 回到实测假设 A（未标定状态）。 */
    internal fun resetCameraLayout() {
        cameraLayoutStore.reset()
        cameraLayout = cameraLayoutStore.load()
        currentPage?.onDataChanged()
    }

    // ---- 摄像头 ----

    /** 权限已授予就走直连；未授予就申请一次，拒绝则保持"未接入"并如实说明。 */
    private fun setupCameraSource() {
        val granted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            cameraPermissionState = "未授予"
            cameraSource = UnavailableCameraSource("CAMERA 权限未授予：授权后可直接采集，无需服务转发")
            return
        }
        cameraPermissionState = "已授予"
        if (!BuildConfig.EMULATOR_CAMERA) {
            val source = SurfaceCameraSource(this, L6RecordCameraAdapter { cameraLayout }) {
                uiHandler.post { if (!isFinishing && !isDestroyed) currentPage?.onDataChanged() }
            }
            surfaceCamera = source
            cameraSource = source
            previewSurfaces.forEach { (direction, surface) -> source.bind(direction, surface) }
            return
        }
        val direct = DirectCameraSource(
            context = this,
            adapter = if (BuildConfig.EMULATOR_CAMERA) EmulatorRecordCameraAdapter(this)
                else L6RecordCameraAdapter { cameraLayout },
            onFrame = { direction, bitmap ->
                previewFrames[direction] = bitmap
                uiHandler.post { if (!isFinishing && !isDestroyed) currentPage?.onCameraFrame(direction) }
            },
            onStatusChanged = {
                uiHandler.post { if (!isFinishing && !isDestroyed) currentPage?.onDataChanged() }
            },
        )
        directCamera = direct
        cameraSource = direct
    }

    /**
     * 申请 CAMERA。
     *
     * 只自动申请一次：被拒后再由用户点"授权"才重试（[force]），
     * 否则会形成"申请→回调→再申请"的循环（页面冒烟测试就是被这个打爆的）。
     */
    internal fun requestCameraPermission(force: Boolean = false) {
        // 即使反复点击“授权”，同一代准备期间也只能有一个服务请求。
        if (accessGate.state == RecordAccessGate.State.REQUESTING) return
        if (force) accessGate.reset()
        if (BuildConfig.EMULATOR_CAMERA || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED || serviceFeed?.connected == true) accessGate.begin()
        else {
            cameraPermissionState = "等待 AutoService 连接"
            serviceFeed?.connect()
            currentPage?.onDataChanged()
        }
    }

    private fun configureAccess() {
        accessGate = RecordAccessGate(
            hasPermission = { checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED },
            request = { completion ->
                cameraPermissionState = if (BuildConfig.EMULATOR_CAMERA) "等待测试摄像头授权" else "等待 AutoService 授权"
                uiHandler.removeCallbacks(permissionTimeout)
                uiHandler.postDelayed(permissionTimeout, 45_000)
                if (BuildConfig.EMULATOR_CAMERA) {
                    permissionCompletion = completion
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
                } else serviceFeed?.requestCamera { granted, reason ->
                    uiHandler.removeCallbacks(permissionTimeout)
                    completion(granted, reason)
                }
            },
            onReady = {
                uiHandler.removeCallbacks(permissionTimeout)
                cameraPermissionState = "已授予并复核"
                if (directCamera == null && surfaceCamera == null) setupCameraSource()
                serviceFeed?.authorize(true)
                vehicle = vehicleFeed.snapshot()
                currentPage?.onDataChanged()
                if (foreground) currentPage?.onShown()
            },
            onBlocked = { reason ->
                uiHandler.removeCallbacks(permissionTimeout)
                cameraPermissionState = reason
                serviceFeed?.authorize(false)
                stopPreview()
                currentPage?.onDataChanged()
            },
        )
        if (BuildConfig.EMULATOR_CAMERA) {
            vehicleFeed = com.galaxy.autorecord.core.OfflineVehicleFeed("模拟器测试：未连接车辆服务")
        } else {
            serviceFeed = AutoServiceRecordFeed(this, { settings },
                onReady = { accessGate.begin() },
                onLost = { reason ->
                    accessGate.reset()
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        // 直连预览已有权限时不依赖车辆服务存活；车辆旧值由 feed 自行清空。
                        accessGate.begin()
                    } else {
                        cameraPermissionState = "AutoService 连接中断：$reason"
                        stopPreview()
                    }
                    vehicle = vehicleFeed.snapshot()
                    currentPage?.onDataChanged()
                }, onChanged = {
                    if (foreground && ::vehicleFeed.isInitialized) {
                        vehicle = vehicleFeed.snapshot()
                        currentPage?.onDataChanged()
                    }
                })
            vehicleFeed = serviceFeed!!
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA || !BuildConfig.EMULATOR_CAMERA) return
        uiHandler.removeCallbacks(permissionTimeout)
        val completion = permissionCompletion
        permissionCompletion = null
        completion?.invoke(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED, "测试摄像头未授权")
    }

    /** 开始预览：由页面按需调用（四宫格四路 / 单路放大只开一路）。 */
    internal fun startPreview(directions: List<String>, fullSizeDirection: String? = null) {
        if (accessGate.state != RecordAccessGate.State.READY) {
            requestCameraPermission()
            return
        }
        surfaceCamera?.let { it.start(directions); return }
        val direct = directCamera
        if (direct == null) {
            requestCameraPermission()
            return
        }
        direct.start(directions, fullSizeDirection)
    }

    internal fun stopPreview() {
        // 后台录制（P8 S-REC 后台开关）：录制中且允许后台时保留相机流，录制不因切页/退后台中断
        if (controller.snapshot.isRecording && settings.background) return
        directCamera?.stop()
        surfaceCamera?.stop()
    }

    internal val usesSurfacePreview get() = !BuildConfig.EMULATOR_CAMERA
    internal fun bindPreviewSurface(direction: String, surface: Surface) {
        previewSurfaces[direction] = surface
        surfaceCamera?.bind(direction, surface)
    }
    internal fun unbindPreviewSurface(direction: String, surface: Surface) {
        if (previewSurfaces[direction] !== surface) return
        previewSurfaces.remove(direction)
        surfaceCamera?.bind(direction, null, surface)
    }

    internal fun setPreviewFullSize(direction: String?) {
        directCamera?.setFullSizeDirection(direction)
    }

    internal fun frameOf(direction: String): Bitmap? = previewFrames[direction]
    internal val platformProtocolVersion: Int get() = serviceFeed?.protocolVersion ?: 0
    internal val cameraAccessReady: Boolean get() = ::accessGate.isInitialized && accessGate.state == RecordAccessGate.State.READY

    /** 清掉某路的旧帧（切方向/关流时避免显示过期画面）。 */
    internal fun clearFrames() {
        previewFrames.clear()
    }

    /** 当前页视图（测试与诊断用）：为 null 说明页面没构建出来。 */
    internal fun currentPageView(): View? = runCatching { currentPage?.view() }.getOrNull()

    /** 退出沉浸回放，回到进入它的一级页。 */
    internal fun backFromPlayback() {
        if (playingSegmentId == null) return
        playingSegmentId = null
        showCurrent()
    }

    internal fun showActionSheet() {
        RecordActionSheet.show(this)
    }

    /** 页内有提示区就写页内（可回看），没有就用短提示；两者都不静默丢消息。 */
    internal fun toastLine(message: String) {
        Log.i(TAG, message)
        when (val page = currentPage) {
            is LivePage, is LibraryPage -> page.showTransient(message)
            else -> android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun dispatchSnapshot() {
        renderBadge()
        currentPage?.onSnapshotChanged()
    }

    private fun maybeShowOnboarding() {
        val prefs = getSharedPreferences("record.ui", Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ONBOARDED, false)) return
        OnboardingDialog.show(this) {
            prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
        }
    }

    // ---- 设置与探测 ----

    internal fun updateSettings(block: (RecordSettings) -> RecordSettings): RecordSettings {
        if (isDriving()) { toastLine("行驶中不可修改设置，请停稳后操作"); return settings }
        val previousInterval = settings.vehicleIntervalMs
        val next = block(settings)
        settings = next
        store.save(next)
        serviceFeed?.configure()
        if (next.vehicleIntervalMs != previousInterval) vehicleFeed.setInterval(next.vehicleIntervalMs)
        return next
    }

    /** 设置改动后需要联动刷新的地方（保留窗口、库容量等）。 */
    internal fun onSettingsChanged() {
        currentPage?.onDataChanged()
    }

    internal fun setThemeMode(mode: String) {
        if (settings.themeMode == mode) return
        updateSettings { it.copy(themeMode = mode) }
        recreate()
    }

    internal fun loadVolumes(deliver: (Result<List<VolumeInfo>>) -> Unit) {
        io.run({ storageVolumes.list() }, deliver)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (settings.themeMode != RecordSettings.THEME_SYSTEM) return
        val night = isNight(newConfig)
        if (night != systemNightAtBuild) {
            systemNightAtBuild = night
            recreate()
        }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(ticker)
        accessGate.close()
        uiHandler.removeCallbacks(permissionTimeout)
        permissionCompletion = null
        recordingEngine?.release()
        controller.close()
        preflightWorker.shutdownNow()
        io.close()
        directCamera?.release()
        surfaceCamera?.release()
        vehicleFeed.release()
        super.onDestroy()
    }

    private fun isNight(configuration: Configuration): Boolean =
        configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private companion object {
        const val TAG = "AutoRecordUi"
        const val MAX_HISTORY = 8
        const val TICK_MS = 1000L
        const val REQUEST_EXPORT = 4711
        const val KEY_MAIN = "record.ui.main"
        const val KEY_SUB = "record.ui.sub"
        const val KEY_PLAYING = "record.ui.playing"
        const val KEY_HISTORY = "record.ui.history"
        const val KEY_SCROLL = "record.ui.scroll"
        const val KEY_ONBOARDED = "record.ui.onboarded"
        const val REQ_CAMERA = 1001
    }
}
