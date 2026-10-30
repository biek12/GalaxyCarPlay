package com.ecarx.autoservice

import android.os.IBinder
import android.os.Handler
import android.os.Looper
import com.galaxy.auto.contract.*

/** 主线程持有租约。身份由入口固定，死亡与迟到解绑不能删除下一代owner。 */
internal class ClientRegistry(private val died: (IBinder) -> Unit) {
    data class Lease(val owner: IBinder, val uid: Int, val role: String, val callback: IAutoCallback,
        val death: IBinder.DeathRecipient, var active: Boolean = false)
    private val clients = linkedMapOf<IBinder, Lease>()
    fun attach(owner: IBinder, uid: Int, role: String, callback: IAutoCallback): Lease {
        require(AutoContract.roleValid(role)); check(clients.size < 16)
        check(clients[owner] == null) { "owner_already_attached" }
        if (role != AutoContract.OBSERVER) check(clients.values.none { it.role == role }) { "role_already_owned" }
        lateinit var death: IBinder.DeathRecipient
        death = IBinder.DeathRecipient {
            Handler(Looper.getMainLooper()).post { if (clients[owner]?.death === death) died(owner) }
        }
        owner.linkToDeath(death, 0)
        return Lease(owner, uid, role, callback, death).also { clients[owner] = it }
    }
    fun require(owner: IBinder, uid: Int): Lease = checkNotNull(clients[owner]).also { check(it.uid == uid) }
    fun remove(owner: IBinder): Lease? = clients.remove(owner)?.also { runCatching { owner.unlinkToDeath(it.death, 0) } }
    fun all() = clients.values.toList()
    fun role(role: String) = clients.values.firstOrNull { it.role == role }
    fun close() { clients.keys.toList().forEach(::remove) }
}
