package com.galaxy.auto.client.diagnostics

import android.content.*
import android.database.Cursor
import android.net.Uri

/** manifest合并后在四个自有App统一安装；仅初始化本地崩溃证据，不启动远程控制。 */
class DiagnosticsInitProvider : ContentProvider() {
    override fun onCreate():Boolean { DiagnosticEvents.initialize(context!!);return true }
    override fun query(u:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?):Cursor?=null
    override fun getType(u:Uri):String?=null
    override fun insert(u:Uri,v:ContentValues?):Uri?=null
    override fun delete(u:Uri,s:String?,a:Array<out String>?)=0
    override fun update(u:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0
}
