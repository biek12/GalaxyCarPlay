package com.shilapi.xcertplay.media

/** 产品试听通过薄适配复用核心构建契约，不扩大核心internal类型的公共API。 */
object GalaxyAudioPreviewFallback {
    fun <T> build(
        createLegacy: () -> T,
        isInitialized: (T) -> Boolean,
        release: (T) -> Unit,
        createFallback: () -> T,
    ): T = LegacyAudioFallback.build(createLegacy, isInitialized, release, createFallback)
}
