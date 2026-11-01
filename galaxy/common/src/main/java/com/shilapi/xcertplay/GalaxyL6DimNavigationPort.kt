package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException

/** L6's documented Autolink DIM Binder contract; this carries TBT metadata, never map pixels. */
internal class GalaxyL6DimNavigationPort(context: Context) : L7NavigationPort {
    private val app = context.applicationContext
    @Volatile private var binder: IBinder? = null
    @Volatile private var bound = false
    @Volatile private var valid = true

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (valid) binder = service?.takeIf(IBinder::isBinderAlive)
        }

        override fun onServiceDisconnected(name: ComponentName?) { binder = null }
        override fun onBindingDied(name: ComponentName?) {
            binder = null
            unbind()
            if (valid) runCatching { initialize() }
        }
        override fun onNullBinding(name: ComponentName?) { binder = null }
    }

    override fun initialize() {
        if (!valid || bound) return
        val intent = Intent().setComponent(ComponentName(SERVICE_PACKAGE, SERVICE_CLASS))
        bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        check(bound) { "L6_DIM_BIND_REJECTED" }
    }

    override fun ready(): Boolean = valid && binder?.isBinderAlive == true
    override fun connectionToken(): Any? = binder?.takeIf { valid && it.isBinderAlive }

    /** DIM service status codes from the L6 Binder contract: 1=start, 0=stop. */
    override fun start() {
        transact(TRANSACTION_NAVIGATION_STATUS) { writeInt(STATUS_START) }
        // TBT event is a separate QNX notification in the same service contract.
        transact(TRANSACTION_TBT_EVENT) { writeInt(TBT_EVENT_START) }
    }

    override fun road(value: String) {
        transact(TRANSACTION_NEXT_GUIDANCE_NAME) { writeString(value.take(MAX_ROAD_NAME_CHARS)) }
    }

    override fun stop() {
        transact(TRANSACTION_TBT_EVENT, allowInvalid = true) { writeInt(TBT_EVENT_STOP) }
        transact(TRANSACTION_NAVIGATION_STATUS, allowInvalid = true) { writeInt(STATUS_STOP) }
    }

    override fun invalidate() { valid = false }

    override fun close() {
        invalidate()
        unbind()
    }

    private fun transact(code: Int, allowInvalid: Boolean = false, writeArguments: Parcel.() -> Unit) {
        val target = binder?.takeIf { (valid || allowInvalid) && it.isBinderAlive } ?: throw RemoteException("L6_DIM_BINDER_UNAVAILABLE")
        transactBinder(target, code, writeArguments)
    }

    @Synchronized private fun unbind() {
        if (!bound) return
        bound = false
        binder = null
        runCatching { app.unbindService(connection) }
    }

    companion object {
        const val SERVICE_PACKAGE = "com.autolink.diminteraction"
        const val SERVICE_CLASS = "com.autolink.diminteraction.DIMNaviInteractioncService"
        const val DESCRIPTOR = "com.autolink.adapterbinder.IDimNaviInteractioncService"
        const val TRANSACTION_NAVIGATION_STATUS = 1
        const val TRANSACTION_TBT_EVENT = 11
        const val TRANSACTION_NEXT_GUIDANCE_NAME = 23
        const val STATUS_STOP = 0
        const val STATUS_START = 1
        const val TBT_EVENT_START = 0
        const val TBT_EVENT_STOP = 1
        const val MAX_ROAD_NAME_CHARS = 256

        internal fun transactBinder(target: IBinder, code: Int, writeArguments: Parcel.() -> Unit) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeArguments()
                check(target.transact(code, data, reply, 0)) { "L6_DIM_TRANSACTION_UNHANDLED:$code" }
                reply.readException()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }
}
