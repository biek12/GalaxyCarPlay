package com.ecarx.autoservice

import android.os.Bundle
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 请求、UI回调、实际节点挂接分别核验，不能用请求布尔值生成成功。 */
internal object ClusterHookReceipt {
    fun decode(bytes:ByteArray,generation:Long,source:Int,expected:Int,now:Long):Bundle? {
        if(bytes.size!=64)return null
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if(b.getInt(0)!=0x31524847 || b.getLong(8)!=generation || b.getInt(24)!=source ||
            b.getLong(16)<=now || b.getLong(16)-now>6000 || b.getInt(48)==0 || b.getInt(52)<=0)return null
        val mounted=b.getInt(4)
        if(mounted and 3!=mounted)return null
        return Bundle().apply {
            putString("state",if(expected!=0 && mounted and expected==expected)"active" else "waiting-content")
            putBoolean("backgroundMounted",mounted and 2!=0);putBoolean("musicMounted",mounted and 1!=0)
            putLong("generation",generation);putLong("backgroundSequence",b.getLong(32));putLong("mediaRevision",b.getLong(40))
            putBoolean("eglSubmitted",true)
        }
    }
}
