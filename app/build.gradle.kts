import java.util.Properties
import java.security.MessageDigest
plugins { id("com.android.application") }

// Hash only shipped implementation and build configuration, never outputs or private signing files.
fun implementationHash(entries: List<Pair<String, ByteArray>>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for ((path, bytes) in entries.sortedBy { it.first }) {
        digest.update(path.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
val implementationFiles = files(fileTree("src/main"), buildFile,
    rootProject.file("build.gradle.kts"), rootProject.file("settings.gradle.kts"),
    rootProject.file("gradle.properties"), rootProject.file("gradle/wrapper/gradle-wrapper.properties"))
val implementationEntries = implementationFiles.files.map {
    it.relativeTo(rootProject.projectDir).invariantSeparatorsPath to providers.fileContents(layout.file(provider { it })).asBytes.get()
}
val controlBuildId = implementationHash(implementationEntries)
val controlServiceVersion = controlBuildId.take(7).toInt(16) + 4
val verifyControlBuildIdentity = tasks.register("verifyControlBuildIdentity") {
    inputs.files(implementationFiles)
    doLast {
        check(implementationHash(implementationEntries.reversed()) == controlBuildId)
        check(implementationHash(implementationEntries.map { (path, bytes) ->
            path to if (path.endsWith("PrivilegedControl.kt")) bytes + 1.toByte() else bytes
        }) != controlBuildId)
        check(implementationEntries.any { it.first.endsWith("IControl.aidl") })
        check(implementationEntries.any { it.first.endsWith("AndroidManifest.xml") })
        check(implementationEntries.none { it.first.contains("/build/") || it.first.contains("signing.properties") })
        val generated = layout.buildDirectory.file("identity-verification/ignored.txt").get().asFile
        generated.parentFile.mkdirs()
        generated.writeText("output must not affect identity")
        check(implementationHash(implementationFiles.files.map {
            it.relativeTo(rootProject.projectDir).invariantSeparatorsPath to it.readBytes()
        }) == controlBuildId)
        logger.lifecycle("Control implementation ID: $controlBuildId; service version: $controlServiceVersion")
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifyControlBuildIdentity) }
val signing = Properties().apply {
    val f = file(System.getProperty("user.home") + "/.android-auto-music-restart-signing/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
android {
    namespace = "com.jimmyshin.automusicrestart"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.jimmyshin.automusicrestart"
        minSdk = 33
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"
        testInstrumentationRunner = "com.jimmyshin.automusicrestart.DeviceChecks"
        buildConfigField("String", "CONTROL_BUILD_ID", "\"$controlBuildId\"")
        buildConfigField("int", "CONTROL_SERVICE_VERSION", controlServiceVersion.toString())
    }
    buildFeatures { aidl = true; buildConfig = true }
    testBuildType = "release"
    signingConfigs {
        create("personal") {
            if (signing.isNotEmpty()) {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("personal"); isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
}
