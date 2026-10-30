package ecarx.car

import android.os.IBinder

/** 测试夹具，不进入APK；模拟本车公开Binder结构，不包含OEM实现。 */
interface IECarXCar : android.os.IInterface {
    fun getCarService(name: String): IBinder
    object Stub {
        lateinit var service: IECarXCar
        @JvmStatic fun asInterface(binder: IBinder) = service
    }
}
