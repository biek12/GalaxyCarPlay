package com.galaxy.auto.client.vehicle

import android.app.AlertDialog
import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.galaxy.auto.client.R
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.IAutoCallback
import com.galaxy.auto.contract.IAutoResult
import com.galaxy.auto.contract.IAutoService
import com.galaxy.auto.ui.GalaxyActionButton
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.FileOutputStream

/**
 * 真实 Activity、XML 和 AutoClient。
 *
 * 只在 bindService 边界放一个本地 Stub。诊断包名不在上传名单里，Stub 不做账号登记。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VehicleAccountActivityTest {
    private lateinit var service: LocalAutoService

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        assertFalse(app.packageName in DIAGNOSTIC_PACKAGES)
        service = LocalAutoService()
        shadowOf(app).setComponentNameAndServiceForBindService(SERVICE_NAME, service)
    }

    @After
    fun tearDown() {
        ShadowDialog.reset()
    }

    @Test
    fun `日间已绑定显示长期状态和解绑入口`() {
        service.json = bound("测试账号甲乙丙丁戊己庚辛壬癸")
        val controller = Robolectric.buildActivity(VehicleAccountActivity::class.java).setup()
        val activity = controller.get()
        try {
            settle { texts(activity).any { it.contains("已绑定 · 长期有效") } }
            assertTrue(texts(activity).contains("取消绑定"))
            assertEquals(
                activity.getColor(R.color.vehicle_account_success),
                text(activity, "已绑定 · 长期有效").currentTextColor,
            )
            assertTrue(service.actions().all { it == "state" })
            capture(activity, "account-bound-light.png")
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `未绑定页同时显示真二维码有效码和两条待确认`() {
        service.json = pending()
        val controller = Robolectric.buildActivity(VehicleAccountActivity::class.java).setup()
        val activity = controller.get()
        try {
            settle {
                val image = activity.findViewById<ImageView>(R.id.code_qr)
                image.drawable != null && buttons(activity, "确认是我的账号").size == 2
            }
            assertTrue(texts(activity).contains(CODE))
            val bitmap = (activity.findViewById<ImageView>(R.id.code_qr).drawable as BitmapDrawable).bitmap
            assertEquals(512, bitmap.width)
            assertEquals(2, buttons(activity, "拒绝此请求").size)
            buttons(activity, "确认是我的账号")[0].performClick()
            pump()
            assertTrue(service.calls.any { it.action == "confirm" && it.bindingId == "alpha" && it.accepted })
            buttons(activity, "拒绝此请求")[1].performClick()
            pump()
            assertTrue(service.calls.any { it.action == "confirm" && it.bindingId == "beta" && !it.accepted })
            capture(activity, "account-code-pending-light.png")
        } finally {
            controller.destroy()
        }
    }

    @Test
    @Config(sdk = [30], qualifiers = "night")
    fun `夜间已绑定保持同一结构和夜间色`() {
        service.json = bound("夜间账号")
        val controller = Robolectric.buildActivity(VehicleAccountActivity::class.java).setup()
        val activity = controller.get()
        try {
            settle { texts(activity).any { it.contains("已绑定 · 长期有效") } }
            assertEquals(0xFF53C69C.toInt(), activity.getColor(R.color.vehicle_account_success))
            assertEquals(
                activity.getColor(R.color.vehicle_account_success),
                text(activity, "已绑定 · 长期有效").currentTextColor,
            )
            assertTrue(texts(activity).contains("取消绑定"))
            capture(activity, "account-bound-night.png")
        } finally {
            controller.destroy()
        }
    }

    @Test
    @Config(sdk = [30], qualifiers = "night")
    fun `夜间断线保留原账号且不隐式发请求`() {
        service.json = bound("断线前账号")
        val controller = Robolectric.buildActivity(VehicleAccountActivity::class.java).setup()
        val activity = controller.get()
        try {
            settle { texts(activity).any { it.contains("断线前账号") } }
            val before = service.actions()
            assertTrue(before.all { it == "state" })
            connection().onServiceDisconnected(SERVICE_NAME)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(before, service.actions())
            assertTrue(texts(activity).any { it.contains("断线前账号") })
            assertTrue(texts(activity).any { it.contains("AutoService 未连接") })
            capture(activity, "account-error-night.png")
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `解绑取消不请求确认才发送且返回会结束页面`() {
        service.json = bound("解绑账号")
        val controller = Robolectric.buildActivity(VehicleAccountActivity::class.java).setup()
        val activity = controller.get()
        try {
            settle { buttons(activity, "取消绑定").isNotEmpty() }
            buttons(activity, "取消绑定").single().performClick()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertNotNull(dialog)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertFalse(service.actions().contains("unbind"))
            buttons(activity, "取消绑定").single().performClick()
            (ShadowDialog.getLatestDialog() as AlertDialog)
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .performClick()
            settle { service.actions().contains("unbind") }
            assertEquals(1, service.actions().count { it == "unbind" })
            buttons(activity, "返回").single().performClick()
            assertTrue(activity.isFinishing)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `错误回执保留上一份车辆且忙时不能连点`() {
        service.json = bound("原始账号")
        val controller = Robolectric.buildActivity(VehicleAccountActivity::class.java).setup()
        val activity = controller.get()
        try {
            settle { texts(activity).any { it.contains("原始账号") } }
            service.hold = true
            service.error = "服务端拒绝"
            service.json = "{"
            buttons(activity, "暂停车辆在线服务").single().performClick()
            settle { service.actions().contains("disable") }
            val size = service.actions().size
            buttons(activity, "暂停车辆在线服务").single().performClick()
            assertEquals(size, service.actions().size)
            assertFalse(buttons(activity, "暂停车辆在线服务").single().isEnabled)
            service.release()
            settle { texts(activity).any { it.contains("服务端拒绝") } }
            assertTrue(texts(activity).any { it.contains("原始账号") })
            val first = controller
            first.destroy()
        } finally {
            if (!activity.isDestroyed) {
                controller.destroy()
            }
        }
        service.hold = false
        service.error = null
        service.json = bound("重建账号")
        val again = Robolectric.buildActivity(VehicleAccountActivity::class.java).setup()
        try {
            settle { texts(again.get()).any { it.contains("重建账号") } }
        } finally {
            again.destroy()
        }
    }

    /** 本地服务只回放脚本，不实现第二份账号状态机，也不打开网络或 Root。 */
    private class LocalAutoService : IAutoService.Stub() {
        val calls = mutableListOf<WebCall>()
        var json: String? = null
        var error: String? = null

        @Volatile var hold = false
        private var pending: IAutoResult? = null

        fun actions() = synchronized(calls) { calls.map { it.action } }

        fun release() {
            val result =
                synchronized(this) {
                    hold = false
                    pending.also { pending = null }
                }
            result?.onResult(reply())
        }

        override fun getProtocolVersion() = AutoContract.VERSION

        override fun getSnapshot() = Bundle()

        override fun getVehicleCatalog(
            offset: Int,
            limit: Int,
        ) = Bundle()

        override fun getVehicleProperties(
            offset: Int,
            limit: Int,
        ) = Bundle()

        override fun getVehicleValue(
            propertyId: Int,
            areaId: Int,
            characterOffset: Int,
        ) = Bundle()

        override fun attach(
            owner: IBinder,
            role: String,
            callback: IAutoCallback,
        ) = Unit

        override fun detach(owner: IBinder) = Unit

        override fun setActive(
            owner: IBinder,
            active: Boolean,
        ) = Unit

        override fun publishMedia(
            owner: IBinder,
            value: Bundle,
        ) = Unit

        override fun publishNavigation(
            owner: IBinder,
            value: Bundle,
        ) = Unit

        override fun publishArtwork(
            owner: IBinder,
            fd: ParcelFileDescriptor,
        ) = Unit

        override fun executeShell(
            owner: IBinder,
            transport: String,
            command: String,
            timeoutMillis: Int,
            result: IAutoResult,
        ) = Unit

        override fun mediaCommand(
            owner: IBinder,
            command: Int,
        ) = Unit

        override fun configureScene(
            owner: IBinder,
            value: Bundle,
        ) = Unit

        override fun setPreviewSurface(
            owner: IBinder,
            surface: Surface?,
        ) = Unit

        override fun setProjectionPolicy(
            owner: IBinder,
            value: Bundle,
        ) = Unit

        override fun setVehicleFeed(
            owner: IBinder,
            keys: Array<String>?,
            minIntervalMillis: Int,
        ) = Unit

        override fun getVehicleValues(keys: Array<String>?) = Bundle()

        override fun setVehicleScan(enabled: Boolean) = Unit

        override fun requestClientPermissions(
            owner: IBinder,
            permissions: Array<String>?,
            result: IAutoResult,
        ) = Unit

        override fun configureSentinel(
            owner: IBinder,
            enabled: Boolean,
            result: IAutoResult,
        ) = Unit

        override fun reportSentinelIdle(
            owner: IBinder,
            powerSequence: Long,
        ) = Unit

        override fun vehicleWebRequest(
            owner: IBinder,
            action: String,
            payload: Bundle,
            result: IAutoResult,
        ) {
            synchronized(calls) {
                calls +=
                    WebCall(
                        action = action,
                        bindingId = payload.getString("bindingId"),
                        accepted = payload.getBoolean("accepted"),
                    )
            }
            if (hold) {
                pending = result
                return
            }
            result.onResult(reply())
        }

        override fun vehicleWebUpload(
            owner: IBinder,
            clipId: String,
            direction: String,
            file: ParcelFileDescriptor,
            length: Long,
            result: IAutoResult,
        ) = Unit

        override fun configureClusterElements(
            owner: IBinder,
            configuration: Bundle,
        ) = Unit

        override fun publishVisualMedia(
            owner: IBinder,
            value: Bundle,
        ) = Unit

        override fun publishTrackArtwork(
            owner: IBinder,
            track: String,
            fd: ParcelFileDescriptor,
        ) = Unit

        override fun prepareOemArtwork(
            owner: IBinder,
            fd: ParcelFileDescriptor,
            result: IAutoResult,
        ) = Unit

        override fun configureClusterImage(
            owner: IBinder,
            configuration: Bundle,
            image: ParcelFileDescriptor,
            result: IAutoResult,
        ) = Unit

        private fun reply() =
            Bundle().apply {
                error?.let { putString("error", it) }
                json?.let { putString("json", it) }
            }
    }

    private class WebCall(
        val action: String,
        val bindingId: String?,
        val accepted: Boolean,
    )

    private fun connection() =
        shadowOf(RuntimeEnvironment.getApplication() as Application).boundServiceConnections.single()

    private fun pump() {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.getAllStackTraces().keys.filterIsInstance<HandlerThread>().forEach { thread ->
            if (thread.isAlive) {
                runCatching { shadowOf(thread.looper).idle() }
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun settle(done: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!done() && System.nanoTime() < deadline) {
            pump()
            if (!done()) {
                Thread.sleep(10)
            }
        }
        assertTrue(done())
    }

    private fun texts(activity: VehicleAccountActivity): List<String> = collect(activity.window.decorView)

    private fun collect(view: View): List<String> =
        when (view) {
            is TextView -> listOf(view.text.toString())
            is ViewGroup -> (0 until view.childCount).flatMap { collect(view.getChildAt(it)) }
            else -> emptyList()
        }

    private fun text(
        activity: VehicleAccountActivity,
        value: String,
    ): TextView = findText(activity.window.decorView, value) ?: error(value)

    private fun findText(
        view: View,
        value: String,
    ): TextView? {
        if (view is TextView && view.text.toString() == value) {
            return view
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findText(view.getChildAt(index), value)?.let { return it }
            }
        }
        return null
    }

    private fun buttons(
        activity: VehicleAccountActivity,
        label: String,
    ): List<GalaxyActionButton> {
        val found = mutableListOf<GalaxyActionButton>()

        fun walk(view: View) {
            if (view is GalaxyActionButton && view.text.toString() == label) {
                found += view
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    walk(view.getChildAt(index))
                }
            }
        }
        walk(activity.window.decorView)
        return found
    }

    private fun capture(
        activity: VehicleAccountActivity,
        name: String,
    ) {
        val directory = System.getenv("GALAXY_ACCOUNT_SCREENSHOT_DIR")?.let(::File) ?: return
        directory.mkdirs()
        val view = activity.window.decorView
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 1800)
        val bitmap = Bitmap.createBitmap(1080, 1800, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        FileOutputStream(File(directory, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun bound(name: String) =
        JSONObject()
            .put("bound", true)
            .put("enabled", true)
            .put("owner", JSONObject().put("displayName", name).put("phoneMasked", "138****0000"))
            .put(
                "basicInfo",
                JSONObject().put("model", "L6").put("androidVersion", "11").put("serviceVersion", "test"),
            ).toString()

    private fun pending() =
        JSONObject()
            .put("bound", false)
            .put("enabled", true)
            .put("code", CODE)
            .put("expiresAt", System.currentTimeMillis() + 300_000)
            .put("scanUrl", "https://example.test/galaxy/app/vehicles#bind=$CODE")
            .put(
                "bindings",
                JSONArray()
                    .put(row("alpha", "待确认甲乙丙丁戊己庚辛壬癸"))
                    .put(row("beta", "待确认二")),
            ).toString()

    private fun row(
        id: String,
        name: String,
    ) = JSONObject()
        .put("bindingId", id)
        .put("displayName", name)
        .put("phoneMasked", "138****0000")
        .put("expiresAt", System.currentTimeMillis() + 300_000)

    private companion object {
        val SERVICE_NAME = ComponentName(AutoContract.SERVICE_PACKAGE, AutoContract.SERVICE_CLASS)
        val DIAGNOSTIC_PACKAGES =
            setOf(
                "com.galaxy.autorecord",
                "com.galaxy.autocluster",
                "com.ecarx.carplay",
                "com.ecarx.autoservice",
            )
        const val CODE = "0123456789abcdef"
    }
}
