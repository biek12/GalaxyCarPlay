package com.galaxy.autorecord.web

import com.galaxy.autorecord.core.*
import org.json.*

/** 原始车辆侧录转换为网页时间轴；不把未核验的踏板原始量当百分比发布。 */
internal object WebTelemetry {
    fun live(snapshot:VehicleSnapshot):JSONObject {
        val values=snapshot.values.filterValues{it.status==VehicleValueStatus.LIVE}.mapKeys{it.key.key}.mapValues{it.value.value}
        return sample(VehicleSample(0,values))
    }
    private fun sample(s:VehicleSample):JSONObject {
        val row=JSONObject().put("t",s.timeMs).put("q",if(s.values.isEmpty())"missing" else if(s.quality.values.any{it=="STALE"})"stale" else "good")
        val keys=mapOf("displaySpeed" to "speed","rpm" to "rpm","steeringAngle" to "steer","cruiseSetSpeed" to "cruiseSet","fuel" to "fuelPct","evRange" to "evRangeKm","odometer" to "odoKm","insideTemperature" to "tempInC","outsideTemperature" to "tempOutC")
        keys.forEach{(key,target)->s.values[key]?.toDoubleOrNull()?.takeIf{it.isFinite()}?.let{row.put(target,it)}}
        s.values["gear"]?.takeIf{it in listOf("P","R","N","D")}?.let{row.put("gear",it)}
        return row
    }
    fun encode(entry:SegmentEntry):JSONObject {
        val history=VehicleHistory.read(entry.dir)
        val deltas=history.zipWithNext().map{(a,b)->b.timeMs-a.timeMs}.filter{it>0}.sorted()
        val interval=deltas.getOrNull(deltas.size/2)?.toInt()?:0
        val samples=JSONArray()
        history.forEach{s->samples.put(sample(s))}
        val events=JSONArray()
        EventReader.read(entry.dir).forEachIndexed{i,event->events.put(JSONObject().put("eventId","${entry.id}-$i").put("key",event.kind.key)
            .put("startOffsetMs",event.startMs).put("endOffsetMs",if(event.isDiscrete)JSONObject.NULL else event.endMs)
            .put("kind",if(event.isDiscrete)"instant" else "continuous").put("sourceQuality","good").put("ruleVersion",1))}
        return JSONObject().put("schema","autorecord-v1").put("actualIntervalMs",interval).put("clockBiasUs",0)
            .put("samples",samples).put("events",events).put("sessionClips",JSONArray().put(JSONObject().put("clipId",entry.id).put("startAt",entry.startedAtMillis).put("endAt",entry.startedAtMillis+entry.durationMillis)))
    }
}
