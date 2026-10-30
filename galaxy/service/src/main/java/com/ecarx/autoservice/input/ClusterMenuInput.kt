package com.ecarx.autoservice.input

/** 原车HW_KEY_INPUT为[action,key,...]；AutoDisplay 3.6.2已用raw key=6菜单、18行程。
 * 不混用Android KEYCODE_MENU=82/Autolink remote=200082；监听不消费原车动作。
 * 短按在RELEASE发一次，重复DOWN、单独UP、CANCEL、跨会话/迟到事件都不能切页。
 */
internal class ClusterMenuInput {
    private var downAt: Long?=null
    private var session=""
    private var sequence=0L
    fun reset(){downAt=null;session="";sequence=0}
    fun accept(session: String,sequence: Long,action: Int,key: Int,at: Long): Boolean {
        if(this.session!=session){downAt=null;this.session=session;this.sequence=0}
        if(sequence<=this.sequence)return false
        this.sequence=sequence
        if(key!=6)return false
        return when(action){
            0->{if(downAt==null)downAt=at;false}
            1->{val start=downAt;downAt=null;start!=null&&at-start in 0..699}
            else->{downAt=null;false}
        }
    }
}
