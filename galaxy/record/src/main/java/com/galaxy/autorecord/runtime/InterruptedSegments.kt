package com.galaxy.autorecord.runtime

import com.galaxy.autorecord.core.*
import org.json.JSONObject
import java.io.File

/** 只固化本次进程所有、上一会话遗留的writing证据；不删视频，不伪造封装/complete。 */
internal object InterruptedSegments {
    fun finalizeEvidence(root:File,priorStart:Long,previousPid:Int):Int {
        var count=0
        val prefix=root.canonicalPath+File.separator
        if(priorStart==Long.MAX_VALUE||previousPid<=0)return 0
        // 上一会话时间已持久化；先按目录时间筛出可能遗留段，再读清单。
        // 不在主线程为了找少数writing段扫描全部MP4/侧录属性。
        val firstDay=java.text.SimpleDateFormat("yyyyMMdd",java.util.Locale.US).format(java.util.Date(priorStart))
        val days=SegmentLayout.segmentsRoot(root).listFiles().orEmpty().filter{!it.name.matches(Regex("[0-9]{8}"))||it.name>=firstDay}
        for(day in days) for(dir in day.listFiles().orEmpty()) {
            val at=SegmentScanner.parseDirStamp(dir.name)
            if(at!=null&&at<priorStart-priorStart%1000)continue
            if(!dir.canonicalPath.startsWith(prefix))continue
            val file=File(dir,SegmentLayout.FILE_MANIFEST)
            val old=runCatching{SegmentManifest.fromJson(file.readText())}.getOrNull()?:continue
            if(old.state!=SegmentManifest.STATE_WRITING||old.startedAtWallMillis<priorStart)continue
            val updated=old.copy(state=SegmentManifest.STATE_FAILED).toJson().put("interruption",JSONObject()
                .put("reason","previous_process_exit").put("previousPid",previousPid).put("recoveredAt",System.currentTimeMillis()))
            check(AtomicFiles.writeAtomically(file,updated.toString().toByteArray())){"interrupted_manifest_write_failed"}
            count++
        }
        return count
    }
}
