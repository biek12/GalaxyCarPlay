package com.galaxy.auto.contract

import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],manifest=Config.NONE)
class MediaSnapshotTest {
    @Test fun originAndOpaqueProviderIdentitySurviveSanitization(){
        val input=Bundle().apply{
            putString("sourceKind","native");putString("sourceApp","Music App")
            putString("sourcePackage","music.example");putString("sourceTrackId","opaque-track-123")
            putString("track","stable-identity");putString("privateUnsupported","discard")
        }
        val output=AutoContract.sanitizeMedia(input)
        for(key in listOf("sourceKind","sourceApp","sourcePackage","sourceTrackId","track"))assertEquals(input.getString(key),output.getString(key))
        assertFalse(output.containsKey("privateUnsupported"))
    }
    @Test fun lyricsDemandSurvivesSceneRoundTripAndDefaultsOff(){
        val original=SceneConfiguration(1,"example",null,lyrics=true)
        assertTrue(SceneConfiguration.from(original.bundle()).lyrics)
        assertFalse(SceneConfiguration(2,"plain",null).lyrics)
    }
    @Test fun invalidTimelineCannotEnterNativeOrSceneConsumers(){
        val input=Bundle().apply{putStringArrayList("lyrics",arrayListOf("first","second"));putLongArray("lyricTimes",longArrayOf(1000,500))}
        assertFalse(AutoContract.sanitizeMedia(input).containsKey("lyricTimes"))
        input.putLongArray("lyricTimes",longArrayOf(0,1000))
        assertArrayEquals(longArrayOf(0,1000),AutoContract.sanitizeMedia(input).getLongArray("lyricTimes"))
    }
}
