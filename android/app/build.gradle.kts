plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("dev.flutter.flutter-gradle-plugin")
}
// Android otherwise appends each variant to its module prefix, changing internal JVM names.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.moduleName.set("fetchy")
}
android {
    namespace = "com.luverse.fetchy"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = "28.2.13676358"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    defaultConfig {
        applicationId = "com.luverse.fetchy"
        minSdk = 24
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        if (project.findProperty("split-per-abi")?.toString() != "true") {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    packaging { jniLibs { useLegacyPackaging = true; pickFirsts += "**/libffmpeg.zip.so"; keepDebugSymbols += "**/*.zip.so"; excludes += listOf("**/armeabi-v7a/**", "**/x86/**") } }
    buildTypes {
        release {
            // Local install artifact. Use a Fetchy-owned upload key for distribution.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
}
flutter { source = "../.." }
dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("androidx.room:room-runtime:2.7.2")
    kapt("androidx.room:room-compiler:2.7.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.core:core-ktx:1.16.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
