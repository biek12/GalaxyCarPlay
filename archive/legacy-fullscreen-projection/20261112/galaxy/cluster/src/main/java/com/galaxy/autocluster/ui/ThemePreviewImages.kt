package com.galaxy.autocluster.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import com.galaxy.autocluster.theme.*
import com.galaxy.autocluster.theme.render.*
import java.io.ByteArrayOutputStream

/** 仅供主题库/主题包的静态样式图；完整投屏不经过此Bitmap路径。 */
internal object ThemePreviewImages {
    fun render(context: Context,theme: ThemePackage,night: Boolean=true,width: Int=960): Bitmap {
        val scene=SceneXml.read(theme)
        return ThemeResources(theme,scene,context.cacheDir).use { resources->
            val bitmap=Bitmap.createBitmap(width,width*532/1920,Bitmap.Config.ARGB_8888)
            val painter=SceneRenderer(resources)
            try {
                val now=SystemClock.elapsedRealtime()
                painter.render(Canvas(bitmap),CompiledScene(scene,night,resources),DemoContent.data(now),now,
                    ArtworkFrame(DemoContent.artwork,null,0),demoMap=true)
            }finally{painter.close()}
            bitmap
        }
    }
    fun withPreview(context: Context,theme: ThemePackage): ThemePackage {
        val image=render(context,theme)
        val bytes=ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
        val assets=theme.copyEntries().filterKeys { it !in setOf("manifest.xml","scene.xml","styles.xml") }+("preview.png" to bytes)
        return ThemePackageCodec.create(theme.metadata,theme.content("scene.xml"),theme.content("styles.xml"),assets)
    }
}
