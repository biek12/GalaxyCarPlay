package com.galaxy.autocluster.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import com.galaxy.autocluster.preview.SceneData

/**
 * 本机样式与动画使用的固定演示样本。
 *
 * 只交给预览绘制器消费，不发布 IPC，也不读取车辆或媒体服务。样本字段和示例封面由本对象持有。
 */
internal object DemoContent {
    /**
     * 按调用方时钟 [now] 组装一条演示快照。
     *
     * [speed]、[playing] 与 [elapsed] 只替换示例里的车速、播放状态和进度，其余字符串与歌词时间保持固定。
     * 车辆信号和媒体的接收时间写成 [now]，供预览用同一时钟判断有效。
     */
    fun data(
        now: Long,
        speed: Int = 68,
        playing: Boolean = false,
        elapsed: Long = 72000,
    ): SceneData =
        SceneData(
            Bundle().apply {
                putBundle(
                    "vehicle",
                    Bundle().apply {
                        val values =
                            mapOf(
                                "cruiseState" to "active",
                                "cruiseSetSpeed" to "100",
                                "regenLevel" to "auto",
                                "regenPowerKw" to "60",
                                "drivePowerKw" to "0",
                                "leftTurn" to "0",
                                "rightTurn" to "1",
                                "lowBeam" to "1",
                                "highBeam" to "0",
                                "driverSeatbeltWarning" to "1",
                                "parkingBrake" to "0",
                                "positionLights" to "1",
                                "fuelConsumption" to "5.8",
                                "electricConsumption" to "14.7",
                                "displaySpeed" to speed.toString(),
                                "rpm" to "2300",
                                "soc" to "78",
                                "fuel" to "62",
                                "evRange" to "397",
                                "fuelRange" to "520",
                                "gear" to "D",
                                "driveMode" to "增程",
                                "accelerator" to "18",
                                "brake" to "0",
                                "steeringAngle" to "12",
                                "outsideTemperature" to "23",
                                "tire.frontLeft" to "238",
                                "tire.frontRight" to "240",
                                "tire.rearLeft" to "239",
                                "tire.rearRight" to "241",
                            )
                        for ((key, value) in values) {
                            putBundle(
                                key,
                                Bundle().apply {
                                    putString("value", value)
                                    putString("status", "VALID")
                                    putLong("receivedAt", now)
                                    if (key.endsWith("PowerKw")) putString("unit", "kW")
                                },
                            )
                        }
                    },
                )
                putBundle(
                    "media",
                    Bundle().apply {
                        putString("track", "local-preview")
                        putString("title", "Night Drive")
                        putString("artist", "The Example")
                        putString("album", "在路上")
                        putBoolean("playing", playing)
                        putLong("elapsed", elapsed)
                        putLong("receivedAt", now)
                        putLong("duration", 211000)
                        putStringArrayList(
                            "lyrics",
                            arrayListOf(
                                "城市灯火渐渐远去",
                                "风在夜色里轻轻吹",
                                "沿着光 一直向前",
                                "让心跟随这条路",
                                "直到天边慢慢亮起",
                            ),
                        )
                        putLongArray("lyricTimes", longArrayOf(0, 50000, 70000, 90000, 120000))
                    },
                )
                putBundle(
                    "navigation",
                    Bundle().apply {
                        putString("road", "示意道路")
                        putString("distance", "2.1 km")
                        putString("arrival", "14:32 到达")
                        putString("remaining", "22 分钟")
                        putString("remainingDistance", "18 km")
                        putString("maneuver", "3")
                    },
                )
            },
        )

    /**
     * 进程内惰性生成的 256×256 示例封面。
     *
     * 首次读取时在调用线程绘制，之后复用同一张位图。绘制结果留在本对象，不交给服务回收。
     */
    val artwork: Bitmap by lazy {
        Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader =
                LinearGradient(
                    0f,
                    0f,
                    256f,
                    256f,
                    intArrayOf(0xFF254F62.toInt(), 0xFF7A99A8.toInt(), 0xFFD5BD9A.toInt()),
                    null,
                    Shader.TileMode.CLAMP,
                )
            canvas.drawRect(0f, 0f, 256f, 256f, paint)
            paint.shader = null
            for (i in 0..5) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                paint.color = 0x55FFFFFF
                canvas.drawCircle(130f, 105f, 30f + i * 17, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textSize = 21f
            paint.typeface = Typeface.create("sans-serif-medium", 0)
            canvas.drawText("ON THE WAY", 45f, 219f, paint)
        }
    }
}
