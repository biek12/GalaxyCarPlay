package com.galaxy.autocluster.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.galaxy.autocluster.theme.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 导出实际组件渲染器的离线审阅图与主题包，供上车前目检/导入验收，非运行时依赖。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComponentCatalogTest {
    @Test fun exportActualDayNightStylePreviewsAndRoundTripThemes(){
        val folder=File("build/screenshots/catalog").apply {mkdirs()}
        val descriptions=JSONArray()
        for((index,preset)in ComponentPresets.all.withIndex()){
            val preview=ComponentSampleView(RuntimeEnvironment.getApplication())
            preview.bind(preset)
            preview.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(220,View.MeasureSpec.EXACTLY));preview.layout(0,0,360,220)
            val entry=JSONObject().put("id",index).put("type",preset.type).put("title",preset.title)
            for(night in listOf(false,true)){
                preview.showNight=night
                val bitmap=Bitmap.createBitmap(360,220,Bitmap.Config.ARGB_8888);preview.draw(Canvas(bitmap))
                val name="style-$index-${if(night)"night"else"day"}.png"
                File(folder,name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle();entry.put(if(night)"night"else"day",name)
            }
            descriptions.put(entry);preview.release()
        }
        File(folder,"catalog.json").writeText(descriptions.toString(2))
        val properties=JSONArray()
        for(spec in ComponentProperties.all){
            val fields=JSONArray()
            for(property in spec.properties)fields.put(JSONObject().put("key",property.key).put("label",property.label).put("section",property.section).put("styleable",property.styleable).put("default",property.default).put("kind",property.kind.name))
            properties.put(JSONObject().put("type",spec.type).put("title",spec.title).put("properties",fields))
        }
        File(folder,"properties.json").writeText(properties.toString(2))
        val themes=File("build/test-themes").apply {mkdirs()}
        for(theme in BuiltinThemes.packages()){
            val bytes=ThemePackageCodec.bytes(theme)
            val restored=ThemePackageCodec.read(bytes.inputStream())
            SceneXml.read(restored)
            File(themes,theme.metadata.id+".gctheme").writeBytes(bytes)
        }
    }
}
