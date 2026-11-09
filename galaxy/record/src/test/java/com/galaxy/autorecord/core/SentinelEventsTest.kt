package com.galaxy.autorecord.core

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class SentinelEventsTest {
    @Test fun lateEventSidecarSurvivesSeparateFinalizationAndKeepsTime(){
        val dir=Files.createTempDirectory("sentinel-events").toFile()
        try{
            java.io.File(dir,"sentinel-events.jsonl").writeText("""{"startMs":59950,"endMs":59950,"ruleVersion":"VSTD:1"}
""")
            assertEquals(59950L,EventReader.read(dir).single().startMs)
            java.io.File(dir,SegmentLayout.FILE_EVENTS).writeText("""{"events":[{"type":"turn","startMs":100,"endMs":300}]}""")
            val marks=EventReader.read(dir)
            assertEquals(listOf(EventKind.TURN,EventKind.SENTINEL),marks.map{it.kind})
            assertEquals(59950L,marks.last().startMs)
        }finally{dir.deleteRecursively()}
    }
    @Test fun eventBeforeRecordingDoesNotInventPriorFrames(){
        val dir=Files.createTempDirectory("sentinel-before").toFile()
        try{
            java.io.File(dir,"sentinel-events.jsonl").writeText("""{"startMs":0,"endMs":0,"ruleVersion":"VSTD:2","beforeCapture":true}
""")
            assertTrue(EventReader.read(dir).single().changes.values.single().contains("无事前画面"))
        }finally{dir.deleteRecursively()}
    }
}
