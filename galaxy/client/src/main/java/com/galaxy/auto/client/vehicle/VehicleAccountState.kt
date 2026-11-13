package com.galaxy.auto.client.vehicle

import org.json.JSONObject

/**
 * 车辆账号页的只读呈现数据。
 *
 * 输入是服务端 JSON 和调用方传入的当前时间，输出是页面可以直接绑定的字段。
 * 不持有 Context、View 或 Handler，不写配置，也不发送请求。缺字段保持原始默认值，
 * 不补车辆、手机号，也不把缺字段当成已绑定。
 */
data class VehicleAccountState(
    val model: String,
    val androidVersion: String,
    val serviceVersion: String,
    val bound: Boolean,
    val enabled: Boolean,
    val ownerName: String,
    val ownerPhone: String,
    val code: String,
    val scanUrl: String,
    val expiresAt: Long,
    /** 到期时间仍晚于当前时间。标题和申请按钮只看这一项。 */
    val codeActive: Boolean,
    /** 绑定码非空且仍未过期时才显示二维码、文本码和有效期。 */
    val codeVisible: Boolean,
    val bindings: List<PendingAccount>,
    val jsonError: String,
) {
    /**
     * 按回执错误、JSON 错误、在线说明的顺序决定提示。
     *
     * 回执里只要有 error 键就采用它，包括空白；JSON 错误只在非空白时采用。
     * 两者都没有时，由 enabled 选择长期有效或尚未启用的固定说明。
     */
    fun hint(replyError: String?): Hint {
        if (replyError != null) {
            return Hint.Reply(replyError)
        }
        if (jsonError.isNotBlank()) {
            return Hint.Json(jsonError)
        }
        return if (enabled) Hint.Enabled else Hint.Disabled
    }

    /** 一条尚未过期的待确认账号。身份只用 bindingId，不用列表位置。 */
    data class PendingAccount(
        val bindingId: String,
        val displayName: String,
        val phoneMasked: String,
    )

    /** 底部提示的来源。固定句子由页面资源决定，动态文本只携带服务端原文。 */
    sealed class Hint {
        /** 本次请求回执里的 error。 */
        data class Reply(
            val text: String,
        ) : Hint()

        /** 最近一份有效车辆 JSON 里的 error。 */
        data class Json(
            val text: String,
        ) : Hint()

        /** 在线服务已开启。 */
        data object Enabled : Hint()

        /** 在线服务未开启。 */
        data object Disabled : Hint()
    }

    companion object {
        /**
         * 解析一份车辆 JSON。
         *
         * [nowMillis] 由调用方显式传入，用来判断绑定码和待确认请求是否仍有效。
         * JSON 不是对象时返回 null，调用方必须保留上一份有效数据。
         */
        fun parse(
            json: String,
            nowMillis: Long,
        ): VehicleAccountState? {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val info = root.optJSONObject("basicInfo") ?: JSONObject()
            val owner = root.optJSONObject("owner") ?: JSONObject()
            val expiresAt = root.optLong("expiresAt")
            val code = root.optString("code")
            val codeActive = expiresAt > nowMillis
            return VehicleAccountState(
                model = info.optString("model"),
                androidVersion = info.optString("androidVersion"),
                serviceVersion = info.optString("serviceVersion"),
                bound = root.optBoolean("bound"),
                enabled = root.optBoolean("enabled"),
                ownerName = owner.optString("displayName"),
                ownerPhone = owner.optString("phoneMasked"),
                code = code,
                scanUrl = root.optString("scanUrl"),
                expiresAt = expiresAt,
                codeActive = codeActive,
                codeVisible = code.isNotBlank() && codeActive,
                bindings = pendingAccounts(root, nowMillis),
                jsonError = root.optString("error"),
            )
        }

        /**
         * 按服务器数组顺序收集未过期、且有 bindingId 的请求。
         *
         * 到期边界是 expiresAt <= now。没有身份的行不能确认或拒绝，直接跳过。
         */
        private fun pendingAccounts(
            root: JSONObject,
            nowMillis: Long,
        ): List<PendingAccount> {
            val array = root.optJSONArray("bindings") ?: return emptyList()
            val rows = mutableListOf<PendingAccount>()
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                if (row.optLong("expiresAt") <= nowMillis) {
                    continue
                }
                val bindingId = row.optString("bindingId")
                if (bindingId.isEmpty()) {
                    continue
                }
                rows +=
                    PendingAccount(
                        bindingId = bindingId,
                        displayName = row.optString("displayName"),
                        phoneMasked = row.optString("phoneMasked"),
                    )
            }
            return rows
        }
    }
}
