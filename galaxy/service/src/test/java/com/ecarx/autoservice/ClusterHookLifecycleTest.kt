package com.ecarx.autoservice

import com.ecarx.autoservice.platform.QnxClusterRemote
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[30],manifest=org.robolectric.annotation.Config.NONE)
class ClusterHookLifecycleTest {
    private val assets=mapOf("g733_hook_runtime.sh" to "script".toByteArray(),"health" to "health".toByteArray())
    @Test fun matchingLiveProcessReusesWithoutRestartOrStage(){
        val commands=mutableListOf<String>();val root=Files.createTempDirectory("cluster-reuse").toFile()
        QnxClusterRemote({command->commands+=command;when{
            command.startsWith("if [ -r")->"AS_CLUSTER_INSTALLED\n"
            command.contains("echo AS_CLUSTER_LIVE")->"AS_CLUSTER_LIVE\n"
            else->"G733_RUNTIME_MATERIALS_MATCH\n"
        }},assets,"version",{true},root).use {assertTrue(it.ensure(1).getBoolean("reused"))}
        assertFalse(commands.any{it.contains("kill -")||it.contains("activate --")});assertEquals(0,root.listFiles()!!.size);root.delete()
    }
    @Test fun firstStartStagesVerifiesAndOnlyThenActivates(){
        val root=Files.createTempDirectory("cluster-install").toFile();val commands=mutableListOf<String>()
        QnxClusterRemote({command->commands+=command;when{
            command.startsWith("if [ -r")->"AS_CLUSTER_INSTALL_REQUIRED\n"
            command.startsWith("n=0;")->"AS_CLUSTER_RESTORED\n"
            command.contains("activate --authorized-runtime")->"G733_RUNTIME_UI_HEARTBEAT test-ticket\n"
            else->"G733_RUNTIME_MATERIALS_MATCH\n"
        }},assets,"version",{true},root).use {assertFalse(it.ensure(2).getBoolean("reused"))}
        val copy=commands.indexOfFirst{it.contains("cp /otaupdate/")};val verify=commands.indexOfLast{it.endsWith(" inspect")};val start=commands.indexOfFirst{it.contains("activate --authorized-runtime")}
        assertTrue(copy>=0&&verify>copy&&start>verify);assertEquals(0,root.listFiles()!!.size);root.delete()
    }
    @Test fun lateCancelledLeaseCannotActivateAfterDeployment(){
        var active=true;var activated=false;val root=Files.createTempDirectory("cluster-cancel").toFile()
        val remote=QnxClusterRemote({command->when{
            command.startsWith("if [ -r")->"AS_CLUSTER_INSTALL_REQUIRED\n"
            command.startsWith("n=0;")->"AS_CLUSTER_RESTORED\n"
            command.contains("cp /otaupdate/")->{active=false;"ok"}
            command.contains("activate --")-> {activated=true;error("must not launch")}
            else->"G733_RUNTIME_MATERIALS_MATCH\n"
        }},assets,"version",{active},root)
        remote.use{assertThrows(IllegalStateException::class.java){it.ensure(3)}}
        assertFalse(activated);assertEquals(0,root.listFiles()!!.size);root.delete()
    }
    @Test fun fixedFirmwareMismatchCannotActivate(){
        var launched=false
        QnxClusterRemote({command->when{
            command.startsWith("if [ -r")->"AS_CLUSTER_INSTALLED\n"
            command.endsWith(" inspect")->error("firmware_mismatch")
            else->{launched=true;""}
        }},assets,"version",{true}).use {assertThrows(IllegalStateException::class.java){it.ensure(4)}}
        assertFalse(launched)
    }
}
