package com.galaxy.autocluster.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.galaxy.auto.contract.ClusterDisplayConfiguration
import com.galaxy.auto.ui.GalaxySelection
import com.galaxy.auto.ui.GalaxyViews
import com.galaxy.autocluster.R
import com.galaxy.autocluster.databinding.ActivityClusterBinding
import com.galaxy.autocluster.databinding.PageClusterBackgroundBinding
import com.galaxy.autocluster.databinding.PageClusterHomeBinding
import com.galaxy.autocluster.databinding.PageClusterMusicBinding
import com.galaxy.autocluster.databinding.PageClusterOemBinding
import com.galaxy.autocluster.databinding.PageClusterRestoreBinding
import com.galaxy.autocluster.databinding.PageClusterResultBinding
import com.galaxy.autocluster.databinding.PageClusterSettingsBinding
import com.galaxy.autocluster.databinding.PageClusterThemesBinding
import com.galaxy.autocluster.runtime.ClusterDisplayStatus
import com.galaxy.autocluster.runtime.ClusterDisplayStore
import com.galaxy.autocluster.runtime.ClusterStatusInput
import com.galaxy.autocluster.ui.navigation.ClusterPage
import com.galaxy.autocluster.ui.pages.BackgroundPage
import com.galaxy.autocluster.ui.pages.BackgroundPageState
import com.galaxy.autocluster.ui.pages.ClusterHomePage
import com.galaxy.autocluster.ui.pages.ClusterSettingsPage
import com.galaxy.autocluster.ui.pages.DisplayResultPage
import com.galaxy.autocluster.ui.pages.MusicCardPage
import com.galaxy.autocluster.ui.pages.OemElementsPage
import com.galaxy.autocluster.ui.pages.RestoreConfirmationPage
import com.galaxy.autocluster.ui.pages.ThemeEntryPage
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 页面事件宿主：页面只回传用户意图，配置写入、服务命令与选图流程都由本 Presenter
 * 统一执行；页面不直接写配置/启动服务，状态映射不发动作。
 */
interface ClusterPageHost {
    /** 导航到指定页面；主导航、标题返回与按钮返回共用。 */
    fun nav(next: ClusterPage)

    /** 显式进入既有主题编辑页（首页“自定义仪表”与主题页“我的主题”）。 */
    fun openThemes()

    /** 首页“原厂增强”入口：刷新草稿模式后进入原厂增强页。 */
    fun oemEntry()

    /** 音乐开关用户操作：提交保留当前背景与图片的方案。 */
    fun musicSwitch(enabled: Boolean)

    /** 背景来源选择：image 且暂无草稿图片时先选图，不提前提交。 */
    fun backgroundSelected(source: String)

    /** 选图按钮（更换/新选）共用同一选择器流程。 */
    fun pickNewImage()

    /** 设置页“日夜外观”行：弹出公共选择对话框。 */
    fun appearanceClicked()

    /** 恢复确认：enabled=false 同步 commit → stop → 结果页。 */
    fun restoreConfirmed()

    /** 恢复取消：不写配置不发命令，回首页。 */
    fun restoreCancelled()
}

/**
 * 当前页内容的统一入口：`render` 用最新持久配置与快照刷新动态数据，
 * `onSnapshot` 接收观察连接投递的回执；只有承载真实绘制的页面需要处理快照。
 * 切页时旧内容随槽位整体替换，不保留跨页状态。
 */
interface ClusterPageContent {
    /** 快照/配置变化后只刷新当前页动态数据；不重建布局、不提交动作。 */
    fun render()

    /** 观察快照投递；默认无动作。 */
    fun onSnapshot(value: Bundle) {}
}

/**
 * Cluster 普通页面的唯一状态与路由 owner：持有页面状态（page/draft/snapshot/connected/
 * pendingRevision/readingImage）并把用户意图转交给 [ClusterDisplayActions]。
 *
 * 生命周期：`start` 创建一次观察会话并连接（原 onCreate 连接语义），`destroy` 关闭
 * 会话与图片导入并拒绝迟到回调；不随切页额外绑定，不随快照重建整页或音乐预览。
 * 配置变更 `rebind` 重新绑定壳与当前页，保留当前状态；预览随槽位释放后按新 Context
 * 重建一次。
 *
 * 调用链：会话快照 → 本 Presenter → `ClusterDisplayStatus` → 当前 page.render；
 * 点击 → 本 Presenter → Actions → 既有 Store/RenderService/Activity。
 * 线程：全部主线程；文件导入由 [BackgroundImageImport] 的有界 worker 负责。
 */
internal class ClusterAppPresenter(
    private val activity: Activity,
    private var binding: ActivityClusterBinding,
    private val store: ClusterDisplayStore,
    private val actions: ClusterDisplayActions,
    private val sessionFactory: (Context, (Bundle) -> Unit, (String) -> Unit) -> ClusterPageSession,
    importExecutor: Executor? = null,
) : ClusterPageHost {
    /** 暂存方案；不代表启用，控件呈现实际请求仍以 Store 为准。 */
    private var draft = store.draft()
    private var snapshot = Bundle()
    private var connected = false
    private var page = ClusterPage.HOME
    private var readingImage = false
    private var pendingRevision = 0L
    private var session: ClusterPageSession? = null
    private var current: ClusterPageContent? = null
    private var destroyed = false
    private var generation = 0
    private var imageImport: BackgroundImageImport? = null

    /** 图片导入调度；测试注入可控单线程时只替换调度边界，IO 代码仍是生产实现。 */
    private val imageExecutor: Executor = importExecutor ?: Executors.newSingleThreadExecutor()

    init {
        bindNavigation()
    }

    /**
     * 装配完成后的启动：恢复保存的页面与选图状态、展示当前页、创建并连接观察会话，
     * 最后执行 onCreate 已启用恢复（只一次）。顺序与旧 onCreate 一致。
     */
    fun start(savedPage: String?, savedReadingImage: Boolean) {
        if (destroyed) return
        page = ClusterPage.from(savedPage)
        readingImage = savedReadingImage
        show(page)
        val ticket = ++generation
        session =
            sessionFactory(
                activity,
                { value ->
                    if (!destroyed && ticket == generation) {
                        snapshot = value
                        connected = true
                        current?.onSnapshot(value)
                        renderCurrent()
                    }
                },
                { state ->
                    if (!destroyed && ticket == generation && state in DISCONNECTED_STATES) {
                        connected = false
                        renderCurrent()
                    }
                },
            )
        session?.connect()
        actions.restoreEnabledOutputIfNeeded()
        imageImport =
            BackgroundImageImport(activity, imageExecutor) { result -> onImportResult(result) }
    }

    /** 销毁：关闭观察会话与导入 IO；迟到回调与迟到结果一律失效，后台输出不受影响。 */
    fun destroy() {
        destroyed = true
        ++generation
        session?.close()
        session = null
        imageImport?.close()
        imageImport = null
    }

    /** 配置变更：用新 Context 的 binding 重建壳并重展示当前页，状态全部保留。 */
    fun rebind(newBinding: ActivityClusterBinding) {
        if (destroyed) return
        binding = newBinding
        bindNavigation()
        show(page)
    }

    /** 选图回执（旧 onActivityResult 合同）：仅请求码 41、readingImage、RESULT_OK 处理。 */
    fun onPickResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        if (requestCode != ClusterDisplayActions.REQUEST_PICK_IMAGE || !readingImage) return
        readingImage = false
        if (resultCode != Activity.RESULT_OK) return
        val path = data?.getStringExtra(ClusterDisplayActions.RESULT_EXTRA_IMAGE_PATH) ?: return
        imageImport?.importImage(File(path))
    }

    /** 硬件返回：返回 true 表示已处理；home 页返回 false 由 Activity 退出。 */
    fun onHardwareBack(): Boolean {
        val back = page.hardwareBack ?: return false
        show(back)
        return true
    }

    /** 当前页与选图状态供 Activity 保存实例状态。 */
    val savedPage: String get() = page.stored

    /** 选图进行中状态；随旧 `readingImage` 键保存与恢复。 */
    val isReadingImage: Boolean get() = readingImage

    override fun nav(next: ClusterPage) {
        show(next)
    }

    override fun openThemes() {
        actions.openThemes()
    }

    override fun oemEntry() {
        draft = store.draft().copy(mode = ClusterDisplayConfiguration.OEM_ELEMENTS)
        show(ClusterPage.OEM)
    }

    /** 音乐切换只改 music；image 采用当前 elements.image，空时才取草稿，不因草稿启用图片背景。 */
    override fun musicSwitch(enabled: Boolean) {
        submit(
            store.elements().copy(
                music = enabled,
                image = store.elements().image.ifEmpty { draft.image },
            ),
        )
    }

    /** 背景选择：image 且草稿无图先选图不提交；其余来源用当前草稿图片提交并保留 music。 */
    override fun backgroundSelected(source: String) {
        if (source == ClusterDisplayConfiguration.IMAGE_BACKGROUND && draft.image.isEmpty()) {
            startPickImage()
        } else {
            submit(store.elements().copy(background = source, image = draft.image))
        }
    }

    override fun pickNewImage() {
        startPickImage()
    }

    /** 外观选择：公共弹窗，取消不写；确认写 appearance、enabled 才 reload，然后 recreate。 */
    override fun appearanceClicked() {
        val values = actions.appearanceValues
        val labels =
            listOf(
                activity.getString(R.string.cluster_settings_appearance_option_auto),
                activity.getString(R.string.cluster_settings_appearance_option_day),
                activity.getString(R.string.cluster_settings_appearance_option_night),
            )
        GalaxySelection.select(
            activity,
            activity.getString(R.string.cluster_settings_appearance_dialog_title),
            labels,
            actions.currentAppearanceIndex(),
            activity.getString(R.string.cluster_settings_appearance_apply),
        ) { index ->
            val enabled = actions.applyAppearance(values[index])
            if (enabled) actions.reloadDisplay()
            activity.recreate()
        }
    }

    /** 恢复确认：Actions 同步 commit + stop 后进结果页；未收到 restored 前结果页显示等待。 */
    override fun restoreConfirmed() {
        actions.restoreOriginal()
        show(ClusterPage.RESULT)
    }

    override fun restoreCancelled() {
        show(ClusterPage.HOME)
    }

    /** 提交：Actions 保存并按 needsElements 发命令；失败保留 Toast 并重读持久配置，不伪装成功。 */
    private fun submit(value: ClusterDisplayConfiguration) {
        try {
            val outcome = actions.submitElements(value, snapshot)
            draft = outcome.value
            pendingRevision = outcome.pendingRevision
        } catch (error: Exception) {
            onSubmitFailed()
        }
        renderCurrent()
    }

    /** 提交失败的用户可见反馈与状态回滚。 */
    private fun onSubmitFailed() {
        Toast.makeText(activity, R.string.cluster_submit_failed_toast, Toast.LENGTH_LONG).show()
        draft = store.draft()
    }

    /** 打开既有选择器并标记选图进行中；取消/其他 result 不提交。 */
    private fun startPickImage() {
        readingImage = true
        actions.pickImage()
    }

    /** 图片导入结果：成功在当前持久配置上提交（保留当下 music），失败保留原提示。 */
    private fun onImportResult(result: Result<String>) {
        if (destroyed) return
        result
            .onSuccess { name ->
                submit(
                    store.elements().copy(
                        background = ClusterDisplayConfiguration.IMAGE_BACKGROUND,
                        image = name,
                    ),
                )
            }.onFailure {
                Toast.makeText(activity, R.string.cluster_image_invalid_toast, Toast.LENGTH_LONG).show()
            }
    }

    /** 切页：重建槽位内容并刷新主导航选中；不携带跨页状态。 */
    private fun show(next: ClusterPage) {
        page = next
        bindNavigation()
        val slot = binding.clusterPageSlot
        slot.removeAllViews()
        val inflater = activity.layoutInflater
        current =
            when (next) {
                ClusterPage.HOME ->
                    ClusterHomePage(
                        PageClusterHomeBinding.inflate(inflater, slot, true),
                        this,
                        ::currentDisplay,
                        ::restoreVisible,
                    )
                ClusterPage.OEM ->
                    OemElementsPage(
                        PageClusterOemBinding.inflate(inflater, slot, true),
                        this,
                        ::musicSummary,
                        ::backgroundSummary,
                        ::pageStatus,
                    )
                ClusterPage.MUSIC ->
                    MusicCardPage(
                        PageClusterMusicBinding.inflate(inflater, slot, true),
                        this,
                        Bundle(snapshot),
                        ::pageStatus,
                        ::musicChecked,
                    )
                ClusterPage.BACKGROUND ->
                    BackgroundPage(
                        PageClusterBackgroundBinding.inflate(inflater, slot, true),
                        this,
                        ::backgroundPageState,
                        ::pageStatus,
                    )
                ClusterPage.THEMES ->
                    ThemeEntryPage(
                        PageClusterThemesBinding.inflate(inflater, slot, true),
                        this,
                    )
                ClusterPage.SETTINGS ->
                    ClusterSettingsPage(
                        PageClusterSettingsBinding.inflate(inflater, slot, true),
                        this,
                        ::appearanceValue,
                    )
                ClusterPage.RESTORE ->
                    RestoreConfirmationPage(
                        PageClusterRestoreBinding.inflate(inflater, slot, true),
                        this,
                    )
                ClusterPage.RESULT ->
                    DisplayResultPage(
                        PageClusterResultBinding.inflate(inflater, slot, true),
                        this,
                        ::restoreResult,
                    )
            }
        current?.render()
    }

    /** 主导航选中归属、样式与点击：子页一律选中仪表；重建壳后重绑一次。 */
    private fun bindNavigation() {
        binding.clusterNavHome.setOnClickListener { nav(ClusterPage.HOME) }
        binding.clusterNavThemes.setOnClickListener { nav(ClusterPage.THEMES) }
        binding.clusterNavSettings.setOnClickListener { nav(ClusterPage.SETTINGS) }
        val selected = page.mainNav
        binding.clusterNavHome.isSelected = selected == ClusterPage.HOME
        binding.clusterNavThemes.isSelected = selected == ClusterPage.THEMES
        binding.clusterNavSettings.isSelected = selected == ClusterPage.SETTINGS
        GalaxyViews.navigationStyle(binding.clusterNavHome)
        GalaxyViews.navigationStyle(binding.clusterNavThemes)
        GalaxyViews.navigationStyle(binding.clusterNavSettings)
    }

    /** 只刷新当前页动态数据；切页才换布局。 */
    private fun renderCurrent() {
        if (destroyed) return
        current?.render()
    }

    /** 状态判定输入：持久配置 + 最新快照 + 连接与回执版本。 */
    private fun statusInput() =
        ClusterStatusInput(
            enabled = store.enabled,
            mode = store.read().mode,
            elements = store.elements(),
            snapshot = snapshot,
            connected = connected,
            pendingRevision = pendingRevision,
        )

    /** 首页“当前显示”。 */
    private fun currentDisplay(): String = ClusterDisplayStatus.homeOverview(activity.resources, statusInput())

    /** 恢复入口只在启用时可见。 */
    private fun restoreVisible(): Boolean = store.enabled

    /** 本页状态说明：music/background 页带页面维度，其余走通用判定。 */
    private fun pageStatus(): String {
        val element =
            when (page) {
                ClusterPage.MUSIC -> "music"
                ClusterPage.BACKGROUND -> "background"
                else -> ""
            }
        return ClusterDisplayStatus.elementStatus(activity.resources, statusInput(), element)
    }

    /** 结果页文案：enabled 走通用状态；只有 restored 回执才确认恢复。 */
    private fun restoreResult(): String = ClusterDisplayStatus.restoreResult(activity.resources, statusInput())

    /** 原厂增强页音乐入口当前值。 */
    private fun musicSummary(): String =
        activity.getString(
            if (store.elements().music) {
                R.string.cluster_oem_music_on
            } else {
                R.string.cluster_oem_music_off
            },
        )

    /** 原厂增强页背景入口当前值。 */
    private fun backgroundSummary(): String =
        ClusterDisplayStatus.backgroundName(activity.resources, store.elements().background)

    /** 音乐页开关回填值。 */
    private fun musicChecked(): Boolean = store.elements().music

    /** 背景页动态数据：选中来源、当前说明与按钮可见性，条件与旧分支一致。 */
    private fun backgroundPageState(): BackgroundPageState {
        val elements = store.elements()
        val noteRes =
            when (elements.background) {
                ClusterDisplayConfiguration.IMAGE_BACKGROUND -> R.string.cluster_background_note_image
                ClusterDisplayConfiguration.CARPLAY_MAP -> R.string.cluster_background_note_carplay
                ClusterDisplayConfiguration.LOCAL_MAP -> R.string.cluster_background_note_local
                else -> null
            }
        return BackgroundPageState(
            selectedSource = elements.background,
            noteRes = noteRes,
            draftHintVisible =
                elements.background != ClusterDisplayConfiguration.IMAGE_BACKGROUND &&
                    draft.image.isNotEmpty(),
            changeImageVisible = elements.background == ClusterDisplayConfiguration.IMAGE_BACKGROUND,
            pickNewVisible = elements.background != ClusterDisplayConfiguration.IMAGE_BACKGROUND,
        )
    }

    /** 设置页当前外观显示值；未知持久值按跟随系统呈现，与旧实现一致。 */
    private fun appearanceValue(): String =
        activity.getString(
            when (actions.currentAppearanceValue()) {
                "day" -> R.string.cluster_settings_appearance_option_day
                "night" -> R.string.cluster_settings_appearance_option_night
                else -> R.string.cluster_settings_appearance_option_auto
            },
        )

    /** 观察状态里属于“连接已失效”的固定取值，与旧实现一致。 */
    private companion object {
        val DISCONNECTED_STATES = setOf("service_disconnected", "binding_died", "service_unavailable")
    }
}
