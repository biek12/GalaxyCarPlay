package com.galaxy.autorecord.core

/**
 * 车辆数据模型（P1 数据条、P3 历史数据条、P9 记录项共用一份目录）。
 *
 * 纪律：
 * - 未取到值就是 [VehicleValueStatus.UNKNOWN]，显示"—"；**不补零、不用旧值顶替**；
 * - 源断开是 [VehicleValueStatus.UNAVAILABLE]，与"过期"分开；
 * - 目录键名与 AutoService 的车辆目录别名一致，采集统一由 AutoService 提供，
 *   本模块不做 ADB/Root/厂商 SDK 直连（需求稿 §4）。
 */
enum class VehicleGroup(val label: String) {
    DRIVE("行驶"),
    LIGHT("灯光"),
    ASSIST("辅助驾驶"),
    ENERGY("油电与里程"),
    CLIMATE("温度"),
    EXTENDED("扩展项"),
}

enum class VehicleValueStatus(val label: String) {
    LIVE("有效"),
    STALE("过期"),
    UNKNOWN("缺失"),
    UNAVAILABLE("数据不可用"),
}

/** 最小记录集（规格 R06 表格，P9 里固定开启且不可关：回放必需）。 */
enum class VehicleKey(
    val key: String,
    val label: String,
    val unit: String,
    val group: VehicleGroup,
    val minimalSet: Boolean = true,
) {
    SPEED("displaySpeed", "车速", "km/h", VehicleGroup.DRIVE),
    GEAR("gear", "挡位", "", VehicleGroup.DRIVE),
    RPM("rpm", "转速", "rpm", VehicleGroup.DRIVE),
    STEERING("steeringAngle", "转向角", "°", VehicleGroup.DRIVE),
    ACCELERATOR("accelerator", "油门", "%", VehicleGroup.DRIVE),
    BRAKE("brake", "制动踏板", "%", VehicleGroup.DRIVE),
    AUTO_HOLD("autoHold", "Auto Hold", "", VehicleGroup.DRIVE),
    PARKING_BRAKE("parkingBrake", "驻车制动", "", VehicleGroup.DRIVE),

    BRAKE_LIGHT("brakeLight", "制动灯", "", VehicleGroup.LIGHT),
    LEFT_TURN("leftTurn", "左转向灯", "", VehicleGroup.LIGHT),
    RIGHT_TURN("rightTurn", "右转向灯", "", VehicleGroup.LIGHT),
    LOW_BEAM("lowBeam", "近光", "", VehicleGroup.LIGHT),
    HIGH_BEAM("highBeam", "远光", "", VehicleGroup.LIGHT),
    AUTO_HIGH_BEAM("autoHighBeamEnabled", "自动远光", "", VehicleGroup.LIGHT),
    POSITION_LIGHT("positionLights", "示宽灯", "", VehicleGroup.LIGHT),
    REAR_FOG("rearFog", "后雾灯", "", VehicleGroup.LIGHT),

    ADAS_LATERAL("adasLateral", "横向辅助", "", VehicleGroup.ASSIST),
    CRUISE_STATE("cruiseState", "巡航", "", VehicleGroup.ASSIST),
    CRUISE_SET_SPEED("cruiseSetSpeed", "设定车速", "km/h", VehicleGroup.ASSIST),

    FUEL("fuel", "油量", "%", VehicleGroup.ENERGY),
    SOC("soc", "电量", "%", VehicleGroup.ENERGY),
    FUEL_RANGE("fuelRange", "燃油续航", "km", VehicleGroup.ENERGY),
    EV_RANGE("evRange", "纯电续航", "km", VehicleGroup.ENERGY),
    ODOMETER("odometer", "总里程", "km", VehicleGroup.ENERGY),

    INSIDE_TEMP("insideTemperature", "车内温度", "°C", VehicleGroup.CLIMATE),
    OUTSIDE_TEMP("outsideTemperature", "车外温度", "°C", VehicleGroup.CLIMATE),

    DOOR_STATE("doorState", "车门状态", "", VehicleGroup.EXTENDED, minimalSet = false),
    SEAT_BELT("seatBelt", "安全带", "", VehicleGroup.EXTENDED, minimalSet = false),
    TIRE_PRESSURE("tirePressure", "胎压", "kPa", VehicleGroup.EXTENDED, minimalSet = false),
    ;

    /** 界面标签：带单位（规格 §2.5：数值必须带单位，未知显示"—"）。 */
    fun displayLabel(): String = if (unit.isEmpty()) label else "$label($unit)"

    companion object {
        val minimal: List<VehicleKey> get() = entries.filter { it.minimalSet }
        val extended: List<VehicleKey> get() = entries.filter { !it.minimalSet }
        fun of(key: String): VehicleKey? = entries.firstOrNull { it.key == key }
    }
}

/** 单个数据项。value 已是展示串（未知时为"—"）。 */
data class VehicleValue(
    val key: VehicleKey,
    val value: String,
    val status: VehicleValueStatus,
    val sampledAtMillis: Long = 0L,
)

/** 一帧车辆快照。 */
data class VehicleSnapshot(
    val values: Map<VehicleKey, VehicleValue> = emptyMap(),
    val connected: Boolean = false,
    val intervalMs: Int = 500,
    /** 非空即说明为什么没有数据（"AutoService 未连接""摄像头/车辆域未接入"）。 */
    val reason: String = "AutoService 车辆数据未接入",
    val droppedSamples: Long = 0L,
) {
    fun value(key: VehicleKey): VehicleValue = values[key] ?: VehicleValue(key, "—", VehicleValueStatus.UNKNOWN)

    fun display(key: VehicleKey): String = value(key).value
}

/**
 * 车辆数据来源。录制引擎与界面都只依赖这个接口：
 * 当前实现是 [OfflineVehicleFeed]（未接入，全部"—"），AutoService 订阅就绪后替换实现即可，
 * 页面与控制器不需要改。
 */
interface VehicleFeed {
    fun snapshot(): VehicleSnapshot
    fun setInterval(intervalMs: Int)
    fun release()
    /** 录制专用逐包历史；null表示仅快照源，空列表表示这一拍没有新更新。 */
    fun drainRecordedUpdates(): List<RecordedVehicleUpdate>? = null
}

data class RecordedVehicleUpdate(val atNanos: Long, val snapshot: VehicleSnapshot)

/** 未接入实现：如实报告"没有数据"，而不是给 0。 */
class OfflineVehicleFeed(private val reason: String = "AutoService 车辆数据通道未接入（B2 后由服务订阅提供）") : VehicleFeed {
    @Volatile
    private var snapshot = VehicleSnapshot(reason = reason)

    override fun snapshot(): VehicleSnapshot = snapshot

    override fun setInterval(intervalMs: Int) {
        snapshot = snapshot.copy(intervalMs = intervalMs)
    }

    override fun release() = Unit
}
