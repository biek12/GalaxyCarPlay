package com.galaxy.autorecord.core

import android.content.Context
import android.util.Log

/**
 * 方位 ↔ cameraId 映射（**标定数据，不是用户设置**）。
 *
 * 实测结论：`LENS_FACING` 恒为 FRONT、`SENSOR_ORIENTATION` 恒为 0、厂商不提供 vendor 方位 tag，
 * 所以方位**只能靠实测标定**得出，不能从 CameraCharacteristics 推断。
 * 本轮标定未完成（两个候选假设对 2/3 的判断相反），默认按**假设 A**（EVCam 位置序）落地。
 *
 * 设计原则（实测建议 §7.1）：标定结果是数据，改了只影响映射，不改代码；
 * 界面永远显示"假设 A / 已标定"的区别，不把假设当已确认。
 */
data class CameraLayout(
    /** direction（front/rear/left/right） → cameraId */
    val mapping: Map<String, Int>,
    /** 是否经过地面真值标定（有人在车前/车后走过，或用 md5 + 画面内容对号）。 */
    val calibrated: Boolean = false,
) {
    fun cameraIdOf(direction: String): Int? = mapping[direction]

    fun directionOf(cameraId: Int): String? = mapping.entries.firstOrNull { it.value == cameraId }?.key

    /** 四个 ID 已全占用；用户确认后整体交换两个方向，避免重复 ID 与半套配置。 */
    fun withCameraId(direction: String, cameraId: Int): CameraLayout? {
        if (!isValid || direction !in SegmentLayout.DIRECTIONS || cameraId !in CameraProfile.USABLE_IDS) return null
        val previousId = mapping.getValue(direction)
        val other = directionOf(cameraId) ?: return null
        return copy(mapping = mapping + mapOf(direction to cameraId, other to previousId), calibrated = false)
    }

    /** 四路是否都映射到实测可用 ID，且没有两路指向同一个 cameraId。 */
    val isValid: Boolean
        get() = SegmentLayout.DIRECTIONS.all { mapping[it] != null } &&
            mapping.values.all { it in CameraProfile.USABLE_IDS } &&
            mapping.values.toSet().size == mapping.size

    /** 重复映射到的 cameraId（诊断用；非空即映射不可用）。 */
    fun duplicates(): List<Int> = mapping.values
        .groupingBy { it }.eachCount().filterValues { it > 1 }.keys.toList()

    /** 未映射或映射到不可用 ID 的方向。 */
    fun badDirections(): List<String> = SegmentLayout.DIRECTIONS.filter { direction ->
        val id = mapping[direction] ?: return@filter true
        id !in CameraProfile.USABLE_IDS
    }

    /** 一行摘要："前=2 后=3 左=0 右=1（假设 A，未标定）"。 */
    fun summary(): String {
        val body = SegmentLayout.DIRECTIONS.joinToString(" ") { direction ->
            "${directionLabel(direction)}=${mapping[direction]?.toString() ?: "—"}"
        }
        val state = if (calibrated) "已标定" else if (mapping == ASSUMED_A.mapping) "假设 A，未标定" else "方位待核对，未标定"
        return "$body（$state）"
    }

    /** 只保留实测可用 ID 的映射副本（用于把脏配置收回来）。 */
    fun sanitized(): CameraLayout {
        val cleaned = mapping.filterValues { it in CameraProfile.USABLE_IDS }
        val deduped = mutableMapOf<String, Int>()
        val used = mutableSetOf<Int>()
        SegmentLayout.DIRECTIONS.forEach { direction ->
            val id = cleaned[direction]
            if (id != null && used.add(id)) deduped[direction] = id
        }
        return if (deduped.size == SegmentLayout.DIRECTIONS.size) copy(mapping = deduped) else this
    }

    companion object {
        /** 假设 A：EVCam 固定提交按 cameraIds 位置装配（0=左、1=右、2=前、3=后）。 */
        val ASSUMED_A = CameraLayout(
            mapping = mapOf("left" to 0, "right" to 1, "front" to 2, "rear" to 3),
            calibrated = false,
        )

        /** 假设 B：现场几何推断（2=后、3=前），与 A 对 2/3 的判断相反，作标定参考。 */
        val ASSUMED_B = CameraLayout(
            mapping = mapOf("left" to 0, "right" to 1, "front" to 3, "rear" to 2),
            calibrated = false,
        )

        /** 默认落地假设 A（实测建议 §7.1）。 */
        val DEFAULT = ASSUMED_A
    }
}

/**
 * 标定持久化。放在独立的 `record.camera` 文件里，与用户设置分开：
 * 这样"恢复默认设置"不会把标定结果冲掉，导出诊断也能单独看到标定状态。
 */
class CameraLayoutStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): CameraLayout {
        if (!prefs.contains(KEY_CALIBRATED)) return CameraLayout.DEFAULT
        val mapping = SegmentLayout.DIRECTIONS.mapNotNull { direction ->
            val id = prefs.getInt(prefix(direction), Int.MIN_VALUE)
            if (id == Int.MIN_VALUE) null else direction to id
        }.toMap()
        if (mapping.size != SegmentLayout.DIRECTIONS.size) {
            Log.w(TAG, "标定映射不完整，回落假设 A")
            return CameraLayout.DEFAULT
        }
        val layout = CameraLayout(mapping, prefs.getBoolean(KEY_CALIBRATED, false))
        if (!layout.isValid) {
            Log.w(TAG, "标定映射不合法（${layout.badDirections()} / 重复 ${layout.duplicates()}），回落假设 A")
            return CameraLayout.DEFAULT
        }
        return layout
    }

    /** 保存标定；非法映射直接拒绝（返回 false），不写入半套配置。 */
    fun save(layout: CameraLayout, calibrated: Boolean): Boolean {
        if (!layout.isValid) return false
        prefs.edit().apply {
            SegmentLayout.DIRECTIONS.forEach { direction ->
                putInt(prefix(direction), layout.mapping.getValue(direction))
            }
            putBoolean(KEY_CALIBRATED, calibrated)
            putLong(KEY_UPDATED_AT, System.currentTimeMillis())
        }.apply()
        return true
    }

    fun reset() {
        prefs.edit().clear().apply()
    }

    private fun prefix(direction: String) = "camera.id.$direction"

    private companion object {
        const val TAG = "AutoRecordCameraLayout"
        const val FILE = "record.camera"
        const val KEY_CALIBRATED = "camera.calibrated"
        const val KEY_UPDATED_AT = "camera.updatedAt"
    }
}
