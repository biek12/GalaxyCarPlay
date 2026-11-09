package com.ecarx.autoservice.sentinel

import android.os.*
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue

/** G733 专用哨兵口。只允许已核验的待机/关闭和CH服务就绪，不接受任意属性/值。 */
internal class RootSentinelPort:Closeable {
    private val events=ArrayBlockingQueue<JSONObject>(32)
    private val thread=HandlerThread("sentinel-power").apply{start()}
    private val handler=Handler(thread.looper)
    private var power:Any?=null
    private var listener:Any?=null
    private var property:Any?=null
    @Volatile private var state=0
    @Volatile private var closed=false
    @Volatile private var armed=false
    private var sequence=0L
    private var pendingCompletion=0L
    private var lastModeRequestAt=0L
    private fun service(name:String):IBinder=Class.forName("android.os.ServiceManager")
        .getMethod("getService",String::class.java).invoke(null,name) as? IBinder?:error("sentinel_service_missing")
    private fun proxy(descriptor:String,binder:IBinder):Any {
        check(binder.interfaceDescriptor==descriptor){"sentinel_descriptor_mismatch"}
        return Class.forName("$descriptor\$Stub").getMethod("asInterface",IBinder::class.java).invoke(null,binder)
    }
    @Synchronized private fun connect(withPower:Boolean=true) {
        check(!closed)
        if(property==null){
            val car=proxy("ecarx.car.IECarXCar",service("ecarxcar_service"))
            val signal=Class.forName("ecarx.car.IECarXCar").getMethod("getCarService",String::class.java).invoke(car,"car_signal") as IBinder
            check(signal.interfaceDescriptor=="ecarx.car.hardware.property.IECarXCarProperty")
            property=Class.forName("ecarx.car.hardware.property.ECarXCarPropertyManagerBase")
                .getConstructor(IBinder::class.java,Handler::class.java,Boolean::class.javaPrimitiveType,String::class.java)
                .newInstance(signal,handler,false,"AutoServiceSentinel")
        }
        if(!withPower)return
        if(power==null){
            val car=proxy("android.car.ICar",service("car_service"))
            val binder=Class.forName("android.car.ICar").getMethod("getCarService",String::class.java).invoke(car,"power") as IBinder
            val port=proxy("android.car.hardware.power.ICarPower",binder)
            val callbackBinder=object:Binder(){
                override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
                    if(code==INTERFACE_TRANSACTION){reply?.writeString("android.car.hardware.power.ICarPowerStateListener");return true}
                    if(code!=1)return super.onTransact(code,data,reply,flags)
                    data.enforceInterface("android.car.hardware.power.ICarPowerStateListener")
                    val next=data.readInt()
                    handler.post{if(!closed){state=next;pendingCompletion=if(next==7)sequence+1 else 0L;publish();if(armed&&(next==3||next==6))runCatching{write(CH,1)}}}
                    reply?.writeNoException();return true
                }
            }
            val type=Class.forName("android.car.hardware.power.ICarPowerStateListener")
            val callback=java.lang.reflect.Proxy.newProxyInstance(type.classLoader,arrayOf(type)){p,m,a->when(m.name){
                "asBinder"->callbackBinder;"hashCode"->System.identityHashCode(p);"equals"->p===a?.firstOrNull();"toString"->"AutoServiceSentinelPower";else->null}}
            try{
                port.javaClass.getMethod("registerListenerWithCompletion",type).invoke(port,callback)
                state=(port.javaClass.getMethod("getPowerState").invoke(port) as Number).toInt()
                power=port;listener=callback;publish()
            }catch(e:Exception){runCatching{port.javaClass.getMethod("unregisterListener",type).invoke(port,callback)};throw e}
        }
    }
    @Synchronized private fun publish(){
        val value=JSONObject().put("power",state).put("powerSequence",++sequence).put("at",SystemClock.elapsedRealtime())
        check(events.offer(value)){"sentinel_power_queue_exhausted"}
    }
    private fun write(id:Int,value:Int){
        check(id==MODE&&value in 0..1||id==CH&&value in 0..1)
        val manager=checkNotNull(property)
        val result=manager.javaClass.getMethod("setIntProperty",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType)
            .invoke(manager,id,1,value)
        check(result.toString()=="SUCCEED"){"sentinel_write_rejected"}
    }
    @Synchronized fun control(action:String,powerSequence:Long=0,requestAt:Long=0):JSONObject {
        if(action=="powerReady"&&power==null)return JSONObject().put("accepted",false)
        if(action in setOf("arm","resume","disarm")){
            require(requestAt>0){"sentinel_request_timestamp_missing"}
            check(requestAt>=lastModeRequestAt){"sentinel_request_obsolete"};lastModeRequestAt=requestAt
        }
        connect(action!="disarm")
        when(action){"arm"->{write(CH,1);write(MODE,1);armed=true};"resume"->{write(CH,1);armed=true};"disarm"->{armed=false;write(MODE,0);disconnectPower()};"status"->Unit;"powerReady"->{
            if(powerSequence>0&&powerSequence==pendingCompletion){
                val port=checkNotNull(power);val type=Class.forName("android.car.hardware.power.ICarPowerStateListener")
                if(state==7)write(CH,0)
                port.javaClass.getMethod("finished",type).invoke(port,listener);pendingCompletion=0
            }
        };else->error("sentinel_action_invalid")}
        return JSONObject().put("accepted",true).put("power",state).put("at",SystemClock.elapsedRealtime())
    }
    fun stream(output:java.io.DataOutputStream){
        connect()
        com.ecarx.autoservice.platform.RootWire.write(output,JSONObject().put("power",state).put("powerSequence",sequence).put("at",SystemClock.elapsedRealtime()))
        while(!closed){val event=events.poll(30,java.util.concurrent.TimeUnit.SECONDS)
            ?:JSONObject().put("power",state).put("powerSequence",sequence).put("at",SystemClock.elapsedRealtime())
            com.ecarx.autoservice.platform.RootWire.write(output,event)}
    }
    private fun disconnectPower(){power?.let{port->listener?.let{cb->runCatching{port.javaClass.getMethod("unregisterListener",Class.forName("android.car.hardware.power.ICarPowerStateListener")).invoke(port,cb)}}};power=null;listener=null}
    override fun close(){closed=true;armed=false;disconnectPower();property=null;events.offer(JSONObject().put("closed",true));thread.quitSafely()}
    companion object {const val MODE=557877190;const val CH=557877184}
}
