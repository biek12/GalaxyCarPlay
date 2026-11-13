package com.galaxy.auto.client.vehicle

import android.view.LayoutInflater
import android.view.ViewGroup
import com.galaxy.auto.client.R
import com.galaxy.auto.client.databinding.ItemVehicleBindingRequestBinding

/**
 * 待确认账号行。
 *
 * 只填充已经 inflate 的行，并按 bindingId 回调确认或拒绝。不解析 JSON，不发请求，
 * 也不另存一份绑定状态。
 */
class VehicleBindingRequestsAdapter(
    private val container: ViewGroup,
    private val onDecision: (bindingId: String, accepted: Boolean) -> Unit,
) {
    private val rows = mutableListOf<ItemVehicleBindingRequestBinding>()
    private var ids = emptyList<String>()

    /**
     * 用当前有效请求替换或刷新行。
     *
     * 身份顺序没变时只更新文字、回调和可用状态，避免把同一行拆掉再造。
     * [actionsEnabled] 为 false 时两颗按钮都不可点，返回键不在这里。
     */
    fun submit(
        requests: List<VehicleAccountState.PendingAccount>,
        actionsEnabled: Boolean,
    ) {
        val next = requests.map { it.bindingId }
        if (next == ids && rows.size == requests.size) {
            requests.forEachIndexed { index, request ->
                bind(rows[index], request, actionsEnabled)
            }
            return
        }
        container.removeAllViews()
        rows.clear()
        val inflater = LayoutInflater.from(container.context)
        requests.forEach { request ->
            val binding = ItemVehicleBindingRequestBinding.inflate(inflater, container, true)
            rows += binding
            bind(binding, request, actionsEnabled)
        }
        ids = next
    }

    /** 只切换已有行的按钮可用性。列表身份由 [submit] 维护。 */
    fun setActionsEnabled(actionsEnabled: Boolean) {
        rows.forEach { binding ->
            binding.bindingConfirm.isEnabled = actionsEnabled
            binding.bindingReject.isEnabled = actionsEnabled
        }
    }

    /**
     * 把一行的账号文字和两个按钮绑到同一个 bindingId。
     *
     * 点击时读取该行自己的身份，刷新或删除其他行不会串到这一行。
     */
    private fun bind(
        binding: ItemVehicleBindingRequestBinding,
        request: VehicleAccountState.PendingAccount,
        actionsEnabled: Boolean,
    ) {
        val context = binding.root.context
        binding.bindingAccount.text =
            context.getString(
                R.string.vehicle_account_binding_account,
                request.displayName,
                request.phoneMasked,
            )
        binding.bindingConfirm.isEnabled = actionsEnabled
        binding.bindingReject.isEnabled = actionsEnabled
        binding.bindingConfirm.setOnClickListener {
            if (binding.bindingConfirm.isEnabled) {
                onDecision(request.bindingId, true)
            }
        }
        binding.bindingReject.setOnClickListener {
            if (binding.bindingReject.isEnabled) {
                onDecision(request.bindingId, false)
            }
        }
    }
}
