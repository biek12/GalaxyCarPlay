package com.galaxy.autorecord.runtime
import com.galaxy.autorecord.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files
import java.io.File
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[30])
class InterruptedSegmentsTest {
 @Test fun productionDirectorySecondStillRecoversMillisecondSessionAnchor() {
  val root=Files.createTempDirectory("record-recovery-anchor").toFile()
  try {
   val start=1_793_865_400_742L
   val dir=SegmentLayout.segmentDir(root,start,1).apply{mkdirs()}
   File(dir,SegmentLayout.FILE_MANIFEST).writeText(SegmentManifest(segmentId=dir.name,startedAtWallMillis=start,
    startedAtMonotonicNanos=1,durationMillis=0,state=SegmentManifest.STATE_WRITING,storageVolumeUuid=null,
    channels=emptyList(),vehicle=null,events=null,appVersionName="test").toJson().toString())
   assertEquals(1,InterruptedSegments.finalizeEvidence(root,start,42))
   assertEquals(SegmentManifest.STATE_FAILED,SegmentManifest.fromJson(File(dir,SegmentLayout.FILE_MANIFEST).readText())!!.state)
  }finally{root.deleteRecursively()}
 }
 @Test fun onlyPreviousSessionWritingBecomesTerminalWithoutChangingBytesOrCompleteFiles() {
  val root=Files.createTempDirectory("record-interrupted").toFile()
  try {
   fun make(name:String,at:Long,state:String):File {
    val dir=File(SegmentLayout.segmentsRoot(root),"2026-11-05/$name").apply{mkdirs()}
    File(dir,SegmentLayout.FILE_MANIFEST).writeText(SegmentManifest(segmentId=name,startedAtWallMillis=at,
     startedAtMonotonicNanos=1,durationMillis=0,state=state,storageVolumeUuid=null,channels=emptyList(),vehicle=null,events=null,appVersionName="test").toJson().toString())
    File(dir,"front.mp4.writing").writeBytes(byteArrayOf(1,2,3))
    return dir
   }
   val older=make("older",100,SegmentManifest.STATE_WRITING)
   val crashed=make("crashed",200,SegmentManifest.STATE_WRITING)
   val complete=make("complete",300,SegmentManifest.STATE_COMPLETE)
   assertEquals(1,InterruptedSegments.finalizeEvidence(root,200,42))
   assertEquals("failed",SegmentManifest.fromJson(File(crashed,SegmentLayout.FILE_MANIFEST).readText())!!.state)
   assertEquals("writing",SegmentManifest.fromJson(File(older,SegmentLayout.FILE_MANIFEST).readText())!!.state)
   assertEquals("complete",SegmentManifest.fromJson(File(complete,SegmentLayout.FILE_MANIFEST).readText())!!.state)
   assertArrayEquals(byteArrayOf(1,2,3),File(crashed,"front.mp4.writing").readBytes())
   assertEquals(0,InterruptedSegments.finalizeEvidence(root,200,42))
  }finally{root.deleteRecursively()}
 }
}
