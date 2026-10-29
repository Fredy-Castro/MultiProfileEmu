import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// Release signing config lives in keystore.properties (gitignored, never committed) pointing at
// release.jks (also gitignored) alongside it. Both are generated once per machine/developer and
// must be backed up separately — losing them means future releases can never update an install
// signed with the old key.
val keystorePropertiesFile = file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.multiprofileemu.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.multiprofileemu.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // librclone.so under jniLibs/ is really a static rclone binary, not a real shared library —
    // it needs to be physically extracted to disk at install time so ProcessBuilder can exec()
    // it (execve needs a real file path, unlike dlopen which can mmap straight out of the APK
    // zip). AGP's modern default packs native libs uncompressed and unextracted for that reason;
    // this opts back into the old extract-to-disk behavior, matching extractNativeLibs="true" in
    // AndroidManifest.xml.
    packaging {
        jniLibs.useLegacyPackaging = true
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // applicationIdSuffix keeps this fully separate from a release install on the same
        // device (different package, coexists side by side) — debuggable=true (AGP's default
        // for this build type) is what unlocks `adb shell run-as` for inspecting this specific
        // process's environment/sandbox, which a release build never allows.
        debug {
            applicationIdSuffix = ".debug"
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
}
