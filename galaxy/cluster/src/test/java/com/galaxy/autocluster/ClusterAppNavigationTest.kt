package com.galaxy.autocluster

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Switch
import android.content.Intent
import com.galaxy.auto.contract.ClusterDisplayConfiguration
import com.galaxy.autocluster.runtime.ClusterDisplayStore
import com.galaxy.auto.ui.GalaxySettingRow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 真实Activity离线布局截图；不是模拟器、车机截图或音乐数据验收。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],qualifiers="w571dp-h1028dp-280dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClusterAppNavigationTest {
    @Test fun appSeparatesHomeMusicAndBackgroundAndKeepsServiceSettingsOutside(){
        org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).declareComponentUnbindable(
            android.content.ComponentName(com.galaxy.auto.contract.AutoContract.SERVICE_PACKAGE,com.galaxy.auto.contract.AutoContract.SERVICE_CLASS))
        val controller=Robolectric.buildActivity(ClusterActivity::class.java).setup()
        try{
            val activity=controller.get();val root=activity.window.decorView
            fun row(name:String)=walk(root).filterIsInstance<GalaxySettingRow>().single{it.titleView.text.toString()==name}
            capture(root,"overview")
            assertTrue(walk(root).filterIsInstance<TextView>().any{it.text.toString()=="我的仪表"})
            assertFalse(walk(root).any{it is com.galaxy.autocluster.ui.InstrumentMusicPreview})
            row("原厂增强").performClick();capture(root,"oem-enhancement")
            row("音乐卡片").performClick();capture(root,"music-preview")
            assertEquals(1,walk(root).count{it is com.galaxy.autocluster.ui.InstrumentMusicPreview})
            assertFalse(walk(root).filterIsInstance<TextView>().any{it.text.contains("Wi-Fi")||it.text.toString()=="本机 ADB"})
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun runtimeUsesRootEnabledAndRequiresAnActualReceipt(){
        org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).declareComponentUnbindable(
            android.content.ComponentName(com.galaxy.auto.contract.AutoContract.SERVICE_PACKAGE,com.galaxy.auto.contract.AutoContract.SERVICE_CLASS))
        val store=ClusterDisplayStore(org.robolectric.RuntimeEnvironment.getApplication())
        val configuration=ClusterDisplayConfiguration(mode=ClusterDisplayConfiguration.OEM_ELEMENTS,music=true)
        store.submitElements(configuration)
        val controller=Robolectric.buildActivity(ClusterActivity::class.java).setup()
        try{
            val activity=controller.get()
            val connected=ClusterActivity::class.java.getDeclaredField("connected").apply{isAccessible=true}
            val snapshot=ClusterActivity::class.java.getDeclaredField("snapshot").apply{isAccessible=true}
            val status=ClusterActivity::class.java.getDeclaredMethod("elementStatus",String::class.java).apply{isAccessible=true}
            connected.setBoolean(activity,true)
            val bundle=android.os.Bundle().apply{
                putBundle("features",android.os.Bundle().apply{putBoolean("rootEnabled",true)})
                putBundle("clusterElements",configuration.bundle())
            }
            snapshot.set(activity,bundle)
            assertTrue((status.invoke(activity,"") as String).contains("等待仪表确认"))
            bundle.putString("clusterElementStatus","active")
            assertEquals("原厂增强运行中",status.invoke(activity,""))
            bundle.getBundle("features")!!.putBoolean("rootEnabled",false)
            assertTrue((status.invoke(activity,"") as String).contains("基础能力已关闭"))
        }finally{controller.pause().stop().destroy()}
    }

    @Test fun stagedImageIsNotAnEnabledSwitchAndUserControlsSubmitWithoutAnotherApply(){
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        val shadow=org.robolectric.Shadows.shadowOf(app)
        shadow.declareComponentUnbindable(android.content.ComponentName(com.galaxy.auto.contract.AutoContract.SERVICE_PACKAGE,com.galaxy.auto.contract.AutoContract.SERVICE_CLASS))
        val store=ClusterDisplayStore(app)
        store.saveDraft(ClusterDisplayConfiguration(ClusterDisplayConfiguration.OEM_ELEMENTS,"image",true,"aurora.png"))
        val controller=Robolectric.buildActivity(ClusterActivity::class.java).setup()
        try{
            val activity=controller.get()
            fun views()=walk(activity.window.decorView)
            fun row(name:String)=views().filterIsInstance<GalaxySettingRow>().single{it.titleView.text.toString()==name}
            fun control()=views().filterIsInstance<Switch>().single()
            fun drain():List<Intent> = generateSequence{shadow.nextStartedService}.toList()
            row("原厂增强").performClick();row("音乐卡片").performClick()
            assertFalse(control().isChecked);assertFalse(store.enabled);drain()
            control().performClick()
            assertTrue(store.enabled);assertTrue(store.elements().music)
            assertEquals("original",store.elements().background) // music does not enable a staged image
            assertTrue(control().isChecked)
            assertTrue(drain().any{it.component?.className==ClusterRenderService::class.java.name&&it.action=="apply"})
            activity.onBackPressed();row("背景").performClick();row("本地图片").performClick()
            assertEquals("image",store.elements().background);assertEquals("aurora.png",store.elements().image)
            assertTrue(store.elements().music)
            assertTrue(drain().any{it.component?.className==ClusterRenderService::class.java.name&&it.action=="apply"})
            activity.onBackPressed();row("音乐卡片").performClick();control().performClick()
            assertFalse(store.elements().music);assertEquals("image",store.elements().background)
            assertTrue(store.enabled);drain()
            activity.onBackPressed();row("背景").performClick();row("原厂背景").performClick()
            assertFalse(store.enabled)
            assertTrue(drain().any{it.component?.className==ClusterRenderService::class.java.name&&it.action=="stop"})
            activity.onBackPressed();row("音乐卡片").performClick();assertFalse(control().isChecked)
        }finally{controller.pause().stop().destroy()}
        val recreated=Robolectric.buildActivity(ClusterActivity::class.java).setup()
        try{
            assertFalse(ClusterDisplayStore(recreated.get()).enabled)
            val starts=generateSequence{shadow.nextStartedService}.toList()
            assertFalse(starts.any{it.component?.className==ClusterRenderService::class.java.name})
        }finally{recreated.pause().stop().destroy()}
    }

    @Test fun changedPlanCannotDisplaySuccessFromPreviousImageReceipt(){
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app).declareComponentUnbindable(android.content.ComponentName(com.galaxy.auto.contract.AutoContract.SERVICE_PACKAGE,com.galaxy.auto.contract.AutoContract.SERVICE_CLASS))
        val store=ClusterDisplayStore(app)
        val current=ClusterDisplayConfiguration(ClusterDisplayConfiguration.OEM_ELEMENTS,"image",true,"new.png")
        store.submitElements(current)
        val controller=Robolectric.buildActivity(ClusterActivity::class.java).setup()
        try{
            val activity=controller.get()
            ClusterActivity::class.java.getDeclaredField("connected").apply{isAccessible=true}.setBoolean(activity,true)
            val snapshot=android.os.Bundle().apply{
                putString("clusterElementStatus","active")
                putBundle("clusterElements",current.copy(image="old.png").bundle())
                putBundle("clusterElementRuntime",android.os.Bundle().apply{putBoolean("backgroundMounted",true)})
            }
            ClusterActivity::class.java.getDeclaredField("snapshot").apply{isAccessible=true}.set(activity,snapshot)
            val status=ClusterActivity::class.java.getDeclaredMethod("elementStatus",String::class.java).apply{isAccessible=true}
            assertEquals("正在提交仪表配置",status.invoke(activity,"background"))
            snapshot.putBundle("clusterElements",current.bundle())
            assertEquals("仪表背景已替换",status.invoke(activity,"background"))
            snapshot.putString("clusterConfigurationStatus","failed")
            assertTrue((status.invoke(activity,"background") as String).contains("未能应用"))
        }finally{controller.pause().stop().destroy()}
    }
    private fun walk(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{walk(view.getChildAt(it))}else emptyList()
    private fun capture(root:View,name:String){
        root.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1800,View.MeasureSpec.EXACTLY))
        root.layout(0,0,1000,1800)
        val image=Bitmap.createBitmap(1000,1800,Bitmap.Config.ARGB_8888)
        try{root.draw(Canvas(image));val directory=File("build/outputs/refactor-ui").apply{mkdirs()};File(directory,"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}
    }
}
