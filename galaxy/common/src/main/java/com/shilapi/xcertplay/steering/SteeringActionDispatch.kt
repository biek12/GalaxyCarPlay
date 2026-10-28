package com.shilapi.xcertplay.steering

import com.shilapi.xcertplay.orchestration.CarPlayController

/**
 * 方控动作 → CarPlay 控制器的唯一映射。
 *
 * 播放/暂停/上一曲/下一曲经 `media`（运行时是 [GalaxySteeringMediaDispatch]，含跨源窗口与
 * 上游蓝牙等待闸门）；Siri/接听/挂断走控制器对应方法。控制器已关闭或手机会话已经更替
 * （owner不再是本会话）时一律不发送，避免把旧动作打到新手机的会话上。
 */
internal object SteeringActionDispatch {
    fun dispatch(controller: CarPlayController, owner: Any, media: ((Int) -> Boolean)?,
                 action: SteeringAction): Boolean {
        if (controller.isClosed()) return false
        if (controller.activeMediaSessionOwner() !== owner) return false
        val index = GalaxySteeringMediaWindow.index(action)
        if (index != null) return media?.invoke(index) ?: controller.sendMediaButton(index, owner)
        return when (action) {
            SteeringAction.SIRI -> controller.requestSiri()
            SteeringAction.ANSWER_CALL -> controller.answerCall()
            SteeringAction.END_CALL -> controller.endCall()
            else -> false
        }
    }
}