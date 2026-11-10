import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

plugins { alias(libs.plugins.android.application) }
val habLibraries = layout.buildDirectory.dir("generated/hab/jniLibs")
val buildHab by tasks.registering(Exec::class) {
    val sdk = providers.environmentVariable("ANDROID_HOME").orElse(rootProject.file(".work/android-sdk").path)
    val ndk = providers.environmentVariable("GALAXY_ANDROID_NDK").orElse(sdk.map { "$it/ndk/28.2.13676358" })
    inputs.dir("src/main/cpp/hab")
    inputs.file(rootProject.file("scripts/build_hab_bridge.py"))
    outputs.dir(habLibraries)
    commandLine("python3", rootProject.file("scripts/build_hab_bridge.py"), "--ndk", ndk.get(),
        "--output", habLibraries.get().file("arm64-v8a/libgalaxy_hab_bridge.so").asFile)
}
val qnxAssets = layout.buildDirectory.dir("generated/qnx/assets")
val prepareQnxReceiver by tasks.registering(Exec::class) {
    inputs.dir("src/main/cpp/hab")
    inputs.dir(rootProject.file("tools/hab"))
    inputs.file(rootProject.file("scripts/prepare_auto_qnx_asset.py"))
    inputs.file(rootProject.file(".work/hab-probes/build-report.json"))
    outputs.dir(qnxAssets)
    commandLine("python3", rootProject.file("scripts/prepare_auto_qnx_asset.py"), "--output", qnxAssets.get().dir("qnx").asFile)
}
// Deployment endpoint stays outside version control; public builds use a reserved example host.
val privateServer = java.util.Properties().apply {
    rootProject.file(".private/server.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val bridgeOrigin = providers.environmentVariable("GALAXY_ORIGIN")
    .orElse(privateServer.getProperty("bridge.origin", "https://bridge.example.invalid/galaxy")).get()
require(bridgeOrigin.startsWith("https://") && !bridgeOrigin.contains('"') && !bridgeOrigin.contains('\\') && !bridgeOrigin.contains('\n') && !bridgeOrigin.contains('\r'))

android {
    buildFeatures { buildConfig = true }
    namespace = "com.ecarx.autoservice"
    compileSdk = 37
    defaultConfig {
        buildConfigField("String", "GALAXY_ORIGIN", "\"$bridgeOrigin\"")
        applicationId = "com.ecarx.autoservice"; minSdk = 29; targetSdk = 37; versionCode = 1; versionName = "0.1.0"; testInstrumentationRunner = "com.ecarx.autoservice.projection.ProjectionSmokeInstrumentation" }
    packaging.jniLibs.useLegacyPackaging = true
    sourceSets.getByName("main").jniLibs.directories.add(habLibraries.get().asFile.path)
    sourceSets.getByName("main").assets.directories.add(qnxAssets.get().asFile.path)
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
tasks.named("preBuild") { dependsOn(buildHab, prepareQnxReceiver) }
dependencies {
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
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                val next = super.visitMethod(access, name, descriptor, signature, exceptions)
                return object : MethodVisitor(Opcodes.ASM9, next) {
                    override fun visitMethodInsn(opcode: Int, owner: String, method: String, desc: String, isInterface: Boolean) {
                        if (opcode == Opcodes.INVOKESTATIC && owner == "java/security/KeyFactory" && method == "getInstance" &&
                            desc == "(Ljava/lang/String;)Ljava/security/KeyFactory;" && name in setOf("parsePrivateKeyPem", "derivePublicKey")) {
                            replaced++
                            super.visitMethodInsn(opcode, "com/ecarx/autoservice/platform/AdbCrypto", "keyFactory", desc, false)
                        } else super.visitMethodInsn(opcode, owner, method, desc, isInterface)
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
