package com.galaxy.autocluster.theme.render

/** AutoService规范化状态；未知/过期绝不推断成已激活。 */
internal enum class CruiseVisualState {
    UNKNOWN,OFF,STANDBY,ACTIVE;
    companion object {
        fun from(value: String?): CruiseVisualState = when(value){
            "off"->OFF
            "standby"->STANDBY
            "active"->ACTIVE
            else->UNKNOWN
        }
    }
}
