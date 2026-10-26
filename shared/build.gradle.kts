plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm("desktop")
    androidTarget()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.common)
                implementation(libs.lwjgl.core)
                implementation(libs.lwjgl.glfw)

                val lwjglVersion = libs.versions.lwjgl.get()
                runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:natives-windows")
                runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:natives-linux")
                runtimeOnly("org.lwjgl:lwjgl-glfw:$lwjglVersion:natives-windows")
                runtimeOnly("org.lwjgl:lwjgl-glfw:$lwjglVersion:natives-linux")
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.documentfile)
                implementation(libs.shizuku.api)
                implementation(libs.shizuku.provider)
            }
        }
    }
}

android {
    namespace = "com.multiprofileemu.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    // IPrivilegedFileOps.aidl: the interface ShizukuBridge binds to, implemented by
    // PrivilegedFileOpsService and run in a process Shizuku spawns under adb shell's UID.
    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
