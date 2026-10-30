plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.anxinban.kid"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.anxinban.kid"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            // 自用安装：使用 debug 签名，免去申请证书的麻烦。
            // 若要上架应用商店或分发给他人，请改用正式签名。
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
