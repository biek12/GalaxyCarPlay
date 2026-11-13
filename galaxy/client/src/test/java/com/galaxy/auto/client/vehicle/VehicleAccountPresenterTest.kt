package com.galaxy.auto.client.vehicle

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.galaxy.auto.client.R
import com.galaxy.auto.client.databinding.ActivityVehicleAccountBinding
import com.galaxy.auto.ui.GalaxyActionButton
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.TimeUnit

/** 真实 Presenter 绑定真实控件。连接端口只记录请求并在测试里回放。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class VehicleAccountPresenterTest {
    @Test
    fun `重复点击只发出一个请求且动作载荷与合同一致`() {
        val harness = Harness()
        harness.ready(unbound())
        harness.click(R.string.vehicle_account_code_refresh)
        harness.click(R.string.vehicle_account_code_refresh)
        assertEquals(listOf("state", "code"), harness.port.actions())
        val code = harness.port.requests[1]
        assertTrue(code.payload.getBoolean("refresh"))
        harness.port.reply(1, unbound())
        harness.click(R.string.vehicle_account_binding_confirm)
        val confirm = harness.port.requests[2]
        assertEquals("confirm", confirm.action)
        assertEquals("alpha", confirm.payload.getString("bindingId"))
        assertTrue(confirm.payload.getBoolean("accepted"))
        harness.port.reply(2, unbound())
        harness.click(R.string.vehicle_account_binding_reject, index = 1)
        val reject = harness.port.requests[3]
        assertEquals("beta", reject.payload.getString("bindingId"))
        assertFalse(reject.payload.getBoolean("accepted"))
        harness.port.reply(3, bound(enabled = false))
        harness.click(R.string.vehicle_account_unbind)
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(4, harness.port.requests.size)
        harness.click(R.string.vehicle_account_unbind)
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("unbind", harness.port.requests[4].action)
        assertTrue(
            harness.port.requests[4]
                .payload.isEmpty,
        )
        harness.port.reply(4, bound(enabled = false))
        harness.click(R.string.vehicle_account_online_enable)
        assertEquals("enable", harness.port.requests[5].action)
        assertTrue(
            harness.port.requests[5]
                .payload.isEmpty,
        )
        harness.port.reply(5, bound(enabled = true))
        harness.click(R.string.vehicle_account_online_pause)
        assertEquals("disable", harness.port.requests[6].action)
        assertFalse(
            harness.port.requests[0]
                .payload
                .containsKey("refresh"),
        )
        harness.close()
    }

    @Test
    fun `state不覆盖正常提示且无效JSON保留上一份账号`() {
        val harness = Harness()
        harness.presenter.onReady()
        assertEquals(
            harness.text(R.string.vehicle_account_hint_connecting),
            harness.binding.statusHint.text
                .toString(),
        )
        harness.port.reply(0, bound(enabled = true))
        assertEquals(
            harness.text(R.string.vehicle_account_hint_enabled),
            harness.binding.statusHint.text
                .toString(),
        )
        assertTrue(
            harness.binding.ownerLine.text
                .contains("保留账号"),
        )
        harness.presenter.onReady()
        harness.port.requests[1]
            .result
            .invoke(response(json = "{", error = "解析失败"))
        assertTrue(
            harness.binding.ownerLine.text
                .contains("保留账号"),
        )
        assertEquals(
            "解析失败",
            harness.binding.statusHint.text
                .toString(),
        )
        harness.close()
    }

    @Test
    fun `busy期间断线后新连接可以请求且旧回执不覆盖`() {
        listOf(
            "ipc:DeadObjectException",
            "connect:RemoteException",
            "bind:SecurityException",
            "service_disconnected",
            "binding_rejected",
            "binding_died",
            "service_unavailable",
        ).forEach { reason ->
            val harness = Harness()
            harness.presenter.onReady()
            assertTrue(
                harness.binding.onlineButton.isEnabled
                    .not(),
            )
            harness.presenter.onConnectionState(reason)
            assertEquals(
                harness.text(R.string.vehicle_account_hint_disconnected),
                harness.binding.statusHint.text
                    .toString(),
            )
            assertFalse(harness.binding.requestCodeButton.isEnabled)
            harness.presenter.onReady()
            assertEquals(listOf("state", "state"), harness.port.actions())
            harness.port.reply(0, bound(name = "旧账号"))
            assertFalse(
                harness.binding.ownerLine.text
                    .contains("旧账号"),
            )
            assertFalse(harness.binding.onlineButton.isEnabled)
            harness.port.reply(1, bound(name = "新账号"))
            assertTrue(
                harness.binding.ownerLine.text
                    .contains("新账号"),
            )
            harness.close()
        }
    }

    @Test
    fun `两秒轮询重复开始停止和销毁都不会叠加`() {
        val harness = Harness()
        harness.presenter.onReady()
        harness.port.reply(0, unbound())
        harness.presenter.onStart()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, harness.port.requests.size)
        harness.port.reply(1, unbound())
        shadowOf(Looper.getMainLooper()).idleFor(2000, TimeUnit.MILLISECONDS)
        assertEquals(3, harness.port.requests.size)
        harness.port.reply(2, unbound())
        harness.presenter.onStart()
        shadowOf(Looper.getMainLooper()).idleFor(2000, TimeUnit.MILLISECONDS)
        assertEquals(4, harness.port.requests.size)
        harness.port.reply(3, unbound())
        harness.presenter.onStop()
        shadowOf(Looper.getMainLooper()).idleFor(4000, TimeUnit.MILLISECONDS)
        assertEquals(4, harness.port.requests.size)
        harness.presenter.onStart()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(5, harness.port.requests.size)
        harness.presenter.close()
        shadowOf(Looper.getMainLooper()).idleFor(4000, TimeUnit.MILLISECONDS)
        assertEquals(5, harness.port.requests.size)
        assertTrue(harness.port.closed)
    }

    @Test
    fun `离线时待确认请求到期会从同一JSON消失且不发请求`() {
        val harness = Harness(now = NOW)
        harness.presenter.onReady()
        harness.port.reply(0, unbound(bindingExpiresAt = NOW + 1000))
        assertEquals(2, harness.buttons(R.string.vehicle_account_binding_confirm).size)
        harness.presenter.onConnectionState("service_disconnected")
        harness.presenter.onStart()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, harness.port.requests.size)
        harness.now = NOW + 1000
        shadowOf(Looper.getMainLooper()).idleFor(2000, TimeUnit.MILLISECONDS)
        assertTrue(harness.buttons(R.string.vehicle_account_binding_confirm).isEmpty())
        assertEquals(1, harness.port.requests.size)
        harness.close()
    }

    @Test
    fun `销毁后的回执不再写界面也不再发请求`() {
        val harness = Harness()
        harness.presenter.onReady()
        val hint =
            harness.binding.statusHint.text
                .toString()
        harness.presenter.close()
        harness.port.reply(0, bound())
        assertEquals(
            hint,
            harness.binding.statusHint.text
                .toString(),
        )
        assertEquals(1, harness.port.requests.size)
        assertTrue(harness.port.closed)
        harness.presenter.connect()
        assertEquals(0, harness.port.connects)
    }

    private class Harness(
        var now: Long = NOW,
    ) {
        val port = FakePort()
        val binding: ActivityVehicleAccountBinding
        val presenter: VehicleAccountPresenter

        init {
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            binding = ActivityVehicleAccountBinding.inflate(activity.layoutInflater)
            activity.setContentView(binding.root)
            presenter =
                VehicleAccountPresenter(
                    binding = binding,
                    port = port,
                    qr = VehicleQrRenderer(workerLooper = activity.mainLooper, encode = { error("二维码不在本测试编码") }),
                    clock = { now },
                    onBack = {},
                )
            presenter.bindActions()
        }

        fun ready(json: String) {
            presenter.onReady()
            port.reply(0, json)
        }

        fun click(
            label: Int,
            index: Int = 0,
        ) {
            buttons(label)[index].performClick()
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun buttons(label: Int): List<GalaxyActionButton> {
            val text = binding.root.context.getString(label)
            val found = mutableListOf<GalaxyActionButton>()

            fun walk(view: View) {
                if (view is GalaxyActionButton && view.text.toString() == text) {
                    found += view
                }
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) {
                        walk(view.getChildAt(index))
                    }
                }
            }
            walk(binding.root)
            return found
        }

        fun text(id: Int) = binding.root.context.getString(id)

        fun close() {
            presenter.close()
        }
    }

    private class FakePort : VehicleAccountPresenter.ClientPort {
        val requests = mutableListOf<Recorded>()
        var connects = 0
        var closed = false

        override fun connect() {
            connects += 1
        }

        override fun close() {
            closed = true
        }

        override fun request(
            action: String,
            payload: Bundle,
            result: (Bundle) -> Unit,
        ) {
            requests += Recorded(action, Bundle(payload), result)
        }

        fun actions() = requests.map { it.action }

        fun reply(
            index: Int,
            json: String? = null,
            error: String? = null,
        ) {
            val bundle = Bundle()
            if (json != null) {
                bundle.putString("json", json)
            }
            if (error != null) {
                bundle.putString("error", error)
            }
            requests[index].result.invoke(bundle)
        }

        class Recorded(
            val action: String,
            val payload: Bundle,
            val result: (Bundle) -> Unit,
        )
    }

    private fun response(
        json: String? = null,
        error: String? = null,
    ) = Bundle().apply {
        json?.let { putString("json", it) }
        error?.let { putString("error", it) }
    }

    private fun bound(
        enabled: Boolean = true,
        name: String = "保留账号",
    ) = JSONObject()
        .put("bound", true)
        .put("enabled", enabled)
        .put("owner", JSONObject().put("displayName", name).put("phoneMasked", "138****0000"))
        .put("basicInfo", JSONObject().put("model", "L6"))
        .toString()

    private fun unbound(bindingExpiresAt: Long = NOW + 60_000) =
        JSONObject()
            .put("bound", false)
            .put("enabled", true)
            .put("code", "0123456789abcdef")
            .put("expiresAt", NOW + 60_000)
            .put("scanUrl", "https://example.test/galaxy/app/vehicles#bind=0123456789abcdef")
            .put(
                "bindings",
                JSONArray()
                    .put(pending("alpha", "甲", bindingExpiresAt))
                    .put(pending("beta", "乙", bindingExpiresAt)),
            ).toString()

    private fun pending(
        id: String,
        name: String,
        expiresAt: Long,
    ) = JSONObject()
        .put("bindingId", id)
        .put("displayName", name)
        .put("phoneMasked", "138****0000")
        .put("expiresAt", expiresAt)

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
