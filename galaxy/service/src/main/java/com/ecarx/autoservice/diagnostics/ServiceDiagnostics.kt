package com.ecarx.autoservice.diagnostics

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import com.ecarx.autoservice.ipc.ClientRegistry
import com.ecarx.autoservice.ipc.ServiceSnapshots
import com.ecarx.autoservice.settings.ServiceFeatures
import com.ecarx.autoservice.startup.AutoStartup
import com.ecarx.autoservice.vehicle.ClusterFieldDiagnostics
import com.ecarx.autoservice.vehicle.ServiceVehicleSession
import com.ecarx.autoservice.vehicle.VehicleCatalog
import com.ecarx.autoservice.vehicle.VehicleDemand
import com.galaxy.auto.contract.AutoContract
import java.io.File
import java.io.PrintWriter

/**
 * 只读诊断文本。
 *
 * foundation-check 在读快照缓存之前返回。车辆值只补同一份订阅缓存，不扫描、不采集、不访问 Root 或 HTTP。
 */
internal class ServiceDiagnostics(
    private val context: Context,
    private val clients: ClientRegistry,
    private val snapshots: ServiceSnapshots,
    private val vehicle: () -> ServiceVehicleSession,
    private val paused: () -> Boolean,
    private val rootReady: () -> Boolean,
    private val features: () -> ServiceFeatures,
    private val foundation: () -> String,
) {
    /**
     * 按原分支顺序写诊断。
     *
     * 前缀和字段名保持调用方脚本使用的文本。
     */
    fun dump(
        writer: PrintWriter,
        args: Array<out String>,
    ) {
        if ("--foundation-check" in args) {
            writer.println("FOUNDATION_CHECK=" + foundation())
            return
        }
        val snapshot = snapshots.current()
        if ("--record-properties" in args) {
            writer.println("RECORD_PROPERTIES=" + vehicle().diagnostics())
            return
        }
        if ("--record-fields" in args) {
            val withVehicle = Bundle(snapshot).apply { putBundle("vehicle", vehicle().feedSnapshot()) }
            val keys =
                VehicleCatalog.forModel(snapshot.getString("model") ?: "L6").map { it.key } +
                    VehicleCatalog.LEGACY_ALIASES.keys
            writer.println(
                "RECORD_FIELDS=" +
                    ClusterFieldDiagnostics.collect(withVehicle, SystemClock.elapsedRealtime(), keys),
            )
            return
        }
        if ("--cluster-fields" in args) {
            val withVehicle = Bundle(snapshot).apply { putBundle("vehicle", vehicle().feedSnapshot()) }
            writer.println(
                "CLUSTER_FIELDS=" + ClusterFieldDiagnostics.collect(withVehicle, SystemClock.elapsedRealtime()),
            )
            return
        }
        writer.println("AUTOSERVICE_DIAGNOSTIC version=${AutoContract.VERSION}")
        for (key in listOf("status", "model", "vehicleMode", "vehicleDiscovery")) {
            writer.println("$key=${snapshot.getString(key)}")
        }
        for (key in listOf("vehiclePropertyCount", "vehicleSampledCount", "vehicleValidCount")) {
            writer.println("$key=${snapshot.getInt(key)}")
        }
        writer.println("projectionArchived=true paused=${paused()} rootReady=${rootReady()}")
        writer.println("clients=" + clients.all().joinToString { "${it.role}:${it.active}" })
        writer.println(
            "feeds=" +
                clients
                    .all()
                    .filter { it.feed.isNotEmpty() }
                    .joinToString {
                        "${it.role}:${it.feed.size}@${it.feedIntervalMillis}ms[${it.feed.joinToString(
                            ",",
                        )}]"
                    },
        )
        writer.println(
            "vehicleDemand=" +
                VehicleDemand
                    .merge(clients.all().map { it.feed to it.feedIntervalMillis })
                    .entries
                    .joinToString { "${it.key}@${it.value}ms" },
        )
        writer.println("vehicleStats=" + vehicle().stats())
        writer.println("hab=archived")
        writer.println(
            "clusterElementsRequested=${snapshot.getBoolean("clusterElementsRequested")} revision=${
                snapshot.getLong("clusterElementRevision")
            } configuration=${snapshot.getString("clusterConfigurationStatus")} state=${
                snapshot.getString("clusterElementStatus")
            }",
        )
        writer.println("clusterElementRuntime=" + snapshot.getBundle("clusterElementRuntime"))
        writer.println("automatic=" + AutoStartup.enabled(context))
        val diagnosticPrefs = context.getSharedPreferences("diagnostics", 0)
        writer.println(
            "diagnosticLastUpload=" + diagnosticPrefs.getLong("lastUploadAt", 0) + " error=" +
                diagnosticPrefs.getString("lastUploadError", "") +
                " pending=" +
                File(context.noBackupFilesDir, "diagnostic-inbox").listFiles().orEmpty().size,
        )
        writer.println("features=${features()}")
        writer.println("adbIdentityConfigured=" + File(context.noBackupFilesDir, "adb/client.pem").exists())
        writer.println("qnxCredentialsConfigured=" + File(context.noBackupFilesDir, "qnx-login.json").exists())
    }
}
