package com.galaxy.autocluster.theme

import java.util.UUID

internal data class EditorContent(val scene: ThemeScene,val assets: Map<String,ByteArray>,val name: String="")

/** 纯编辑模型；手势、属性、层序和分组操作不访问服务、磁盘或真实车辆。 */
internal class EditorSession(theme: ThemePackage,val builtIn: Boolean,isNew: Boolean=false) {
    var metadata=theme.metadata;private set
    var expectedRevision: Long?=if(builtIn||isNew)null else metadata.revision;private set
    private val history=EditHistory(EditorContent(SceneXml.read(theme),theme.copyEntries().filterKeys { it !in setOf("manifest.xml","scene.xml","styles.xml","preview.png") },if(builtIn)"${metadata.name} · 自定义"else metadata.name),{ it })
    val selection=linkedSetOf<String>()
    var previewNight=true
    val content get()=history.value()
    val dirty get()=history.dirty()
    val name get()=content.name.ifEmpty { metadata.name }
    val canUndo get()=history.canUndo()
    val canRedo get()=history.canRedo()
    fun node(id: String)=SceneTree.find(content.scene.document.nodes,id)
    fun choose(id: String?,add: Boolean=false){if(!add)selection.clear();if(id!=null&&node(id)!=null){if(add&&id in selection)selection.remove(id)else selection.add(id)}}
    fun change(value: EditorContent){SceneValidator.validate(value.scene,value.assets.keys);history.change(value)}
    fun rename(value: String){metadata.copy(name=value);change(content.copy(name=value))}
    fun undo(){history.undo();trimSelection()}
    fun redo(){history.redo();trimSelection()}
    fun beginGesture()=history.beginGesture()
    fun finishGesture()=history.finishGesture()
    fun cancelGesture()=history.cancelGesture()

    fun moveSelection(dx: Float,dy: Float,base: EditorContent=content,snap: Boolean=false){
        fun move(nodes: List<SceneNode>,width: Float,height: Float): List<SceneNode> = nodes.map { node ->
            if(node.id in selection){
                val r=node.rect
                fun snapped(value: Float)=if(snap)kotlin.math.round(value/8)*8 else value
                node.copy(rect=SceneTree.constrain(r.copy(x=snapped(r.x+dx),y=snapped(r.y+dy)),width,height))
            }else node.copy(children=move(node.children,node.rect.width,node.rect.height))
        }
        history.change(base.copy(scene=base.scene.copy(document=base.scene.document.copy(nodes=move(base.scene.document.nodes,1920f,532f)))))
    }
    fun resize(id: String,width: Float,height: Float,base: EditorContent=content){
        fun edit(nodes: List<SceneNode>,parentWidth: Float,parentHeight: Float): List<SceneNode> = nodes.map {
            if(it.id==id){
                // 组合缩小时，最小子节点仍至少16设计像素，不能生成无法保存的草稿。
                val descendants=SceneTree.flatten(listOf(it))
                val minimumWidth=it.rect.width*descendants.maxOf { child->16f/child.rect.width }
                val minimumHeight=it.rect.height*descendants.maxOf { child->16f/child.rect.height }
                SceneTree.resize(it,SceneTree.constrain(it.rect.copy(width=maxOf(width,minimumWidth),height=maxOf(height,minimumHeight)),parentWidth,parentHeight))
            }
            else it.copy(children=edit(it.children,it.rect.width,it.rect.height))
        }
        history.change(base.copy(scene=base.scene.copy(document=base.scene.document.copy(nodes=edit(base.scene.document.nodes,1920f,532f)))))
    }
    fun updateNode(id: String,transform: (SceneNode)->SceneNode){val c=content;change(c.copy(scene=c.scene.copy(document=c.scene.document.copy(nodes=SceneTree.update(c.scene.document.nodes,id,transform))))) }
    fun property(id: String,spec: PropertySpec,value: String?){
        updateNode(id){node->
            if(!spec.styleable)node.copy(properties=node.properties+(spec.key to requireNotNull(value)))
            else if(previewNight&&!content.scene.document.shareDayNight)node.copy(night=if(value==null)node.night-spec.key else node.night+(spec.key to value))
            else node.copy(day=if(value==null)node.day-spec.key else node.day+(spec.key to value))
        }
    }
    fun add(type: String,properties: Map<String,String> = emptyMap()): String {
        val existing=if(type=="map")SceneTree.flatten(content.scene.document.nodes).firstOrNull { it.type=="map" }else null
        if(existing!=null){choose(existing.id);return existing.id}
        val spec=ComponentProperties.byType.getValue(type);val id="node-${UUID.randomUUID()}"
        val rect=SceneRect((1920-spec.width)/2,(532-spec.height)/2,spec.width,spec.height)
        val node=if(type=="pages")CompositeComponents.pages(id,rect,properties)else SceneNode(id,type,spec.title,rect,properties)
        val c=content;change(c.copy(scene=c.scene.copy(document=c.scene.document.copy(nodes=c.scene.document.nodes+node))));choose(id);return id
    }
    fun deleteSelection(){val c=content;change(c.copy(scene=c.scene.copy(document=c.scene.document.copy(nodes=SceneTree.remove(c.scene.document.nodes,selection)))));selection.clear()}
    fun duplicateSelection(){
        val c=content;val added=mutableListOf<SceneNode>();val positions=SceneTree.worldRects(c.scene.document.nodes)
        fun copy(node: SceneNode): SceneNode=node.copy(id="node-${UUID.randomUUID()}",children=node.children.map(::copy))
        for(id in selection){val old=node(id)?:continue;if(SceneTree.flatten(listOf(old)).any { it.type=="map" })continue
            added.add(copy(old).copy(rect=SceneTree.constrain(positions.getValue(id).let { it.copy(x=it.x+24,y=it.y+24) }))) }
        if(added.isEmpty())return
        change(c.copy(scene=c.scene.copy(document=c.scene.document.copy(nodes=c.scene.document.nodes+added))));selection.clear();selection.addAll(added.map { it.id })
    }
    fun group(){
        val c=content;val all=SceneTree.flatten(c.scene.document.nodes);val selected=all.filter { it.id in selection }
        if(selected.size<2)return
        require(selected.none { candidate->SceneTree.flatten(candidate.children).any { it.id in selection } }) { "group_select_same_level" }
        val world=SceneTree.worldRects(c.scene.document.nodes)
        val left=selected.minOf { world.getValue(it.id).x };val top=selected.minOf { world.getValue(it.id).y }
        val right=selected.maxOf { world.getValue(it.id).let { r->r.x+r.width } };val bottom=selected.maxOf { world.getValue(it.id).let { r->r.y+r.height } }
        val id="group-${UUID.randomUUID()}"
        val group=SceneNode(id,"card","组合",SceneRect(left,top,right-left,bottom-top),mapOf("background" to "#00000000","clip" to "false"),
            children=selected.map { it.copy(rect=world.getValue(it.id).let { r->r.copy(x=r.x-left,y=r.y-top) }) })
        val nodes=SceneTree.remove(c.scene.document.nodes,selection)+group
        change(c.copy(scene=c.scene.copy(document=c.scene.document.copy(nodes=nodes))));choose(id)
    }
    fun ungroup(){
        val c=content;val id=selection.singleOrNull()?:return;val group=node(id)?:return
        if(group.children.isEmpty())return
        val world=SceneTree.worldRects(c.scene.document.nodes).getValue(id)
        val children=group.children.map { it.copy(rect=it.rect.copy(x=it.rect.x+world.x,y=it.rect.y+world.y)) }
        change(c.copy(scene=c.scene.copy(document=c.scene.document.copy(nodes=SceneTree.remove(c.scene.document.nodes,setOf(id))+children))))
        selection.clear();selection.addAll(children.map { it.id })
    }
    fun reorder(id: String,front: Boolean){
        fun sorted(nodes: List<SceneNode>): List<SceneNode>{val item=nodes.firstOrNull { it.id==id };return if(item!=null){val rest=nodes.filterNot { it.id==id };if(front)rest+item else listOf(item)+rest}else nodes.map { it.copy(children=sorted(it.children)) }}
        val c=content;change(c.copy(scene=c.scene.copy(document=c.scene.document.copy(nodes=sorted(c.scene.document.nodes)))))
    }
    fun align(horizontal: String){
        val c=content
        fun aligned(nodes: List<SceneNode>,width: Float,height: Float): List<SceneNode> = nodes.map { node->
            if(node.id in selection)node.copy(rect=when(horizontal){"left"->node.rect.copy(x=0f);"right"->node.rect.copy(x=width-node.rect.width);"top"->node.rect.copy(y=0f);"bottom"->node.rect.copy(y=height-node.rect.height);"vertical"->node.rect.copy(y=(height-node.rect.height)/2);else->node.rect.copy(x=(width-node.rect.width)/2)})
            else node.copy(children=aligned(node.children,node.rect.width,node.rect.height))
        }
        change(c.copy(scene=c.scene.copy(document=c.scene.document.copy(nodes=aligned(c.scene.document.nodes,1920f,532f)))))
    }
    fun packageForSave(value: EditorContent=content,name: String=value.name.ifEmpty { metadata.name }): ThemePackage {
        val own=if(expectedRevision==null)ThemeMetadata("theme-${UUID.randomUUID()}",name,1)else metadata.copy(name=name)
        return SceneXml.write(own,value.scene,value.assets)
    }
    fun saved(theme: ThemePackage,snapshot: EditorContent){metadata=theme.metadata;expectedRevision=theme.metadata.revision;history.markSaved(snapshot)}
    private fun trimSelection(){selection.retainAll(SceneTree.flatten(content.scene.document.nodes).map { it.id }.toSet())}
}
