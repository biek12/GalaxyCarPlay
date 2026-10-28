package com.shilapi.xcertplay

/** 产品证据只在适配层转换；文件关闭、轮转与队列代次继续由同一核心所有者控制。 */
internal var SessionLogFile.configuration: GalaxyConfigurationEvidence?
    get() = context as? GalaxyConfigurationEvidence
    set(value) { context = value }

/** 核心仅接收通用上下文与字符串前缀，不反向依赖产品事件编号或配置数据。 */
internal fun AsyncDiagnosticLog.appendGalaxy(
    target: SessionLogFile?,
    message: String,
    nowMillis: Long = System.currentTimeMillis(),
    configuration: GalaxyConfigurationEvidence? = target?.configuration,
    eventId: String = DiagnosticLogIdentity.next(),
) = append(target, message, nowMillis, context = configuration,
    eventPrefix = DiagnosticLogIdentity.prefix(eventId, nowMillis))
