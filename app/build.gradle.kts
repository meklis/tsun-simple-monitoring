import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersionName = "1.2"
// CI passes its run number so every published build installs over the previous one.
val appVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 4

base {
    archivesName = "tsun-monitor-$appVersionName"
}

android {
    namespace = "com.meklis.tsunmonitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.meklis.tsunmonitor"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    // The release key lives outside git (keystore/, restored from secrets on CI);
    // every update must be signed with it to install over the previous version.
    val keystoreProps = rootProject.file("keystore/keystore.properties")
    signingConfigs {
        if (keystoreProps.exists()) {
            val props = Properties().apply { keystoreProps.inputStream().use(::load) }
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
