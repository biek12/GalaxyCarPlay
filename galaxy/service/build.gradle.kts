import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.util.Properties

plugins { alias(libs.plugins.android.application) }
// 旧整屏 HAB bridge 已归档：不再执行 native 构建，也不把 libgalaxy_hab_bridge.so 打进 main jniLibs。
// 保留同名无动作 Task（无 Exec、无输入输出、无依赖）只为兼容既有离线脚本的 -x :service:buildHab。
val buildHab by tasks.registering {
    // 归档占位：新版不构建、不打包任何旧 Android bridge。
}
val qnxAssets = layout.buildDirectory.dir("generated/qnx/assets")
val prepareQnxReceiver by tasks.registering(Exec::class) {
    inputs.dir("src/main/cpp/hab")
    inputs.dir(rootProject.file("tools/hab"))
    inputs.file(rootProject.file("scripts/prepare_auto_qnx_asset.py"))
    inputs.file(rootProject.file(".work/hab-probes/build-report.json"))
    outputs.dir(qnxAssets)
    commandLine(
        "python3",
        rootProject.file("scripts/prepare_auto_qnx_asset.py"),
        "--output",
        qnxAssets.get().dir("qnx").asFile,
    )
}
val clusterAssets = layout.buildDirectory.dir("generated/cluster/assets")
val prepareClusterHook by tasks.registering(Exec::class) {
    inputs.dir(rootProject.file("galaxy/common/src/main/cpp/kanzi_background"))
    inputs.dir(rootProject.file("galaxy/common/src/main/cpp/kanzi_music"))
    inputs.dir(rootProject.file("tools/research"))
    inputs.file(rootProject.file("scripts/build_g733_background_hook.py"))
    inputs.file(rootProject.file("scripts/prepare_cluster_hook_asset.py"))
    inputs.file(rootProject.file(".local/research/cluster-product-assets/profile.json"))
    outputs.dir(clusterAssets)
    commandLine(
        "python3",
        rootProject.file("scripts/prepare_cluster_hook_asset.py"),
        "--output",
        clusterAssets.get().dir("cluster").asFile,
    )
}
// Deployment endpoint stays outside version control; public builds use a reserved example host.
val privateServer =
    Properties().apply {
        rootProject
            .file(".private/server.properties")
            .takeIf { it.isFile }
            ?.inputStream()
            ?.use { load(it) }
    }
val bridgeOrigin =
    providers
        .environmentVariable("GALAXY_ORIGIN")
        .orElse(privateServer.getProperty("bridge.origin", "https://bridge.example.invalid/galaxy"))
        .get()
require(
    bridgeOrigin.startsWith("https://") &&
        !bridgeOrigin.contains('"') &&
        !bridgeOrigin.contains('\\') &&
        !bridgeOrigin.contains('\n') &&
        !bridgeOrigin.contains('\r'),
)

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    namespace = "com.ecarx.autoservice"
    compileSdk = 37
    defaultConfig {
        buildConfigField("String", "GALAXY_ORIGIN", "\"$bridgeOrigin\"")
        applicationId = "com.ecarx.autoservice"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        // 旧整屏投屏烟测 runner 已归档，不再声明 testInstrumentationRunner。
    }
    packaging.jniLibs.useLegacyPackaging = true
    // 共享 QNX/hook 资产准备保留：QnxClusterHook 仍以 receiver-candidate 作 health。
    sourceSets
        .getByName("main")
        .assets.directories
        .add(qnxAssets.get().asFile.path)
    sourceSets
        .getByName("main")
        .assets.directories
        .add(clusterAssets.get().asFile.path)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
// buildHab 是归档占位，不挂 preBuild；prepareQnxReceiver/prepareClusterHook 仍按共享资产链保留。
tasks.named("preBuild") { dependsOn(prepareQnxReceiver, prepareClusterHook) }
dependencies {
    implementation(project(":auto-ui"))
    implementation(project(":auto-client"))
    implementation(libs.kadb)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation(libs.bouncycastle)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}

/** KADB 2.1.1 源码 CertUtils 的两个 RSA KeyFactory 调用改用局部 Provider。
 * 不修改依赖缓存/全局 Security；精确限定类、方法和调用次数，上游变化必须重新核验。
 */
abstract class KadbLocalCrypto : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun isInstrumentable(classData: ClassData) = classData.className == "com.flyfishxu.kadb.cert.CertUtils"

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            private var replaced = 0

            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val next = super.visitMethod(access, name, descriptor, signature, exceptions)
                return object : MethodVisitor(Opcodes.ASM9, next) {
                    override fun visitMethodInsn(
                        opcode: Int,
                        owner: String,
                        method: String,
                        desc: String,
                        isInterface: Boolean,
                    ) {
                        if (opcode == Opcodes.INVOKESTATIC &&
                            owner == "java/security/KeyFactory" &&
                            method == "getInstance" &&
                            desc == "(Ljava/lang/String;)Ljava/security/KeyFactory;" &&
                            name in setOf("parsePrivateKeyPem", "derivePublicKey")
                        ) {
                            replaced++
                            super.visitMethodInsn(
                                opcode,
                                "com/ecarx/autoservice/platform/AdbCrypto",
                                "keyFactory",
                                desc,
                                false,
                            )
                        } else {
                            super.visitMethodInsn(opcode, owner, method, desc, isInterface)
                        }
                    }
                }
            }

            override fun visitEnd() {
                check(replaced == 2) { "KADB CertUtils RSA 接点已改变，必须重核局部 Provider 补丁" }
                super.visitEnd()
            }
        }
}
androidComponents.onVariants { variant ->
    check(libs.versions.kadb.get() == "2.1.1") { "升级 KADB 前必须重核局部 RSA Provider 补丁" }
    variant.instrumentation.transformClassesWith(KadbLocalCrypto::class.java, InstrumentationScope.ALL) {}
}
