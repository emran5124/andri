import java.util.Base64

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.txtsummarizer.kjpqxw"
    minSdk = 24
    targetSdk = 36
    versionCode = 27
    versionName = "1.0.26"

    // Dynamic version override to ensure every single local or GitHub build increments automatically
    val dynamicVersionCode = System.getenv("APP_VERSION_CODE")?.toIntOrNull() 
        ?: (System.currentTimeMillis() / 100000).toInt()
    val dynamicVersionName = System.getenv("APP_VERSION_NAME") 
        ?: "1.0.$dynamicVersionCode"

    versionCode = dynamicVersionCode
    versionName = dynamicVersionName

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Automatically decode debug.keystore from debug.keystore.base64 in project or workspace directories
  val possibleBase64Files = listOf(
    file("${rootDir}/debug.keystore.base64"),
    file("${projectDir}/../debug.keystore.base64"),
    file("../debug.keystore.base64")
  )
  val base64File = possibleBase64Files.firstOrNull { it.exists() }

  if (base64File != null) {
    val targetKeystores = listOf(
      file("${rootDir}/debug.keystore"),
      file("${projectDir}/debug.keystore"),
      file("debug.keystore")
    )
    for (targetFile in targetKeystores) {
      if (!targetFile.exists()) {
        try {
          val base64Content = base64File.readText().replace("\\s".toRegex(), "")
          val decodedBytes = Base64.getDecoder().decode(base64Content)
          targetFile.writeBytes(decodedBytes)
          println("Generated debug.keystore from base64 at path: ${targetFile.absolutePath}")
        } catch (e: Exception) {
          println("Failed to decode debug.keystore to ${targetFile.absolutePath}: ${e.message}")
        }
      }
    }
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      val keystorePaths = listOf(
        file("${rootDir}/debug.keystore"),
        file("${projectDir}/debug.keystore"),
        file("debug.keystore")
      )
      val keystoreFile = keystorePaths.firstOrNull { it.exists() } ?: file("${rootDir}/debug.keystore")
      storeFile = keystoreFile
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
      val keystorePaths = listOf(
        file("${rootDir}/debug.keystore"),
        file("${projectDir}/debug.keystore"),
        file("debug.keystore")
      )
      if (keystorePaths.any { it.exists() }) {
        signingConfig = signingConfigs.getByName("debugConfig")
      }
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

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  // implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
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
  "ksp"(libs.moshi.kotlin.codegen)
}
