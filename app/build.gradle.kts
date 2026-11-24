import java.util.Properties

plugins { id("com.android.application"); id("com.chaquo.python"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "com.oas.android"
    compileSdk { version = release(37) }
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.oas.android"
        minSdk = 28
        targetSdk = 35
        versionCode = 10
        versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    signingConfigs {
        val propsFile = rootProject.file("keystore/keystore.properties")
        if (propsFile.isFile) {
            val props = Properties().apply { propsFile.inputStream().use { load(it) } }
            create("release") {
                storeFile = rootProject.file("keystore/" + props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    buildFeatures { aidl = true; buildConfig = true; compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
chaquopy {
    defaultConfig {
        version = "3.10"
        buildPython(providers.gradleProperty("pythonExecutable").getOrElse("D:/OnmyojiAutoScript-easy-install/OnmyojiAutoScript-easy-install/toolkit/python.exe"))
        pip {
            install("numpy==1.23.3")
            install("pandas==1.5.0")
            install("opencv-python==4.5.1.48")
            install("Pillow")
            install("rich==13.3.5")
            install("pyyaml==6.0.3")
            install("requests==2.32.3")
            install("cached-property==1.5.2")
            install("inflection==0.5.1")
            install("anytree==2.8.0")
            install("cn2an==0.5.23")
            install("typing-extensions==4.12.2")
            install("filelock==3.16.1")
            install("tqdm==4.65.0")
            install("retry==0.9.2")
            install("pydantic==2.10.0")
            install("scipy")
            install("psutil")
            install("lxml")
            install("cryptography")
            install("pycryptodome")
            install("dnspython")
            install("PySocks")
            install("future==1.0.0")
            install("fastapi==0.104.1")
            install("websockets==13.1")
            install("shapely")
            options("--find-links", "${rootProject.projectDir}/.toolchains/wheels")
        }
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.opencv:opencv:4.12.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    testImplementation("junit:junit:4.13.2")
}
