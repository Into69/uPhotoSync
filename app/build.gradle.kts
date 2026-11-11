import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.unifiphotosync.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.unifiphotosync.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2"
    }

    // Release signing key lives outside the repo; keystore.properties (git-ignored) points to it.
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }
    }
    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // R8: strips unused code (most of the Material icon set, unused BouncyCastle) and resources.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    // uPhotoSync.apk / uPhotoSync-debug.apk instead of app-<buildType>.apk.
    applicationVariants.all {
        val suffix = if (buildType.name == "release") "" else "-${buildType.name}"
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "uPhotoSync$suffix.apk"
        }
    }
    packaging {
        resources {
            // smbj / BouncyCastle ship overlapping metadata files.
            excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Thumbnails for device media and NAS files.
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")

    // SMB2/3 client for the UniFi NAS share. BouncyCastle supplies MD4/RC4 for NTLM,
    // which Android's built-in crypto provider lacks.
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // SRVSVC RPC over SMB, used only to list the NAS's shares for the share picker.
    implementation("com.rapid7.client:dcerpc:0.12.13")

    testImplementation("junit:junit:4.13.2")
}
