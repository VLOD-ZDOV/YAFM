plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.yafm.filemanager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yafm.filemanager"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Release signing comes from ~/.gradle/gradle.properties (YAFM_STORE_FILE, YAFM_STORE_PASSWORD,
    // YAFM_KEY_ALIAS); without them the release build is left unsigned.
    val releaseStore = providers.gradleProperty("YAFM_STORE_FILE").orNull
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = providers.gradleProperty("YAFM_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("YAFM_KEY_ALIAS").get()
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // R8: less code to load and keep in memory, and faster code (Compose gains the most).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    sourceSets {
        // jniLibs: the fetched qsh binary (not in git); jniScripts: the askpass script qsh runs (in git).
        getByName("main").jniLibs.srcDirs("src/main/jniLibs", "src/main/jniScripts")
    }
    packaging {
        // qsh runs as a program from the native library dir, so libqsh.so must be extracted there.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    // libVLC pulls an old Fragment, with which the Activity Result API misbehaves.
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Image previews
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("io.coil-kt:coil-video:2.7.0")

    // Video player. 3.7.3+ need compileSdk 37 and a newer Gradle plugin.
    implementation("org.videolan.android:libvlc-all:3.7.2")

    // Audio player with background playback. 1.10+ need compileSdk 36.
    implementation("androidx.media3:media3-exoplayer:1.9.3")
    implementation("androidx.media3:media3-session:1.9.3")

    // Archives (ZIP, TAR, GZ, BZ2, XZ, 7Z)
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("com.github.luben:zstd-jni:1.5.7-12@aar")
    implementation("org.brotli:dec:0.1.2")

    // Network protocols
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("com.github.mwiede:jsch:0.2.18")
    implementation("commons-net:commons-net:3.10.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    // Desktop natives for zstd in JVM tests (the app uses the Android aar).
    testImplementation("com.github.luben:zstd-jni:1.5.7-12")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
