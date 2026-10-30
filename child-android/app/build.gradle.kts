plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.anxinban.kid"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.anxinban.kid"
        minSdk = 26
        targetSdk = 34
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0.0"
    }

    signingConfigs {
        create("release") {
            val ksPath = rootProject.file("keystore/anxinban.keystore")
            if (ksPath.exists()) {
                storeFile = ksPath
                // PKCS12 只支持一个口令：key 口令必须与 store 口令一致
                storePassword=System.getenv("KEYSTORE_PASS") ?: "AnxinbanStore2026"
                keyAlias = System.getenv("KEY_ALIAS") ?: "anxinban"
                keyPassword=System.getenv("KEYSTORE_PASS") ?: "AnxinbanStore2026"
            }
        }
    }

    buildTypes {
        release {
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
