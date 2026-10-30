package com.galaxy.autocluster.theme

/** 几何拖动保留属性Map身份，复用已解析样式；有界缓存不随撤销历史无限增长。 */
internal class ScenePropertyCache {
    private class Key(val type: String,val maps: List<Map<String,String>>,val night: Boolean) {
        override fun hashCode(): Int = maps.fold(type.hashCode()*31+night.hashCode()) { hash,map -> hash*31+System.identityHashCode(map) }
        override fun equals(other: Any?): Boolean = other is Key && type==other.type && night==other.night && maps.size==other.maps.size && maps.indices.all { maps[it]===other.maps[it] }
    }
    private val cache=object : LinkedHashMap<Key,VisualProperties>(32,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key,VisualProperties>?)=size>256
    }
    fun get(type: String,maps: List<Map<String,String>>,night: Boolean,create: () -> VisualProperties): VisualProperties =
        cache.getOrPut(Key(type,maps,night),create)
    fun clear()=cache.clear()
}
