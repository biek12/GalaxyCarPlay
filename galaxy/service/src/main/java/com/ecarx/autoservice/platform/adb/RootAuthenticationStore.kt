package com.ecarx.autoservice.platform.adb

/**
 * 读取已保存的 VPWD。调用方拥有返回的字符数组，本接口不落盘。
 */
internal interface RootAuthenticationStore {
    /** 没有凭据时返回 null。不把“未配置”抛成传输失败。 */
    fun readVpwd(): CharArray?
}
