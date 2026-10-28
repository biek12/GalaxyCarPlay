package com.shilapi.xcertplay

import android.content.Context
import android.os.Looper
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.steering.GalaxySteeringSessions
import com.shilapi.xcertplay.steering.SteeringAction
import com.shilapi.xcertplay.steering.SteeringBinding
import com.shilapi.xcertplay.steering.SteeringConfiguration
import com.shilapi.xcertplay.steering.SteeringConfigurationStore
import com.shilapi.xcertplay.steering.SteeringGesture
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * 投屏会话与方控监听的装配回归：同一会话重复挂接只登记一个目标，解绑重复调用无副作用，
 * 排队中被取代的挂接不再建立监听，控制器替换时先释放旧登记。
 * 断言直接读 GalaxySteeringSessions 的目标表，避免为测试新增产品侧查询接口。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class GalaxySteeringBindingTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun idleMain() {
        // 关闭方控开关：本组用例只验证目标登记，不发起真实Binder订阅。
        productPreferences().edit().putBoolean("galaxy_steering_enabled", false).commit()
        GalaxySteeringBinding.detach(null)
        idle()
    }

    @After fun release() {
        GalaxySteeringBinding.detach(null)
        idle()
        productPreferences().edit().clear().commit()
        context.getSharedPreferences("galaxy_steering_learning", 0).edit().clear().commit()
    }

    private fun productPreferences() = context.getSharedPreferences("xcertplay_airplay", 0)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun controller(active: Boolean = true): CarPlayController = mock(CarPlayController::class.java).also {
        doReturn(active).`when`(it).hasActiveSession()
    }

    private fun targets(): Map<Long, Any> =
        ReflectionHelpers.getField(requireNotNull(ReflectionHelpers.getField<Any?>(GalaxySteeringSessions, "runtime")), "targets")

    private fun targetControllerOf(target: Any): CarPlayController =
        ReflectionHelpers.getField(target, "controller")

    @Test fun repeatedAttachForTheSameControllerKeepsOneRegistration() {
        val controller = controller()
        GalaxySteeringBinding.attach(context, controller)
        idle()
        assertEquals(1, targets().size)
        // 真实入口会从 bindMedia/sessionStored/sessionStateChanged 多次调用挂接。
        GalaxySteeringBinding.attach(context, controller)
        GalaxySteeringBinding.attach(context, controller)
        idle()
        assertEquals(1, targets().size)
        assertSame(controller, targetControllerOf(targets().values.single()))
    }

    @Test fun repeatedDetachIsIdempotentAndReleasesTheRegistration() {
        val controller = controller()
        GalaxySteeringBinding.attach(context, controller)
        idle()
        GalaxySteeringBinding.detach(controller)
        idle()
        assertTrue(targets().isEmpty())
        GalaxySteeringBinding.detach(controller)
        GalaxySteeringBinding.detach(null)
        idle()
        assertTrue(targets().isEmpty())
    }

    @Test fun queuedAttachCancelledByDetachNeverOpensAListener() {
        val controller = controller()
        // 挂接还没在主线程执行就收到解绑：迟到的挂接必须丢弃，不能留下无主的监听。
        GalaxySteeringBinding.attach(context, controller)
        GalaxySteeringBinding.detach(controller)
        idle()
        assertTrue(targets().isEmpty())
    }

    @Test fun replacingTheControllerReleasesThePreviousRegistration() {
        val first = controller()
        val second = controller()
        GalaxySteeringBinding.attach(context, first)
        idle()
        GalaxySteeringBinding.attach(context, second)
        idle()
        assertEquals(1, targets().size)
        assertSame(second, targetControllerOf(targets().values.single()))
    }

    @Test fun detachForAnOlderControllerDoesNotRemoveTheCurrentSession() {
        val first = controller()
        val second = controller()
        GalaxySteeringBinding.attach(context, first)
        idle()
        GalaxySteeringBinding.attach(context, second)
        idle()
        GalaxySteeringBinding.detach(first)
        idle()
        assertEquals(1, targets().size)
        assertSame(second, targetControllerOf(targets().values.single()))
    }

    @Test fun sessionEndedReleasesOnlyWhenNoPhoneSessionRemains() {
        val controller = controller()
        GalaxySteeringBinding.attach(context, controller)
        idle()
        // 仍有活动会话的迟到false不能撤掉监听。
        GalaxySteeringBinding.onSessionEnded(controller)
        idle()
        assertEquals(1, targets().size)
        doReturn(false).`when`(controller).hasActiveSession()
        GalaxySteeringBinding.onSessionEnded(controller)
        idle()
        assertTrue(targets().isEmpty())
    }

    @Test fun switchReadsTheExistingProductKeyAndWritesBothForCompatibility() {
        val store = SteeringConfigurationStore(context)
        productPreferences().edit().putBoolean("galaxy_steering_enabled", false).commit()
        // D11：用户此前的关闭选择必须被新入口读到，而不是被新文件默认值覆盖。
        assertFalse(store.enabled())
        store.setEnabled(true)
        assertTrue(store.enabled())
        assertTrue(productPreferences().getBoolean("galaxy_steering_enabled", false))
        assertTrue(context.getSharedPreferences("galaxy_steering_learning", 0).getBoolean("enabled", false))
        // 旧文件里已确认的映射照常读回，切换开关不覆盖映射。
        val binding = SteeringBinding(0x2F, SteeringGesture.CLICK, SteeringAction.NEXT)
        store.save(SteeringConfiguration(listOf(binding)))
        store.setEnabled(false)
        assertEquals(listOf(binding), store.load().bindings)
    }
}