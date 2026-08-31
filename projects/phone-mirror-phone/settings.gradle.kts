pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "phone-mirror-phone"

include(":app")
include(":core")
include(":transport:adb-core")
include(":transport:adb-wifi")
include(":transport:adb-usb")
include(":mirror:scrcpy-protocol")
include(":mirror:scrcpy-session")
include(":mirror:video-decoder")
include(":data:cache")
include(":data:remote-files")
include(":data:gallery")
include(":privilege:shizuku")
include(":privilege:dhizuku")
