import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

abstract class ExportReleaseToDesktopTask : DefaultTask() {
  @get:Input
  abstract val versionName: Property<String>

  @get:Input
  abstract val versionCode: Property<Int>

  @get:InputFile
  abstract val aabFile: RegularFileProperty

  @get:InputFile
  abstract val releaseNotesFile: RegularFileProperty

  @TaskAction
  fun export() {
    val home = File(System.getProperty("user.home"))
    val candidates = listOf(
      File(home, "OneDrive/바탕 화면/Build"),
      File(home, "OneDrive/Desktop/Build"),
      File(home, "Desktop/Build")
    )
    val desktopBuild = candidates.firstOrNull { it.parentFile.isDirectory }
      ?: throw GradleException("Could not find a Desktop directory to create Build folder inside.")
    
    val targetDir = File(desktopBuild.parentFile, "Build")
    if (!targetDir.exists()) {
      targetDir.mkdirs()
    }

    val aab = aabFile.get().asFile
    if (!aab.isFile) {
      throw GradleException(
        "Release AAB not found at ${aab.absolutePath}. " +
          "bundleRelease should have produced it; check the build log."
      )
    }

    val releaseNotes = releaseNotesFile.get().asFile
    if (!releaseNotes.isFile) {
      throw GradleException(
        "Missing release notes at ${releaseNotes.absolutePath}. " +
          "Create the Play Console TXT before exporting."
      )
    }

    val releaseNotesText = releaseNotes.readText().trim()
    if (!releaseNotesText.contains("<ko-KR>") || !releaseNotesText.contains("<en-US>")) {
      throw GradleException(
        "Release notes must contain <ko-KR> and <en-US> blocks (Play Console BCP-47 locale tags): ${releaseNotes.absolutePath}"
      )
    }

    val baseName = "ClearPDFLocal-v${versionName.get()}-vc${versionCode.get()}"
    val aabTarget = File(targetDir, "$baseName.aab")
    val txtTarget = File(targetDir, "$baseName-release-notes.txt")

    aab.copyTo(aabTarget, overwrite = true)
    txtTarget.writeText(releaseNotesText + System.lineSeparator())

    logger.lifecycle("Wrote ${aabTarget.absolutePath} (${aabTarget.length()} bytes)")
    logger.lifecycle("Wrote ${txtTarget.absolutePath} (${txtTarget.length()} bytes)")
  }
}

// Brand-defense gate. ClearPDF Local's core promise is that documents physically cannot
// leave the device, enforced by NEVER declaring android.permission.INTERNET. A future
// dependency (ML Kit, Play Services, an analytics/crash SDK) could pull INTERNET in
// transitively without anyone noticing. This task inspects the merged manifest and fails
// the build if INTERNET ever appears, so the offline brand is protected by the build, not
// by code review.
abstract class CheckNoInternetPermissionTask : DefaultTask() {
  @get:InputFile
  abstract val mergedManifest: RegularFileProperty

  @TaskAction
  fun check() {
    val manifest = mergedManifest.get().asFile
    if (manifest.readText().contains("android.permission.INTERNET")) {
      throw GradleException(
        "BRAND VIOLATION: android.permission.INTERNET was found in the merged manifest " +
          "(${manifest.absolutePath}).\n" +
          "ClearPDF Local must stay provably offline and never declares INTERNET. A dependency " +
          "most likely pulled it in transitively (e.g. ML Kit, Play Services, analytics/crash SDK). " +
          "Find and remove it before building."
      )
    }
    logger.lifecycle("✓ No INTERNET permission in the merged manifest — offline brand intact.")
  }
}

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.jeiel85.clearpdflocal"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.jeiel85.clearpdflocal"
    minSdk = 24
    targetSdk = 36
    versionCode = 7
    versionName = "1.5.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // OpenCV ships prebuilt native libs for every ABI. Limit the packaged ABIs to the ones
    // real phones use so the universal GitHub APK stays lean; the Play AAB still splits per ABI.
    ndk {
      abiFilters += listOf("armeabi-v7a", "arm64-v8a")
    }
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/.keystore/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD") ?: "android"
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD") ?: "android"
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

val exportVersionName = android.defaultConfig.versionName
  ?: throw GradleException("versionName is not set in defaultConfig")
val exportVersionCode = android.defaultConfig.versionCode
  ?: throw GradleException("versionCode is not set in defaultConfig")
val exportReleaseAab = layout.buildDirectory.file("outputs/bundle/release/app-release.aab")
val exportReleaseNotes = rootProject.layout.projectDirectory.file("store-graphics/play-console-current/release-notes.txt")

tasks.register<ExportReleaseToDesktopTask>("exportReleaseToDesktop") {
  group = "clearpdf"
  description = "Copies the release AAB and Play Console release notes to the user's Desktop/Build directory"

  dependsOn("bundleRelease")
  versionName.set(exportVersionName)
  versionCode.set(exportVersionCode)
  aabFile.set(exportReleaseAab)
  releaseNotesFile.set(exportReleaseNotes)
}

// Wire the no-INTERNET gate into every variant's assemble/bundle so no APK or AAB can be
// produced with an INTERNET permission. Consuming SingleArtifact.MERGED_MANIFEST makes AGP
// run manifest merging first automatically.
androidComponents {
  onVariants { variant ->
    val capName = variant.name.replaceFirstChar { it.uppercase() }
    val checkTask = tasks.register<CheckNoInternetPermissionTask>("check${capName}NoInternetPermission") {
      group = "verification"
      description = "Fails the build if INTERNET permission is in the merged manifest ($capName)."
      mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
    }
    listOf("assemble$capName", "bundle$capName").forEach { taskName ->
      tasks.matching { it.name == taskName }.configureEach { dependsOn(checkTask) }
    }
  }
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.opencv)
  implementation(libs.tesseract4android)
  implementation(libs.pdfbox.android)
  implementation(libs.onnxruntime.android)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}
