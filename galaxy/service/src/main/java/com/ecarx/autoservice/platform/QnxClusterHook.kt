package com.ecarx.autoservice.platform

import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** 自有Hook的固定操作入口。只允许ensure/inspect/stop，不接受Shell、路径或认证参数。 */
internal object QnxClusterHook {
    private const val SHARED="/data/vendor/nfs/nfs_ota/autoservice-media"
    private const val DIR="/var/data/autoservice-cluster"
    private val lock=Any()
    private fun currentLease(generation:Long):Boolean = runCatching {
        val bytes=File(SHARED,"hook.bin").readBytes()
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes.size==32 && b.getInt(0)==0x314c4847 && b.getInt(4)!=0 && b.getLong(8)==generation &&
            b.getLong(16)>System.currentTimeMillis() && b.getLong(16)-System.currentTimeMillis()<=6000
    }.getOrDefault(false)
    fun receipt():JSONObject {
        // 现场NFS原子rename窗口会返回EIO；读失败是缺少回执，不能冒充HMI失败。
        val bytes=runCatching{File(SHARED,"hook-status.bin").takeIf{it.isFile&&it.length()==64L}?.readBytes()}
            .onFailure{com.ecarx.autoservice.ServiceTrace.event("cluster_receipt_read",it)}.getOrNull()?:ByteArray(0)
        return JSONObject().put("receipt",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP))
    }
    fun control(request:JSONObject,userId:Int):JSONObject = synchronized(lock) {
        val action=request.getString("action")
        require(action in setOf("ensure","inspect","stop"))
        val generation=request.optLong("generation")
        if(action=="ensure")check(currentLease(generation)){"cluster_lease_inactive"}
        val apk=System.getenv("CLASSPATH")?.substringBefore(':')?:error("apk_missing")
        val files=ZipFile(apk).use { zip->
            val names=listOf("libgalaxy_g733_cluster.so","g733_hook_runtime.sh","hmi.cksum","libc.cksum","background.cksum","rightmenu.cksum","hook.cksum","build.json")
            names.associateWith { name->
                val entry=zip.getEntry("assets/cluster/$name")?:error("cluster_asset_missing")
                require(entry.size in 1..2*1024*1024)
                zip.getInputStream(entry).use{it.readBytes()}
            } + ("health" to zip.getInputStream(zip.getEntry("assets/qnx/receiver-candidate")?:error("receiver_asset_missing")).use{it.readBytes()})
        }
        val login=QnxCredentials.login(request)
        QnxTelnet("192.168.118.2").use { remote->
            remote.login(login.username,login.password)
            val deployed=files.toMutableMap()
            val image=request.optString("image")
            if(action=="ensure" && image.isNotEmpty()) {
                require(userId in 0..9999 && image.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,95}\\.(png|jpg|jpeg|webp)")))
                val private=File("/data/user/$userId/com.ecarx.autoservice/no_backup/cluster-backgrounds")
                val original=File(private,"oem-background.kzb")
                if(!original.isFile) {
                    val stage=File("/data/vendor/nfs/nfs_ota","autoservice-background-${UUID.randomUUID()}")
                    check(stage.mkdir()){"cluster_stage_unavailable"}
                    try {
                        remote.execute("cp /apps/cluster/FX11_HEV/bin/hmi/GLY_G733/ClusterHMI/background.kzb /otaupdate/${stage.name}/original.kzb")
                        check(original.parentFile!!.isDirectory || original.parentFile!!.mkdirs())
                        val captured=File(stage,"original.kzb").readBytes()
                        check(KzbBackgroundPatch.originalMatches(captured)){"cluster_background_material_mismatch"}
                        val atomic=android.util.AtomicFile(original);val stream=atomic.startWrite()
                        try{stream.write(captured);atomic.finishWrite(stream)}catch(error:Exception){atomic.failWrite(stream);throw error}
                    } finally {stage.listFiles()?.forEach{it.delete()};stage.delete()}
                }
                val png=File(private,"$image.rgb.png").also{check(it.isFile&&it.length() in 45..KzbBackgroundPatch.PNG_BYTES.toLong()){"cluster_background_png_missing"}}.readBytes()
                val candidate=KzbBackgroundPatch.replace(original.readBytes(),png)
                deployed["background.kzb"]=candidate
                // 资源版本只由图片字节决定；运行租约换代不应重部署同一背景并重启HMI。
                deployed["background.proof"]=KzbBackgroundPatch.proof(candidate)
                deployed["background.mode"]="replace\n".toByteArray()
            } else deployed["background.mode"]="observe\n".toByteArray()
            val digest=MessageDigest.getInstance("SHA-256")
            deployed.toSortedMap().values.forEach{digest.update(it)}
            val version=digest.digest().joinToString(""){"%02x".format(it)}
            QnxClusterRemote(remote::execute,deployed,version,::currentLease).use { port->
                when(action){
                    "ensure"->port.ensure(generation)
                    "stop"->port.stop()
                    else->port.inspect()
                }
            }
        }
    }
}

/** 串行部署、固定材料校验、唯一进程及独立guard均在成功回执之前。 */
internal class QnxClusterRemote(
    private val execute:(String)->String,
    private val files:Map<String,ByteArray>,
    private val version:String,
    private val leaseActive:(Long)->Boolean,
    private val stageRoot:File=File("/data/vendor/nfs/nfs_ota"),
):AutoCloseable {
    private val directory="/var/data/autoservice-cluster"
    private var stage:File?=null
    private val pids="pidin -F '%a %A' | awk '\$1+0>1 && (\$2==\"./cluster-hmi\" || \$2==\"cluster-hmi\" || \$2==\"/apps/cluster/FX11_HEV/bin/hmi/GLY_G733/ClusterHMI/cluster-hmi\") {print \$1}'"
    private val checks=files.filterKeys{it!="build.json"}.map { (name,bytes)->
        "cksum $directory/$name | grep '^[[:space:]]*${PosixChecksum.calculate(bytes)}[[:space:]]*${bytes.size}[[:space:]]' >/dev/null"
    }
    // 本车交互终端不能完整接收聚合数千字节的校验行；每个事务保持为短命令。
    private fun filesMatch()=checks.all { command ->
        execute("if $command; then echo AS_CLUSTER_FILE_MATCH; else echo AS_CLUSTER_FILE_MISMATCH; fi")
            .lineSequence().any{it.trim()=="AS_CLUSTER_FILE_MATCH"}
    }
    private fun installed():Boolean {
        val matching=execute("if [ -r $directory/version ] && [ \"\$(cat $directory/version)\" = '$version' ]; then echo AS_CLUSTER_VERSION_MATCH; else echo AS_CLUSTER_INSTALL_REQUIRED; fi")
            .lineSequence().any{it.trim()=="AS_CLUSTER_VERSION_MATCH"}
        return matching && filesMatch()
    }
    fun inspect():JSONObject {
        if(!installed())return JSONObject().put("state","not_installed")
        val status=execute("sh $directory/g733_hook_runtime.sh status")
        return JSONObject().put("state",if(status.lineSequence().any{it.trim()=="G733_RUNTIME_OWNED"})"owned" else "restored")
    }
    fun stop():JSONObject {
        // Fixed cleanup also works across an APK/controller upgrade; never execute an old unverified script.
        repeat(15) { attempt ->
            val processes=execute(pids).lineSequence().mapNotNull{it.trim().toIntOrNull()}.filter{it>1}.toList()
            val libraries=processes.associateWith{execute("pidin -p $it libs 2>/dev/null || :")}
            val owned=libraries.filterValues{it.contains("$directory/libgalaxy_g733_cluster.so")}.keys
            if(processes.size==1 && owned.isEmpty() && libraries.getValue(processes.single()).contains("PIE")) {
                execute("rm -rf $directory/runtime.lock; echo AS_CLUSTER_RESTORED")
                return JSONObject().put("state","restored")
            }
            owned.forEach { pid -> execute("kill -${if(attempt<2)"TERM"else"KILL"} $pid 2>/dev/null || :") }
            Thread.sleep(1000)
        }
        error("cluster_restore_unconfirmed")
    }
    fun ensure(generation:Long):JSONObject {
        check(leaseActive(generation)){"cluster_lease_inactive"}
        if(installed()) {
            execute("sh $directory/g733_hook_runtime.sh inspect")
            // 连续观察现有进程；单次NFS/心跳缺口不能触发原厂HMI重启。
            repeat(4){attempt->
                val live=execute("if sh $directory/g733_hook_runtime.sh status | grep G733_RUNTIME_OWNED >/dev/null && $directory/health hook-health; then echo AS_CLUSTER_LIVE; else echo AS_CLUSTER_HEALTH_PENDING; fi")
                if(live.lineSequence().any{it.trim()=="AS_CLUSTER_LIVE"})return JSONObject().put("state","running").put("reused",true)
                if(attempt<3)Thread.sleep(1000)
            }
        }
        stop()
        check(leaseActive(generation)){"cluster_lease_inactive"}
        if(!installed()) {
            val folder=File(stageRoot,"autoservice-cluster-${UUID.randomUUID()}")
            check(folder.mkdir()){"cluster_stage_unavailable"};stage=folder
            files.forEach{(name,bytes)->File(folder,name).writeBytes(bytes)}
            execute("umask 077; mkdir -p $directory")
            files.keys.forEach { name ->
                check(leaseActive(generation)){"cluster_lease_inactive"}
                execute("cp /otaupdate/${folder.name}/$name $directory/$name")
            }
            execute("chmod 700 $directory/health")
            check(filesMatch()) { "cluster_deployed_file_mismatch" }
            execute("echo '$version' > $directory/version")
        }
        execute("sh $directory/g733_hook_runtime.sh inspect")
        check(leaseActive(generation)){"cluster_lease_inactive"}
        val result=execute("sh $directory/g733_hook_runtime.sh activate --authorized-runtime")
        check(result.lineSequence().any{it.startsWith("G733_RUNTIME_UI_HEARTBEAT ")}){"cluster_start_unconfirmed"}
        if(!leaseActive(generation)){stop();error("cluster_lease_inactive")}
        return JSONObject().put("state","running").put("reused",false)
    }
    override fun close(){stage?.let{folder->folder.listFiles()?.forEach{it.delete()};folder.delete()};stage=null}
}
