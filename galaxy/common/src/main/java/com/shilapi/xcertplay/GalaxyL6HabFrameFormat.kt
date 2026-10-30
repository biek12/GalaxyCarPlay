package com.shilapi.xcertplay

/** Static Java-side AutoDisplay v3.6.2 profile evidence; not a firmware-verified runtime profile. */
internal data class GalaxyL6HabSampleTarget(
    val virtualScreenId: String,
    val nativeDisplayId: Int,
    val width: Int,
    val height: Int,
    /** 样本screen_specs第五项及nativeSetZOrder共同确认是窗口层级。 */
    val zOrder: Int,
    val nativeOpenMagic: Int,
    val fps: Int,
    val slots: Int,
    val openTimeoutMillis: Int,
) {
    init {
        require(virtualScreenId.isNotBlank())
        require(nativeDisplayId >= 0)
        require(width >= 16 && height >= 16)
        require(nativeOpenMagic >= 0)
        require(fps in 1..60)
        require(slots in 2..8)
        require(openTimeoutMillis in 1..60_000)
    }

    companion object {
        /**
         * Values reconstructed from the AutoDisplay v3.6.2 sample's MainActivity.S0/kt/hy:
         * virtual_cluster maps to native target id 1 at 1920x532; the sample forwards z-order 1
         * when its default ScreenConfig is not a presentation layer (otherwise 0).
         * The sample passes nativeOpen service id 0x580259 (5767769); Java samples at 30 fps,
         * 6 slots and 10 s timeout. These are versioned sample inputs, not a firmware-verified
         * L6 contract.
         */
        fun autoDisplayV362Sample() = GalaxyL6HabSampleTarget(
            virtualScreenId = "virtual_cluster",
            nativeDisplayId = 1,
            width = 1920,
            height = 532,
            zOrder = 1,
            nativeOpenMagic = 0x580259,
            fps = 30,
            slots = 6,
            openTimeoutMillis = 10_000,
        )
    }
}
