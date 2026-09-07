import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "com.multiprofileemu.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "MultiProfileEmu"
            packageVersion = "1.0.0"
            description = "Perfiles de save intercambiables para emuladores compartidos en familia"

            // IMPORTANT (Linux only): whichever JDK runs this Gradle build is the one jlink bakes
            // into the packaged runtime image — it must be a vendor-neutral build (e.g. Eclipse
            // Temurin), NOT Ubuntu/Debian's apt-packaged OpenJDK. Confirmed on real hardware
            // (Bazzite/KDE): a jlink'd Ubuntu-packaged OpenJDK 17 renders JFileChooser with every
            // label/button/filename blank (icons and layout still show), while the exact same code
            // built with Temurin 17 renders it correctly. See README's AppImage section.

            // jlink's automatic module detection (jdeps) can't see LWJGL's reflective use of
            // sun.misc.Unsafe, so it strips jdk.unsupported out of the packaged runtime image —
            // the app still launches fine, but the gamepad thread dies silently in the background
            // with NoClassDefFoundError the moment GLFW tries to load. `./gradlew :desktopApp:run`
            // never hits this because it runs on the full system JDK, not the jlinked one.
            // Explicitly listing modules("jdk.unsupported") did NOT make it into the actual
            // packaged runtime (verified with `jimage list` against the built image) — this
            // bypasses jdeps detection entirely and just bundles every JDK module instead.
            includeAllModules = true

            windows {
                // Per-user install: no admin/UAC prompt needed, matches how most consumer desktop
                // apps install today. Installs to the current user's AppData instead of Program Files.
                perUserInstall = true
                menu = true
                shortcut = true
            }
        }
    }
}
