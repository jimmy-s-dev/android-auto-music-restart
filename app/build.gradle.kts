import java.util.Properties
plugins { id("com.android.application") }
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
        versionCode = 2
        versionName = "1.0.1"
        testInstrumentationRunner = "com.jimmyshin.automusicrestart.DeviceChecks"
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
