plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.anxinban.kid"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.anxinban.kid"
        minSdk = 26
        targetSdk = 34
        // 由 CI 通过 -PversionCode / -PversionName 传入，本地构建时用默认值
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0.0"
    }

    signingConfigs {
        create("release") {
            val ksPath = rootProject.file("keystore/anxinban.keystore")
            if (ksPath.exists()) {
                storeFile = ksPath
                storePassword = System.getenv("KEYSTORE_PASS") ?: "AnxinbanStore2026"
                keyAlias = System.getenv("KEY_ALIAS") ?: "anxinban"
                keyPassword = System.getenv("KEY_PASS") ?: "AnxinbanKey2026"
            }
        }
    }

    buildTypes {
        release {
            // 固定签名：保证每次编译签名一致，支持覆盖安装更新，
            // DeviceAdmin / 无障碍授权 / 绑定数据在更新后全部保留。
            signingConfig = if (rootProject.file("keystore/anxinban.keystore").exists())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
}
