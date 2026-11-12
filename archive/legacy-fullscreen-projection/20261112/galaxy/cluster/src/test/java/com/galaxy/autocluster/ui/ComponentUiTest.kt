package com.galaxy.autocluster.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.galaxy.autocluster.theme.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComponentUiTest {
    private fun descendants(view: View): List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap {descendants(view.getChildAt(it))}else emptyList()
    private fun measure(view: View,width: Int,height: Int){view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height)}
    @Test fun galleryShowsThreeRealPreviewsPerRowAndBrowsingCannotAdd() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        val gallery=ComponentGallery(activity)
        activity.setContentView(gallery);measure(gallery,1080,1300)
        assertEquals(ComponentPresets.all.size/3,gallery.adapter.count)
        val row=gallery.adapter.getView(0,null,gallery) as LinearLayout
        assertEquals(3,row.childCount)
        assertEquals(3,descendants(row).count {it is ComponentSampleView})
        assertTrue(descendants(gallery).filterIsInstance<Button>().none {it.text.contains("添加")})
        (row.getChildAt(0)).performClick()
        val dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog)
        assertTrue(descendants(dialog.window!!.decorView).filterIsInstance<Button>().none {it.text.contains("添加")})
        dialog.dismiss()
        val folder=File("build/screenshots/editor").apply {mkdirs()}
        val bitmap=Bitmap.createBitmap(1080,1300,Bitmap.Config.ARGB_8888);gallery.draw(Canvas(bitmap))
        File(folder,"component-library.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        // View的Canvas尺寸属于整张窗口，预览必须用自身边界适配，不能将表盘绘到裁剪区外。
        val preview=descendants(gallery).filterIsInstance<ComponentSampleView>().first()
        val location=IntArray(2);preview.getLocationOnScreen(location)
        var bright=0
        for(y in location[1] until location[1]+preview.height)for(x in location[0] until location[0]+preview.width){
            val color=bitmap.getPixel(x.coerceIn(0,1079),y.coerceIn(0,1299))
            if(android.graphics.Color.red(color)>150&&android.graphics.Color.green(color)>150)bright++
        }
        assertTrue("组件缩略图必须实际绘出刻度与读数",bright>100)
        bitmap.recycle();controller.pause().stop().destroy()
    }
    @Test fun onlyEditorChooserAddsAndEachPresetIsValidForBothModes() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        var selected: ComponentPreset?=null
        val chooser=ComponentGallery(activity){selected=it}
        val row=chooser.adapter.getView(0,null,chooser) as LinearLayout
        row.getChildAt(1).performClick();assertEquals(ComponentPresets.all[1],selected)
        assertEquals(ComponentProperties.all.size,ComponentPresets.all.map {it.type}.toSet().size)
        for(preset in ComponentPresets.all){
            val sample=ComponentPresets.sample(preset)
            SceneValidator.validate(ThemeScene(SceneDocument(listOf(sample)),SceneStyles()),emptySet())
        }
    }
    @Test fun lyricsAndGaugeHaveDifferentDetailedPropertySections() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val theme=BuiltinThemes.packages().first();val session=EditorSession(theme,true)
        val panel=ComponentPropertyPanel(activity,{}, {emptyList()},{_,_->})
        val lyric=session.add("lyrics");session.choose(lyric);panel.bind(session)
        val lyrics=descendants(panel).filterIsInstance<TextView>().map {it.text.toString()}
        val spec=ComponentProperties.byType.getValue("lyrics")
        for(key in listOf("font","fontSize","activeSize","lines","lineGap","transitionMs"))assertTrue(key,spec.properties.any {it.key==key})
        assertTrue(lyrics.any {it.contains("歌词")})
        val speed=session.add("gauge");session.choose(speed);panel.bind(session)
        val gauge=descendants(panel).filterIsInstance<TextView>().map {it.text.toString()}
        assertNotEquals(lyrics,gauge)
        assertTrue(gauge.any {it.contains("指针")})
    }
}
