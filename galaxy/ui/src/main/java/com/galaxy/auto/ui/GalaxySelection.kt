package com.galaxy.auto.ui

import android.app.AlertDialog
import android.content.Context
import android.widget.LinearLayout

/** 待选状态只属于本次弹窗；关闭/取消不提交，确认只调用一次。 */
object GalaxySelection {
    fun select(context: Context,title: String,options: List<String>,current: Int,applyLabel: String,
        onPreview: (Int)->Unit={},onDismiss: ()->Unit={},optionEnabled: ((Int)->Boolean)?=null,
        builder: GalaxyDialogs.Builder?=null,onApply: (Int)->Unit): AlertDialog {
        require(options.isNotEmpty()&&current in options.indices)
        var pending=current;var committed=false
        val dialog=(builder?:GalaxyDialogs.builder(context)).setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(),current,optionEnabled){dialog,index->
                if(optionEnabled?.invoke(index)==false)return@setSingleChoiceItems
                pending=index;onPreview(index)
                (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=pending!=current
            }.setNegativeButton(android.R.string.cancel,null).setPositiveButton(applyLabel,null).create()
        dialog.setOnDismissListener{onDismiss()}
        dialog.setOnShowListener{
            val apply=dialog.getButton(AlertDialog.BUTTON_POSITIVE);apply.isEnabled=false
            apply.setOnClickListener{
                if(pending==current||committed||optionEnabled?.invoke(pending)==false)return@setOnClickListener
                committed=true;dialog.dismiss();onApply(pending)
            }
        }
        dialog.show();return dialog
    }

    /** App导航/分段选择只发布选中索引，内容与返回行为由宿主决定。 */
    fun segments(context: Context,labels: List<String>,selected: Int,onSelect: (Int)->Unit)=LinearLayout(context).apply {
        require(labels.isNotEmpty()&&selected in labels.indices)
        orientation=LinearLayout.HORIZONTAL
        labels.forEachIndexed{index,label->
            addView(GalaxyViews.button(context,label,index==selected){onSelect(index)},LinearLayout.LayoutParams(0,-2,1f))
        }
    }
}
