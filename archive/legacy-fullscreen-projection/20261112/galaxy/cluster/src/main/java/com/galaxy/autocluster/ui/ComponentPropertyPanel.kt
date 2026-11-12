package com.galaxy.autocluster.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.galaxy.autocluster.theme.*

/** 由组件自己的属性契约生成分节面板；数据绑定共用，视觉属性按日夜覆盖/继承。 */
internal class ComponentPropertyPanel(
    context: Context,private val changed: ()->Unit,
    private val fields: ()->List<Pair<String,String>>,
    private val assetRequested: (String,PropertySpec)->Unit,
): LinearLayout(context) {
    private val ui=ClusterUi(context)
    private val expanded=mutableSetOf<String>()
    private var componentType=""
    private val committers=mutableListOf<()->Boolean>()
    private data class Inheritance(val id: String,val key: String)
    init { orientation=VERTICAL;setPadding(ui.dp(20),ui.dp(18),ui.dp(20),ui.dp(22));background=ui.rounded(ui.surface) }

    fun bind(session: EditorSession?){
        removeAllViews()
        committers.clear()
        val node=session?.selection?.singleOrNull()?.let(session::node)
        if(session==null||node==null){
            addView(ui.label(if((session?.selection?.size?:0)>1)"已选择 ${session!!.selection.size} 个组件"else"选择一个组件",22f))
            addView(ui.note("在画布或图层中选择组件，查看它自己的数据、样式和动画设置。"));return
        }
        val spec=ComponentProperties.byType.getValue(node.type)
        if(componentType!=node.type){componentType=node.type;expanded.clear();expanded.addAll(listOf("数据","文字","盘面"))}
        addView(ui.label(spec.title,22f))
        addView(ui.note("${if(session.previewNight)"夜间"else"日间"}样式 · 未覆盖的属性继承主题"))
        geometry(session,node)
        val state=ui.row()
        for((title,value,apply)in listOf(
            Triple("显示组件",node.visible,{ next: Boolean->session.updateNode(node.id){it.copy(visible=next)} }),
            Triple("锁定拖动",node.locked,{ next: Boolean->session.updateNode(node.id){it.copy(locked=next)} })
        )){
            val control=Switch(context).apply { text=title;isChecked=value;textSize=16f;setTextColor(ui.foreground);minimumHeight=ui.dp(56)
                setOnCheckedChangeListener { _,next->tryChange(this){apply(next);changed()} } }
            state.addView(control,LayoutParams(0,-2,1f))
        }
        addView(state)
        for((section,properties)in spec.properties.groupBy { it.section }){
            val body=ui.column()
            val button=ui.button((if(section in expanded)"⌄  "else"›  ")+section){
                if(section in expanded)expanded.remove(section)else expanded.add(section)
                body.visibility=if(section in expanded)View.VISIBLE else View.GONE
                (getChildAt(indexOfChild(body)-1)as? Button)?.text=(if(section in expanded)"⌄  "else"›  ")+section
            }
            button.gravity=android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            addView(button,LayoutParams(-1,-2).apply { topMargin=ui.dp(12) })
            body.visibility=if(section in expanded)View.VISIBLE else View.GONE
            properties.forEach { property->propertyRow(body,session,node,property) }
            addView(body)
        }
    }

    private fun geometry(session: EditorSession,node: SceneNode){
        val name=ui.input(node.name)
        addView(name,LayoutParams(-1,-2).apply { bottomMargin=ui.dp(12) })
        commitOnExit(name){value->session.updateNode(node.id){it.copy(name=value.trim())};changed()}
        val row=ui.row()
        for((key,title,value)in listOf(Triple("x","X",node.rect.x),Triple("y","Y",node.rect.y),Triple("width","宽",node.rect.width),Triple("height","高",node.rect.height))){
            val column=ui.column()
            column.addView(ui.label(title,14f,true))
            val input=ui.input(number(value)).apply { inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED }
            column.addView(input,LayoutParams(-1,-2).apply { topMargin=ui.dp(8) })
            row.addView(column,LayoutParams(0,-2,1f).apply { if(key!="height")marginEnd=ui.dp(10) })
            commitOnExit(input){raw->
                val parsed=raw.toFloat();require(parsed.isFinite())
                val current=session.node(node.id)?:return@commitOnExit
                when(key){
                    "x"->session.updateNode(node.id){it.copy(rect=it.rect.copy(x=parsed))}
                    "y"->session.updateNode(node.id){it.copy(rect=it.rect.copy(y=parsed))}
                    "width"->session.updateNode(node.id){SceneTree.resize(it,it.rect.copy(width=parsed))}
                    else->session.updateNode(node.id){SceneTree.resize(it,it.rect.copy(height=parsed))}
                }
                changed()
            }
        }
        addView(row)
    }

    private fun resolved(session: EditorSession,node: SceneNode): Map<String,String>{
        val styles=session.content.scene.styles
        val mode=if(session.previewNight&&!session.content.scene.document.shareDayNight)styles.night else styles.day
        val own=if(session.previewNight&&!session.content.scene.document.shareDayNight)node.night else node.day
        return ComponentProperties.defaults(node.type)+styles.shared+mode+node.properties+own
    }
    private fun propertyRow(parent: LinearLayout,session: EditorSession,node: SceneNode,spec: PropertySpec){
        val row=ui.column().apply { setPadding(0,ui.dp(12),0,ui.dp(6)) }
        val title=ui.row()
        title.addView(ui.label(spec.label,16f),LayoutParams(0,-2,1f))
        if(spec.styleable){
            val own=if(session.previewNight&&!session.content.scene.document.shareDayNight)node.night else node.day
            title.addView(ui.button(if(spec.key in own)"恢复继承"else"继承"){tryChange(title){session.property(node.id,spec,null);changed();bind(session)}}.apply {
                tag=Inheritance(node.id,spec.key)
                isEnabled=spec.key in own;textSize=13f;minimumHeight=ui.dp(44);minHeight=ui.dp(44)
            },LayoutParams(ui.dp(100),-2))
        }
        row.addView(title)
        val value=resolved(session,node).getValue(spec.key)
        when(spec.kind){
            PropertyKind.BOOLEAN->{
                val control=Switch(context).apply { isChecked=value=="true";minimumHeight=ui.dp(52);text=if(isChecked)"开启"else"关闭";setTextColor(ui.foreground)
                    setOnCheckedChangeListener { button,enabled->tryChange(button){session.property(node.id,spec,enabled.toString());text=if(enabled)"开启"else"关闭";changed()} } }
                row.addView(control)
            }
            PropertyKind.CHOICE->{
                val spinner=Spinner(context)
                spinner.adapter=ArrayAdapter(context,android.R.layout.simple_spinner_dropdown_item,spec.choices.map { it.second })
                spinner.setSelection(spec.choices.indexOfFirst { it.first==value }.coerceAtLeast(0),false)
                spinner.onItemSelectedListener=object: AdapterView.OnItemSelectedListener{
                    override fun onNothingSelected(parent: AdapterView<*>?)=Unit
                    override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,id: Long){
                        val next=spec.choices[position].first
                        if(next==resolved(session,session.node(node.id)?:node)[spec.key])return
                        if(spec.key=="font"&&next=="asset"&&resolved(session,session.node(node.id)?:node)["fontAsset"].isNullOrEmpty()){
                            assetRequested(node.id,ComponentProperties.byType.getValue(node.type).properties.first { it.key=="fontAsset" });return
                        }
                        tryChange(spinner){session.property(node.id,spec,next);changed()}
                    }
                }
                row.addView(spinner,LayoutParams(-1,ui.dp(52)))
            }
            PropertyKind.SOURCE->{
                val button=ui.button(if(value.isEmpty())"不显示"else fields().firstOrNull { it.first==value }?.second?:value){
                    val options=listOf("" to "不显示")+fields()
                    AlertDialog.Builder(context).setTitle(spec.label).setItems(options.map { it.second }.toTypedArray()){_,which->
                        tryChange(row){session.property(node.id,spec,options[which].first);changed();bind(session)}
                    }.show()
                }
                row.addView(button,LayoutParams(-1,-2))
            }
            PropertyKind.ASSET->row.addView(ui.button(value.substringAfterLast('/').ifEmpty { "选择本地文件" }){assetRequested(node.id,spec)},LayoutParams(-1,-2))
            PropertyKind.COLOR->{
                val input=ui.input(value)
                row.addView(input,LayoutParams(-1,-2))
                commitOnExit(input){raw->session.property(node.id,spec,raw.trim());changed()}
                val tokens=ui.row()
                for((label,color)in listOf("主题" to "@accent","浅色" to "#F2ECDD","蓝" to "#42BAEE","绿" to "#40BE97","透明" to "#00000000"))
                    tokens.addView(ui.button(label){tryChange(input){session.property(node.id,spec,color);input.setText(color);changed()}},LayoutParams(0,-2,1f).apply { marginEnd=ui.dp(4) })
                row.addView(tokens,LayoutParams(-1,-2).apply { topMargin=ui.dp(8) })
            }
            else->{
                val input=ui.input(value)
                if(spec.kind==PropertyKind.NUMBER)input.inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                row.addView(input,LayoutParams(-1,-2))
                commitOnExit(input){raw->session.property(node.id,spec,raw);changed()}
                if(spec.kind==PropertyKind.NUMBER)row.addView(ui.note("范围 ${number(spec.min)} – ${number(spec.max)}"))
            }
        }
        parent.addView(row)
    }
    private fun commitOnExit(input: EditText,commit: (String)->Unit){
        var last=input.text.toString()
        fun apply(): Boolean {val value=input.text.toString();if(value==last)return true;return try {commit(value);last=value;input.error=null;true}catch(error: Exception){input.error=message(error);false}}
        committers.add(::apply)
        input.imeOptions=EditorInfo.IME_ACTION_DONE
        input.setOnFocusChangeListener { _,focused->if(!focused)apply() }
        input.setOnEditorActionListener { _,action,_->if(action==EditorInfo.IME_ACTION_DONE){apply();true}else false }
    }
    fun commitAll()=committers.toList().map { it() }.all { it }
    fun refreshInheritance(session: EditorSession){
        fun visit(view: View){
            val binding=view.tag as? Inheritance
            if(binding!=null&&view is Button){
                val node=session.node(binding.id)
                val own=if(session.previewNight&&!session.content.scene.document.shareDayNight)node?.night else node?.day
                view.isEnabled=own?.containsKey(binding.key)==true
                view.text=if(view.isEnabled)"恢复继承"else"继承"
            }
            if(view is android.view.ViewGroup)for(index in 0 until view.childCount)visit(view.getChildAt(index))
        }
        visit(this)
    }
    private fun tryChange(view: View,action: ()->Unit){try{action()}catch(error: Exception){Toast.makeText(context,message(error),Toast.LENGTH_LONG).show()}}
    private fun message(error: Exception)=when{
        error.message?.contains("range")==true->"最小值必须小于最大值"
        error.message?.contains("bounds")==true->"位置或尺寸超出父级范围"
        error.message?.contains("number")==true->"数值超出此属性的允许范围"
        error.message?.contains("color")==true->"请输入主题色引用、#RRGGBB 或 #AARRGGBB"
        else->"设置无效，已保留原值"
    }
    private fun number(value: Float)=if(value%1f==0f)value.toLong().toString()else value.toString()
}
