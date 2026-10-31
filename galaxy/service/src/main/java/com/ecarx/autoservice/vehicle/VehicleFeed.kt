package com.ecarx.autoservice.vehicle

import android.os.Bundle

/**
 * v3按需车辆信号订阅的纯逻辑部分：键校验与按租约裁剪。
 *
 * 只在服务端做“过滤 + 深拷贝”，不补默认值、不补0、不改写status：
 * 某键本轮无有效值时整个键不出现在结果里，消费者按 receivedAt+maxAgeMillis 自行判过期。
 * 这里不持有任何Binder/线程状态，便于离线测试。
 */
internal object VehicleFeed {
    /**
     * 校验订阅键并规范化。
     *
     * 键只允许当前车型目录里的语义别名（与 [VehicleCatalog.forModel] 的 key 一致），
     * 未知键整体拒绝而不是静默丢弃：半订阅状态在实车上难以从回调侧区分，
     * 直接抛给调用方（Binder入口会转成 IllegalArgumentException 返回）更容易定位。
     * keys为null（AIDL层已由 AutoContract.feedKeysValid 拒绝）按空集处理，避免双重语义。
     *
     * @return 去重并按字典序稳定排序的键集合；空数组表示取消订阅。
     */
    fun validate(keys: Array<String>?, model: String): Set<String> {
        // 旧公共别名（fuelConsumption 等）在目录里没有独立SignalSpec，但必须仍可订阅：
        // 采集侧会把别名翻译成基础键去读，再派生同名子Bundle。
        val known = VehicleCatalog.forModel(model).mapTo(HashSet()) { it.key }.apply { addAll(VehicleCatalog.LEGACY_ALIASES.keys) }
        val accepted = ArrayList<String>(keys?.size ?: 0)
        keys?.forEach { key ->
            require(key in known) { "unknown_signal:$key" }
            accepted += key
        }
        // TreeSet：同一组键每次得到同一顺序，订阅回显/日志可直接比较。
        return accepted.toSortedSet()
    }

    /**
     * 从整轮信号快照里只取出该租约订阅的键。
     *
     * 子Bundle必须深拷贝：整轮快照 [values] 是服务端复用的对象，直接共享会让消费者端
     * 的修改反过来污染快照，也会让异步回调观察到后续轮次的覆盖。
     * 注意 Bundle(Bundle) 只复制map条目（子Bundle仍是同一引用，见 BaseBundle.copyInternal(b,false)），
     * 所以这里用 deepCopy()；minSdk 29 已包含该API（API 26起）。
     * 不写 at/sequence —— 那是发送端的节拍信息，由 AutoService 统一追加。
     */
    fun select(values: Bundle, keys: Set<String>): Bundle = Bundle().apply {
        for (key in keys) values.getBundle(key)?.let { putBundle(key, it.deepCopy()) }
    }
}