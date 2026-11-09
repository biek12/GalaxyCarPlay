package com.shilapi.xcertplay

import com.shilapi.xcertplay.hud.CarPlayNavigationSnapshot
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class GalaxyNavigationSessionTest {
    @Test fun publishesDistanceChangesWithSameRoadAndReplaysAfterBinderReplacement() {
        val values = mutableListOf<CarPlayNavigationSnapshot>()
        val calls = mutableListOf<String>()
        var token: Any? = Any()
        val port = object : L7NavigationPort {
            override fun initialize() { calls += "init" }
            override fun connectionToken() = token
            override fun start() { calls += "start" }
            override fun road(value: String) { calls += "road" }
            override fun guidance(value: CarPlayNavigationSnapshot) { values += value }
            override fun stop() { calls += "stop" }
            override fun close() { calls += "close" }
        }
        val session = L7NavigationSession(port, { true }, Executor { it.run() }, {})
        val first = CarPlayNavigationSnapshot(true, "road", 1, 0, 100, 1000, 300)
        session.update(first); session.update(first)
        assertEquals(listOf(first), values)
        val next = first.copy(distanceToNextMeters = 90)
        session.update(next)
        assertEquals(listOf(first, next), values)
        token = Any(); session.update(next)
        assertEquals(listOf(first, next, next), values)
        assertEquals(2, calls.count { it == "start" })
        session.close(); session.update(first)
        assertEquals(3, values.size)
        assertEquals(listOf("stop", "close"), calls.takeLast(2))
    }
}
