package com.galaxy.autocluster.theme.render

/** 页面状态随自有主题实例生存；重连快照只建立基线，不重播旧菜单事件。 */
internal class PageMotion {
    data class Visual(val current: String,val previous: String,val fraction: Float)
    private data class State(var order: List<String>,var initial: String,var current: Int,var previous: Int,
        var epoch: String,var sequence: Long,var changed: Long)
    private val states=mutableMapOf<String,State>()
    fun sample(id: String,order: List<String>,initial: String,epoch: String,sequence: Long,eventAt: Long,now: Long,duration: Long): Visual {
        val state=states.getOrPut(id){val index=order.indexOf(initial).coerceAtLeast(0);State(order,initial,index,index,epoch,sequence,now)}
        if(state.order!=order||state.initial!=initial){state.order=order;state.initial=initial;state.current=order.indexOf(initial).coerceAtLeast(0);state.previous=state.current}
        if(state.epoch!=epoch){state.epoch=epoch;state.sequence=sequence;state.previous=state.current}
        else if(sequence>state.sequence){
            val steps=(sequence-state.sequence)%order.size
            state.sequence=sequence
            if(eventAt>0&&now-eventAt in 0..2000&&steps!=0L){
                state.previous=state.current;state.current=(state.current+steps.toInt())%order.size;state.changed=now
            }
        }
        val fraction=if(duration<=0||state.previous==state.current)1f else ((now-state.changed).toFloat()/duration).coerceIn(0f,1f)
        return Visual(order[state.current],order[state.previous],SceneMotion.ease(fraction,"smooth"))
    }
    fun retain(ids: Set<String>){states.keys.retainAll(ids)}
    fun clear(){states.clear()}
}
