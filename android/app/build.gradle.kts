import java.util.Properties

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Flutter installs a missing NDK through sdkmanager. That download of
// android-ndk-r28c-*.zip dies with "Failed to read HTTP response: Peer disconnected"
// and the app never reaches the emulator. Prefer an NDK that is already installed.
// Otherwise fetch the required NDK with curl, which can resume, and mark it installed
// so the later sdkmanager call does not download it again.
fun androidSdkDir(): File? {
    val properties = Properties()
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) {
        propertiesFile.inputStream().use { properties.load(it) }
    }
    val path =
        properties.getProperty("sdk.dir")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: System.getenv("ANDROID_HOME")
    return path?.let(::File)?.takeIf { it.isDirectory }
}

fun ndkRoot(version: String): File? = androidSdkDir()?.let { File(it, "ndk/$version") }

fun ndkIsReady(version: String): Boolean {
    val root = ndkRoot(version) ?: return false
    return File(root, "source.properties").isFile && File(root, "ndk-build").isFile
}

fun compareNdkVersions(left: String, right: String): Int {
    val leftParts = left.split('.').map { it.toIntOrNull() ?: 0 }
    val rightParts = right.split('.').map { it.toIntOrNull() ?: 0 }
    val length = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until length) {
        val compared =
            leftParts.getOrElse(index) { 0 }.compareTo(rightParts.getOrElse(index) { 0 })
        if (compared != 0) {
            return compared
        }
    }
    return 0
}

fun markNdkInstalled(version: String) {
    val root = ndkRoot(version) ?: return
    val marker = File(root, "package.xml")
    if (marker.isFile) {
        return
    }
    val parts = version.split('.')
    val major = parts.getOrElse(0) { "0" }
    val minor = parts.getOrElse(1) { "0" }
    val micro = parts.getOrElse(2) { "0" }
    marker.writeText(
        """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02" xmlns:ns5="http://schemas.android.com/repository/android/generic/02">
        <license id="android-sdk-license" type="text">Terms</license>
        <localPackage path="ndk;$version" obsolete="false">
        <type-details xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="ns5:genericDetailsType"/>
        <revision><major>$major</major><minor>$minor</minor><micro>$micro</micro></revision>
        <display-name>NDK (Side by side) $version</display-name>
        <uses-license ref="android-sdk-license"/>
        </localPackage>
        </ns2:repository>
        """.trimIndent(),
    )
}

fun downloadNdk(version: String) {
    val release =
        mapOf("28.2.13676358" to "r28c")[version]
            ?: return
    val sdk = androidSdkDir() ?: return
    if (ndkIsReady(version)) {
        markNdkInstalled(version)
        return
    }
    val osName = System.getProperty("os.name").lowercase()
    val host =
        when {
            osName.contains("mac") || osName.contains("darwin") -> "darwin"
            osName.contains("win") -> "windows"
            else -> "linux"
        }
    val zip = File(sdk, "temp/android-ndk-$release-$host.zip")
    zip.parentFile.mkdirs()
    val url = "https://dl.google.com/android/repository/android-ndk-$release-$host.zip"
    logger.lifecycle("Downloading Android NDK $version from $url")
    val firstAttempt =
        providers.exec {
            commandLine(
                "curl",
                "-fL",
                "--retry",
                "10",
                "--retry-delay",
                "3",
                "--retry-all-errors",
                "-C",
                "-",
                "--connect-timeout",
                "30",
                "-o",
                zip.absolutePath,
                url,
            )
            isIgnoreExitValue = true
        }
    if (firstAttempt.result.get().exitValue != 0) {
        providers.exec {
            commandLine(
                "curl",
                "-fL",
                "--retry",
                "10",
                "--retry-delay",
                "3",
                "-C",
                "-",
                "--connect-timeout",
                "30",
                "-o",
                zip.absolutePath,
                url,
            )
        }.result.get()
    }
    val staging = File(sdk, "temp/ndk-$version")
    if (staging.exists()) {
        staging.deleteRecursively()
    }
    staging.mkdirs()
    providers.exec {
        commandLine("unzip", "-q", "-o", zip.absolutePath, "-d", staging.absolutePath)
    }.result.get()
    val extracted =
        staging.listFiles()?.firstOrNull { it.isDirectory && it.name.startsWith("android-ndk") }
            ?: throw GradleException("NDK archive did not contain an android-ndk directory.")
    val destination = File(sdk, "ndk/$version")
    destination.parentFile.mkdirs()
    if (destination.exists()) {
        destination.deleteRecursively()
    }
    if (!extracted.renameTo(destination)) {
        providers.exec {
            commandLine("mv", extracted.absolutePath, destination.absolutePath)
        }.result.get()
    }
    markNdkInstalled(version)
    zip.delete()
}

val installedNdkVersions =
    (findProperty("flutter.installedNdkVersions") as? String)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
val requestedNdkVersion = flutter.ndkVersion
val selectedNdkVersion =
    when {
        ndkIsReady(requestedNdkVersion) || installedNdkVersions.contains(requestedNdkVersion) ->
            requestedNdkVersion
        installedNdkVersions.isNotEmpty() -> installedNdkVersions.maxWith(::compareNdkVersions)
        else -> {
            downloadNdk(requestedNdkVersion)
            requestedNdkVersion
        }
    }
if (ndkIsReady(selectedNdkVersion)) {
    markNdkInstalled(selectedNdkVersion)
}

android {
    namespace = "com.example.clin_gait_ai"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = selectedNdkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.example.clin_gait_ai"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}
