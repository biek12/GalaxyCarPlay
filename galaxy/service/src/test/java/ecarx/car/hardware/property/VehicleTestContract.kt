package ecarx.car.hardware.property

import android.os.IBinder
import android.os.Handler
import ecarx.car.hardware.signal.SignalFilter

interface IECarXCarProperty {
    fun getProperty(id: Int, area: Int): ECarXCarPropertyValue
    fun getPropertyList(): List<TestConfig>
    object Stub {
        lateinit var service: IECarXCarProperty
        @JvmStatic fun asInterface(binder: IBinder) = service
    }
}
class ECarXCarPropertyValue(private val id: Int, private val area: Int, private val value: Any?) {
    fun getPropertyId() = id
    fun getAreaId() = area
    fun getValue() = value
}
class TestConfig(private val id: Int, private val areas: IntArray) {
    fun getPropertyId() = id
    fun getAreaIds() = areas
    fun getAccess() = 1
    fun getPropertyType() = Int::class.java
}
object SigPropertyIdString { @JvmField val sigIdToStringMap = emptyMap<Int, String>() }
class ECarXCarPropertyManagerBase(service: IBinder, handler: Handler, debug: Boolean, tag: String) {
    interface CarPropertyEventCallback {
        fun onChangeEvent(value: ECarXCarPropertyValue)
        fun onErrorEvent(property: Int, area: Int)
    }
    companion object { var callback: CarPropertyEventCallback? = null }
    fun registerCallback(value: CarPropertyEventCallback) { callback = value }
    fun unregisterCallback(value: CarPropertyEventCallback) { if (callback === value) callback = null }
    fun registerSignals(filter: SignalFilter) {}
    fun unregisterSignals(filter: SignalFilter) {}
}
