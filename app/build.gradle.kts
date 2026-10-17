import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing secrets live in an untracked keystore.properties (see keystore.properties.example).
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "app.twistcubee"
    compileSdk = 36

    defaultConfig {
        // Derived from the developer's GitHub domain (zk-haunt.github.io), as F-Droid recommends.
        // The Kotlin package (namespace) stays app.twistcubee; the two are allowed to differ.
        applicationId = "io.github.zk_haunt.twistcubee"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Reproducible builds (F-Droid): don't embed the git commit hash in the APK, so a build of
            // the same source is byte-identical no matter when or where it was made.
            vcsInfo.include = false
            // Without keystore.properties the release APK is left unsigned (this is what F-Droid
            // expects: it signs builds itself). Use installDebug for local testing without a key.
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // No opaque, Google-only dependency-metadata blob in the APK/AAB (F-Droid requirement; privacy).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
