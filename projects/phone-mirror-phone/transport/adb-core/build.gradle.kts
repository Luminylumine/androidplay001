plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.phone.mirror.transport.adb.core"
    compileSdk = 34

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // core 的 Result 出现在本模块公开 API（AdbConnection/AdbStream/AdbTransport）中，用 api 暴露
    api(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.core)

    testImplementation("junit:junit:4.13.2")
}
