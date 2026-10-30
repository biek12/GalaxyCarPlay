package com.ecarx.autoservice.vehicle

import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30], manifest=Config.NONE)
class ClusterFieldDiagnosticsTest {
    @Test fun onlyNamedFiniteInstrumentValuesAreExportedAndAgeIsPreserved() {
        val values=Bundle().apply {
            putString("credential","private")
            for ((key,value) in mapOf("cruiseState" to "active", "displaySpeed" to "NaN", "electricPowerRaw" to "48.5", "vin" to "private"))
                putBundle(key,Bundle().apply { putString("value",value);putString("status","VALID");putLong("receivedAt",1000);putString("unit","raw") })
        }
        val snapshot=Bundle().apply { putBundle("vehicle",values);putString("artworkUri","private") }
        val result=ClusterFieldDiagnostics.collect(snapshot,3500)
        val fields=result.getJSONObject("fields")
        assertEquals("active",fields.getJSONObject("cruiseState").getString("value"))
        assertEquals("STALE",fields.getJSONObject("cruiseState").getString("status"))
        assertTrue(fields.getJSONObject("displaySpeed").isNull("value"))
        assertEquals(48.5,fields.getJSONObject("electricPowerRaw").getDouble("value"),0.0)
        assertFalse(result.toString().contains("private"));assertFalse(fields.has("regenPowerKw"))
    }
}
