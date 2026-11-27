plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
 id("com.google.gms.google-services")
}
val streamforgeBuildNumber = providers.gradleProperty("streamforgeBuildNumber").orElse("1073").get().toInt()
android {
 namespace = "app.streamforge"
 compileSdk = 35
 defaultConfig {
  applicationId = "com.castdoo.app"
  minSdk = 26
  targetSdk = 35
  versionCode = streamforgeBuildNumber
  versionName = "Alpha build $streamforgeBuildNumber"
 }
 flavorDimensions += "track"
 productFlavors {
  create("stable") {
   dimension = "track"
   buildConfigField("String", "APP_TRACK", "\"stable\"")
  }
  create("beta") {
   dimension = "track"
   versionNameSuffix = "-beta"
   buildConfigField("String", "APP_TRACK", "\"beta\"")
  }
 }
 signingConfigs {
  getByName("debug") {
   storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
   storePassword = "android"
   keyAlias = "androiddebugkey"
   keyPassword = "android"
  }
 }
 buildTypes {
  getByName("debug") { signingConfig = signingConfigs.getByName("debug") }
  getByName("release") {
   isMinifyEnabled = true
   isShrinkResources = true
   proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
  }
 }
 splits {
  abi {
   isEnable = true
   reset()
   include("arm64-v8a")
   isUniversalApk = false
  }
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
 buildFeatures {
  compose = true
  buildConfig = true
 }
}
dependencies {
 val composeBom = platform("androidx.compose:compose-bom:2025.03.00")
 implementation(composeBom)
 androidTestImplementation(composeBom)
 implementation("androidx.activity:activity-compose:1.10.1")
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("androidx.camera:camera-camera2:1.4.2")
 implementation("androidx.camera:camera-lifecycle:1.4.2")
 implementation("androidx.camera:camera-view:1.4.2")
 implementation("androidx.media3:media3-exoplayer:1.5.1")
 implementation("androidx.media3:media3-ui:1.5.1")
 implementation("androidx.media3:media3-transformer:1.5.1")
 implementation("com.google.guava:guava:33.3.1-android")
 implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.ui:ui-tooling-preview")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 debugImplementation("androidx.compose.ui:ui-tooling")
 implementation("com.github.pedroSG94.RootEncoder:library:2.8.1")
 implementation("com.google.android.gms:play-services-ads:25.5.0")
 implementation("com.google.android.gms:play-services-auth:21.6.0")
 implementation(platform("com.google.firebase:firebase-bom:35.2.0"))
 implementation("com.google.firebase:firebase-auth")
 implementation("com.google.firebase:firebase-firestore")
 implementation("com.google.firebase:firebase-messaging")
 implementation("com.google.firebase:firebase-functions")
 implementation("io.github.webrtc-sdk:android:144.7559.15")
 implementation("com.squareup.okhttp3:okhttp:4.11.0")
}
