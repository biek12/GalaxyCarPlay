package com.galaxy.autocluster.theme

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],manifest=Config.NONE)
class EditorSessionTest {
    private fun editor(): EditorSession {
        val nodes=listOf(
            SceneNode("first","value","速度",SceneRect(20f,20f,300f,200f)),
            SceneNode("second","lyrics","歌词",SceneRect(400f,40f,500f,240f)),
            SceneNode("map","map","地图",SceneRect(950f,100f,700f,300f)))
        return EditorSession(SceneXml.write(ThemeMetadata("custom","自定义",1),ThemeScene(SceneDocument(nodes),SceneStyles())),false)
    }
    @Test fun dragUsesOriginalGeometryAndCommitsOneUndoStepOrCancels() {
        val state=editor();state.choose("first");val base=state.content
        state.beginGesture()
        repeat(100){state.moveSelection(it.toFloat(),15f,base)}
        state.finishGesture()
        assertEquals(119f,state.node("first")!!.rect.x,0f)
        state.undo();assertEquals(base,state.content);assertFalse(state.canUndo)
        state.redo();val moved=state.content
        state.beginGesture();state.moveSelection(9000f,-9000f);state.cancelGesture()
        assertEquals(moved,state.content)
        state.resize("first",9999f,9999f)
        assertEquals(SceneRect(0f,0f,1920f,532f),state.node("first")!!.rect)
    }
    @Test fun groupUngroupAndResizeKeepWorldPositionsAndUniqueIds() {
        val state=editor();val before=SceneTree.worldRects(state.content.scene.document.nodes)
        state.choose("first");state.choose("second",true);state.group()
        val group=state.node(state.selection.single())!!
        val grouped=SceneTree.worldRects(state.content.scene.document.nodes)
        assertEquals(before["first"],grouped["first"]);assertEquals(before["second"],grouped["second"])
        state.resize(group.id,group.rect.width*.8f,group.rect.height*.8f)
        val resized=SceneTree.worldRects(state.content.scene.document.nodes)
        state.ungroup()
        val ungrouped=SceneTree.worldRects(state.content.scene.document.nodes)
        assertEquals(resized["first"],ungrouped["first"]);assertEquals(resized["second"],ungrouped["second"])
        state.duplicateSelection()
        val nodes=SceneTree.flatten(state.content.scene.document.nodes)
        assertEquals(nodes.size,nodes.map {it.id}.toSet().size)
        state.choose("map");state.duplicateSelection();assertEquals(1,nodes.count {it.type=="map"})
        assertEquals("map",state.add("map"))
    }
    @Test fun modeSpecificStylesAndAsynchronousSaveDoNotEraseLaterEdits() {
        val state=editor();val font=ComponentProperties.byType.getValue("lyrics").properties.first {it.key=="fontSize"}
        state.previewNight=false;state.property("second",font,"24")
        state.previewNight=true;state.property("second",font,"32")
        assertEquals("24",state.node("second")!!.day["fontSize"])
        assertEquals("32",state.node("second")!!.night["fontSize"])
        val saved=state.content;val packaged=state.packageForSave()
        state.rename("稍后修改");state.saved(packaged,saved)
        assertTrue(state.dirty);assertEquals("稍后修改",state.name)
        state.undo();assertFalse(state.dirty)
        state.change(state.content.copy(scene=state.content.scene.copy(document=state.content.scene.document.copy(shareDayNight=true))))
        state.property("second",font,"28");assertEquals("28",state.node("second")!!.day["fontSize"])
        state.property("second",font,null);assertFalse(state.node("second")!!.day.containsKey("fontSize"))
    }
    @Test fun invalidEditIsAtomicAndBuiltinSaveCreatesIndependentIdentity() {
        val state=editor();val before=state.content
        try {state.updateNode("first"){it.copy(rect=it.rect.copy(x=-1f))};fail()}catch(_:IllegalArgumentException){}
        assertEquals(before,state.content)
        state.choose("first");state.reorder("first",true);assertEquals("first",state.content.scene.document.nodes.last().id)
        state.deleteSelection();assertNull(state.node("first"));state.undo();assertNotNull(state.node("first"))
        val builtin=BuiltinThemes.packages().first();val copy=EditorSession(builtin,true).packageForSave()
        assertNotEquals(builtin.metadata.id,copy.metadata.id)
        assertEquals(SceneXml.read(builtin),SceneXml.read(copy))
    }
}
