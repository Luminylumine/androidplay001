plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.phone.mirror.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phone.mirror.phone"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    // Kotlin 1.9.24 需要显式 Compose compiler 扩展版本 (Kotlin 2.0+ 内置)
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.activity.compose)

    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    implementation(libs.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    // 内部模块
    implementation(project(":core"))
    implementation(project(":transport:adb-core"))
    implementation(project(":transport:adb-wifi"))
    implementation(project(":transport:adb-usb"))
    implementation(project(":mirror:scrcpy-protocol"))
    implementation(project(":mirror:scrcpy-session"))
    implementation(project(":mirror:video-decoder"))
    implementation(project(":data:cache"))
    implementation(project(":data:remote-files"))
    implementation(project(":data:gallery"))
    implementation(project(":privilege:shizuku"))
    implementation(project(":privilege:dhizuku"))

    // 只在 debug 下注入的 UI 调试工具
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
