plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.androidplay.mdclient"
    compileSdk = 36
    compileSdkExtension = 19

    defaultConfig {
        applicationId = "com.androidplay.mdclient"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java")
            res.srcDirs("src/main/res")
            manifest.srcFile("src/main/AndroidManifest.xml")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.pdf:pdf-core:1.0.0-beta01")
    implementation("androidx.pdf:pdf-document-service:1.0.0-beta01")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(fileTree(mapOf("dir" to ".local-deps", "include" to listOf("*.aar"))))
    testImplementation("junit:junit:4.13.2")
}
