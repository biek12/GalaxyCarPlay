package com.shilapi.xcertplay.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 路由只通过具体回调观察别名、回退、上次分类和草稿确认顺序。 */
class GalaxySettingsRouterTest {
    @Test
    fun historicalAliasesNormalizeToCurrentPages() {
        val harness = Harness()
        assertEquals("settings-connection", harness.router.normalize("connection"))
        assertEquals("settings-debug", harness.router.normalize("diagnostics"))
        assertEquals("settings-debug", harness.router.normalize("settings-diagnostics"))
        assertEquals("settings-logs", harness.router.normalize("settings-debug-logs"))
        assertEquals("settings-about", harness.router.normalize("about"))
        assertEquals("home", harness.router.normalize("unknown-page"))
        assertEquals("settings-connection-wireless", harness.router.normalize("settings-connection-wireless"))
    }

    @Test
    fun backTargetsReturnToParentAndHomeExpandsNavigation() {
        val harness = Harness()
        assertEquals("settings-connection", harness.router.backTarget("settings-connection-wireless"))
        assertEquals("settings-connection", harness.router.backTarget("settings-connection-usb"))
        assertEquals("settings", harness.router.backTarget("settings-connection"))
        assertEquals("settings", harness.router.backTarget("settings-debug"))
        assertEquals("settings-debug", harness.router.backTarget("settings-debug-codec"))
        assertEquals("home", harness.router.backTarget("settings"))
        harness.page = "home"
        harness.rendered = "home"
        harness.router.back()
        assertEquals(listOf("expand"), harness.events)
        harness.expandAvailable = false
        harness.router.back()
        assertEquals(listOf("expand", "system-back"), harness.events)
    }

    @Test
    fun settingsFromHomeRestoresLastCategoryAndSettingsRootCollapsesIt() {
        val harness = Harness()
        harness.page = "home"
        harness.rendered = "home"
        harness.lastSettings = "settings-audio"
        harness.router.select("settings")
        assertEquals("settings-audio", harness.page)
        assertEquals(listOf("show:settings-audio"), harness.events)
        harness.router.select("settings")
        assertEquals("settings", harness.page)
        assertEquals(listOf("show:settings-audio", "show:settings"), harness.events)
    }

    @Test
    fun pictureConnectionExitAndCarHomeDispatchConcreteEvents() {
        val harness = Harness()
        harness.page = "settings-general"
        harness.rendered = "settings-general"
        harness.active = true
        harness.router.select("home")
        assertEquals("settings-general", harness.page)
        assertEquals(listOf("projection"), harness.events)
        harness.active = false
        harness.router.select("home")
        assertEquals("home", harness.page)
        harness.router.select("connection")
        assertEquals("settings-connection", harness.page)
        harness.router.select("exit")
        harness.router.select("car-home")
        assertEquals(
            listOf("projection", "show:home", "connection", "show:settings-connection", "exit", "car-home"),
            harness.events,
        )
    }

    @Test
    fun unsavedVehicleDraftIsConfirmedBeforeNavigation() {
        val harness = Harness()
        harness.page = "settings-vehicle"
        harness.rendered = "settings-vehicle"
        harness.dirty = true
        harness.router.select("connection")
        assertEquals(listOf("request-leave"), harness.events)
        assertEquals("settings-vehicle", harness.page)
        harness.dirty = false
        harness.proceed!!.invoke()
        assertEquals(
            listOf("request-leave", "connection", "show:settings-connection"),
            harness.events,
        )
        assertEquals("settings-connection", harness.page)
    }

    @Test
    fun leavingDirtyVehicleByBackRestoresPageUntilDiscard() {
        val harness = Harness()
        harness.page = "settings-vehicle"
        harness.rendered = "settings-vehicle"
        harness.dirty = true
        harness.router.back()
        assertEquals(listOf("request-leave"), harness.events)
        assertEquals("settings-vehicle", harness.page)
        assertFalse(harness.events.any { it.startsWith("show") })
        harness.dirty = false
        harness.proceed!!.invoke()
        assertEquals("settings", harness.page)
        assertTrue(harness.events.contains("show:settings"))
    }

    private class Harness {
        val events = mutableListOf<String>()
        var page = "home"
        var lastSettings = "settings"
        var rendered: String? = null
        var dirty = false
        var active = false
        var expandAvailable = true
        var proceed: (() -> Unit)? = null
        val router: GalaxySettingsRouter =
            GalaxySettingsRouter(
                vehicleDirty = { dirty },
                requestLeave = { next ->
                    events += "request-leave"
                    proceed = next
                },
                openConnectionMenu = { openSettings ->
                    events += "connection"
                    openSettings()
                },
                confirmExit = { events += "exit" },
                openCarHome = { events += "car-home" },
                sessionActive = { active },
                openProjection = { events += "projection" },
                expandNavigation = {
                    if (!expandAvailable) {
                        false
                    } else {
                        events += "expand"
                        true
                    }
                },
                systemBack = { events += "system-back" },
                page = { page },
                setPage = { page = it },
                lastSettingsPage = { lastSettings },
                renderedPage = { rendered },
                show = {
                    router.guardVehicleLeave {
                        events += "show:$page"
                        rendered = page
                    }
                },
            )
    }
}
