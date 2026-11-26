plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Versi diisi oleh workflow release (-PversionName / -PversionCode).
val appVersionName = (findProperty("versionName") as String?) ?: "0.0.0-dev"
val appVersionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1

// Signing release dibaca dari environment (diisi GitHub Actions dari secret).
val keystorePath: String? = System.getenv("ANDROID_KEYSTORE_PATH")

android {
    namespace = "io.github.onecdx.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.onecdx.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        resValue("string", "app_name", "1cdx")
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Bisa terpasang berdampingan dengan versi rilis
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "1cdx debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    testOptions {
        // tm4e/sora memanggil android.util.Log; di unit test JVM kembalikan nilai default.
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        viewBinding = true
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // JSch & BouncyCastle: multi-release jar dengan manifest OSGi per versi Java (bentrok saat dipaketkan)
            excludes += "/META-INF/versions/*/OSGI-INF/MANIFEST.MF"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.sora.bom))
    implementation(libs.sora.editor)
    implementation(libs.sora.textmate)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jsch)
    // JSch memakai jalur BouncyCastle untuk ed25519/curve25519/ML-KEM di Android
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.gson)

    testImplementation(libs.junit)
    // Server SSH/SFTP sungguhan di JVM untuk menguji SftpConnection & SftpWorkspaceRepository di CI
    testImplementation(libs.sshd.core)
    testImplementation(libs.sshd.sftp)
    testImplementation(libs.eddsa)
    testImplementation(libs.slf4j.nop)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
