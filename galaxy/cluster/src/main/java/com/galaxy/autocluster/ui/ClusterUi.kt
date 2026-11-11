package com.galaxy.autocluster.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.*

/** 沿旧GalaxyPlay的Flyme日夜色板与细圆角组织，自有App不依赖CarPlay宿主UI。 */
internal class ClusterUi(val context: Context) {
    private val night get()=context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
    val background get()=Color.parseColor(if(night)"#20272E"else"#D3DBE5")
    val surface get()=Color.parseColor(if(night)"#252A30"else"#FFFFFF")
    val control get()=Color.parseColor(if(night)"#15191F"else"#E5EBF2")
    val foreground get()=Color.parseColor(if(night)"#E5FFFFFF"else"#E5000000")
    val muted get()=Color.parseColor(if(night)"#99FFFFFF"else"#99000000")
    val accent get()=Color.parseColor(if(night)"#0088FF"else"#1368FB")
    fun dp(value: Int)=(value*context.resources.displayMetrics.density+.5f).toInt()
    fun label(value: String,size: Float=18f,secondary: Boolean=false)=TextView(context).apply {
        text=value;textSize=size;setTextColor(if(secondary)muted else this@ClusterUi.foreground);includeFontPadding=false
        if(size>=22)typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    }
    fun rounded(color: Int,radius: Int=4)=GradientDrawable().apply { setColor(color);cornerRadius=dp(radius).toFloat() }
    fun button(value: String,primary: Boolean=false,action: ()->Unit)=com.galaxy.auto.ui.GalaxyViews.button(context,value,primary,action) /* extracted */
    fun column(padding: Int=0)=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(padding),dp(padding),dp(padding),dp(padding)) }
    fun row()=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
    fun input(value: String)=EditText(context).apply {
        setText(value);textSize=18f;setTextColor(this@ClusterUi.foreground);setHintTextColor(muted);background=rounded(control)
        setPadding(dp(14),dp(10),dp(14),dp(10));minimumHeight=dp(52);setSingleLine()
    }
    fun note(value: String)=label(value,14f,true).apply { setPadding(0,dp(12),0,dp(12));setLineSpacing(dp(3).toFloat(),1f) }
}
