package com.shilapi.xcertplay

/**
 * 位置/车速来源的选择规则，单独成纯函数以便回归：产品自有来源优先，其次沿用上游判定。
 *
 * 上游 v0.2.22 的 ECARX 分支只要检测到 SDK 就认为车机无 GPS，参数来自其他车型；产品接入时
 * 必须由产品给出已核验来源，不能沿用这条推断。位置与车速是两个独立决定：本车有位置时，
 * 轮速只作为 `$PASCD` 附加句子，不替代定位。
 */
internal object CarPlayLocationSelection {
    enum class Kind(val speed: Boolean, val position: Boolean) {
        /** 用户关闭位置上报：位置与轮速都不发。 */
        DISABLED(false, false),
        /** 产品自有轮速来源，位置仍由本车 provider 提供。 */
        PRODUCT(true, true),
        /** 上游ECARX且无产品来源：只发轮速（上游假定无GPS）。 */
        VEHICLE_SPEED_ONLY(true, false),
        /** 上游BYD路径：位置与轮速都发。 */
        POSITION_WITH_SPEED(true, true),
        /** 只发位置。 */
        POSITION_ONLY(false, true),
    }

    fun choose(locationReporting: Boolean, speedEnabled: Boolean, productSpeed: Boolean,
               bydWheelSpeed: Boolean): Kind = when {
        !locationReporting -> Kind.DISABLED
        speedEnabled && productSpeed -> Kind.PRODUCT
        !speedEnabled -> Kind.POSITION_ONLY
        !bydWheelSpeed -> Kind.VEHICLE_SPEED_ONLY
        else -> Kind.POSITION_WITH_SPEED
    }
}