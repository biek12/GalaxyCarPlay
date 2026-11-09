package com.galaxy.auto.client.vehicle

import android.graphics.*
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VehicleAccountActivityTest {
    private fun texts(v:View):List<String> = when(v){is TextView->listOf(v.text.toString());is ViewGroup->(0 until v.childCount).flatMap{texts(v.getChildAt(it))};else->emptyList()}
    @Test fun boundAccountHasPermanentStatusAndUnbindEntry(){
        val controller=Robolectric.buildActivity(VehicleAccountActivity::class.java).create()
        val activity=controller.get()
        val value=JSONObject().put("bound",true).put("enabled",true).put("owner",JSONObject().put("displayName","测试账号").put("phoneMasked","138****0000"))
            .put("basicInfo",JSONObject().put("model","L6").put("modelSource","离线样例").put("androidVersion","11").put("serviceVersion","test"))
        show(activity,value)
        val all=texts(activity.window.decorView)
        Assert.assertTrue(all.any{it.contains("已绑定 · 长期有效")})
        Assert.assertTrue(all.any{it=="取消绑定"})
        capture(activity,"account-bound-offline.png")
        controller.destroy()
    }
    @Test fun pendingConfirmationAndQrAreVisibleTogether(){
        val controller=Robolectric.buildActivity(VehicleAccountActivity::class.java).create()
        val activity=controller.get()
        val code="0123456789abcdef"
        val value=JSONObject().put("bound",false).put("enabled",true).put("code",code).put("expiresAt",System.currentTimeMillis()+300000)
            .put("scanUrl","https://example.test/galaxy/app/vehicles#bind=$code")
            .put("bindings",org.json.JSONArray().put(JSONObject().put("bindingId","test").put("displayName","测试账号").put("phoneMasked","138****0000").put("expiresAt",System.currentTimeMillis()+300000)))
        show(activity,value)
        val all=texts(activity.window.decorView)
        Assert.assertTrue(all.contains(code));Assert.assertTrue(all.contains("确认是我的账号"));Assert.assertTrue(all.contains("拒绝此请求"))
        capture(activity,"account-code-offline.png")
        controller.destroy()
    }
    private fun show(activity:VehicleAccountActivity,value:JSONObject){
        activity.javaClass.getDeclaredField("value").apply{isAccessible=true}.set(activity,value)
        activity.javaClass.getDeclaredMethod("render").apply{isAccessible=true}.invoke(activity)
    }
    private fun capture(activity:VehicleAccountActivity,name:String){
        val dir=System.getenv("GALAXY_VERIFY_CACHE")?.let{File(it,"vehicle-web-ui")}?:return
        dir.mkdirs();val view=activity.window.decorView
        view.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1800,View.MeasureSpec.EXACTLY));view.layout(0,0,1080,1800)
        val bitmap=Bitmap.createBitmap(1080,1800,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap));File(dir,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
}
