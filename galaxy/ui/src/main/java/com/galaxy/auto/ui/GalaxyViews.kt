package com.galaxy.auto.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/** 只负责呈现与事件；连接、权限和业务设置由调用方持有。 */
object GalaxyViews {
    fun dp(context: Context,value: Int)=(value*context.resources.displayMetrics.density).toInt()
    fun text(context: Context,value: String,role: GalaxyTypography.Role=GalaxyTypography.Role.LABEL)=GalaxyTypography.text(context,value,role)
    fun text(context: Context,value: String,secondary: Boolean)=TextView(context).apply {
        text=value;textSize=if(secondary)16f else 20f
        typeface=Typeface.create(if(secondary)"sans-serif" else "sans-serif-medium",Typeface.NORMAL)
        includeFontPadding=false;setLineSpacing(dp(context,3).toFloat(),1f)
        GalaxyStyle.text(this,if(secondary)R.color.galaxy_ui_muted else R.color.galaxy_ui_text)
    }
    fun sectionTitle(context: Context,value: String)=text(context,value,GalaxyTypography.Role.SECTION_TITLE)
    fun group(context: Context)=LinearLayout(context).apply {
        orientation=LinearLayout.VERTICAL;GalaxyStyle.surface(this)
        val padding=dp(context,20);setPadding(padding,padding,padding,padding)
    }
    fun button(context: Context,label: String,primary: Boolean=false,click: ()->Unit)=GalaxyActionButton(context).apply {
        text=label;GalaxyStyle.button(this,primary,withIcon=false)
        setOnClickListener {if(isEnabled)click()}
    }
    fun textButton(context: Context,label: String,click: ()->Unit)=GalaxyActionButton(context).apply {
        text=label;isAllCaps=false;stateListAnimator=null;backgroundTintList=null
        GalaxyTypography.apply(this,18,26,false)
        minHeight=dp(context,64);minimumHeight=minHeight;gravity=Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(0,dp(context,8),dp(context,12),dp(context,8))
        GalaxyStyle.bind(this){
            setTextColor(context.getColor(if(isEnabled)R.color.galaxy_ui_accent else R.color.galaxy_ui_disabled))
            background=RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.galaxy_ui_ripple)),null,GalaxyStyle.rounded(context,Color.WHITE,4))
        }
        setOnClickListener{if(isEnabled)click()}
    }
    fun navigationStyle(button: Button)=GalaxyStyle.bind(button){
        val context=button.context
        button.setTextColor(context.getColor(if(button.isSelected)R.color.galaxy_ui_accent else R.color.galaxy_ui_text))
        button.background=RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.galaxy_ui_ripple)),
            GalaxyStyle.rounded(context,if(button.isSelected)context.getColor(R.color.galaxy_ui_selected)else Color.TRANSPARENT,4),null)
    }
    fun actionButton(context: Context,title: String,primary: Boolean=false,click: ()->Unit)=button(context,title,primary,click)
    fun iconButton(context: Context,icon: Int,label: String,click: ()->Unit)=ImageButton(context).apply {
        setImageResource(icon);contentDescription=label
        setPadding(dp(context,16),dp(context,16),dp(context,16),dp(context,16))
        minimumWidth=dp(context,64);minimumHeight=dp(context,64)
        GalaxyStyle.bind(this){imageTintList=ColorStateList.valueOf(context.getColor(R.color.galaxy_ui_text))
            background=RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.galaxy_ui_ripple)),null,GalaxyStyle.rounded(context,Color.WHITE,8))}
        setOnClickListener{if(isEnabled)click()}
    }
    fun switchRow(context: Context,title: String,description: String,checked: Boolean,save: (Boolean)->Unit)=
        GalaxySettingRow(context,title,description).apply {
            val row=this
            val control=Switch(context).apply {
                contentDescription=title;isChecked=checked;minHeight=dp(context,64);GalaxySwitchStyle.apply(this)
                setOnCheckedChangeListener{_,value->if(!row.updatingSwitch)save(value)}
            }
            setAccessory(control,slotWidth=dp(context,82),leading=true)
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener{if(isEnabled&&control.isEnabled)control.performClick()}
            isFocusable=false;GalaxyStyle.rowFeedback(this)
        }
}
