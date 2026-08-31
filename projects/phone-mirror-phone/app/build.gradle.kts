plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.phone.mirror.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phone.mirror.phone"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
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
