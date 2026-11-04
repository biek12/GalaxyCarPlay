package com.galaxy.autorecord.core

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

data class RecordCameraRoute(val id: String, val width: Int, val height: Int)

/** 采集器只处理会话；车型路由/尺寸与模拟器真实摄像头能力由适配器决定。 */
interface RecordCameraAdapter {
    val label: String
    fun routes(): Map<String, RecordCameraRoute>
}
class L6RecordCameraAdapter(private val layout: () -> CameraLayout) : RecordCameraAdapter {
    override val label = "L6/L7 Camera2 直连"
    override fun routes(): Map<String, RecordCameraRoute> {
        val current = layout()
        if (!current.isValid) return emptyMap()
        return current.mapping.mapValues { RecordCameraRoute(it.value.toString(), CameraProfile.JPEG_WIDTH, CameraProfile.JPEG_HEIGHT) }
    }
}

/** 独立 emulator 构建才启用；电脑摄像头经模拟器 Camera2 输入，只映射一路，不复制冒充四路。 */
class EmulatorRecordCameraAdapter(context: Context) : RecordCameraAdapter {
    private val manager = context.getSystemService(CameraManager::class.java)
    override val label = "模拟器测试摄像头（非车辆画面）"
    override fun routes(): Map<String, RecordCameraRoute> {
        val id = manager.cameraIdList.firstOrNull() ?: return emptyMap()
        val map = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyMap()
        val sizes = map.getOutputSizes(ImageFormat.JPEG).orEmpty()
        val size = sizes.filter { it.width <= 1280 && it.height <= 800 }.maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height } ?: return emptyMap()
        return mapOf("front" to RecordCameraRoute(id, size.width, size.height))
    }
}
