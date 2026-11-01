/*
 * 原厂固件类的最小替身（仅单测classpath，不进入APK）。
 *
 * 生产代码用 PathClassLoader 从 /system/priv-app/ECarXCarService 读取该类的
 * mHaltoMgrPropIdMap，并反射调用 getHalPropId 判断“本固件是否真的支持该属性ID”。
 * Robolectric 环境没有那个APK，缺少本替身会让 connect() 在建表阶段抛异常，
 * 整条车辆采集链路变成 not_connected——采集逻辑本身就无法被覆盖。
 *
 * 替身只声明生产代码实际用到的两个反射面：静态字段 mHaltoMgrPropIdMap，
 * 以及字段值类型上的 getHalPropId(int)。返回值取原ID，表示全部已映射。
 */
package com.ecarx.car.hal

class HalPropertyIdTransformation {
    /** 原厂实现是自定义Map子类，getHalPropId声明在这个映射对象上，不是外层类上。 */
    class Mapping {
        fun getHalPropId(mgrPropertyId: Int): Int = mgrPropertyId
        fun getMgrPropId(halPropertyId: Int): Int = halPropertyId
    }

    companion object {
        @JvmField
        val mHaltoMgrPropIdMap: Any = Mapping()
    }
}