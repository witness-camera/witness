import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Upload-signing credentials, kept out of the repository.
 *
 * Play requires every uploaded build to be signed. The key and its passwords
 * live in `keystore.properties` (gitignored) rather than here, so the signing
 * material is never committed and never passes through the build script in
 * plain text. Absent - on a machine that only builds debug - the release build
 * simply goes unsigned rather than failing.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasUploadKey = keystoreProperties.containsKey("storeFile")

/**
 * Public app identifiers (OAuth client IDs) live in `local.properties`, which
 * is gitignored, so a fork of this code registers and uses its own rather than
 * Witness's. They are not secrets: they ship inside every APK. See
 * `local.properties.example`.
 */
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val dropboxAppKey: String = localProperties.getProperty("DROPBOX_APP_KEY", "")

android {
    namespace = "com.witnessrecord.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.witnessrecord.app"
        // Android 11. Every one of the app's defining behaviours needs API 30:
        // the camera/microphone/location foreground-service types that keep a
        // recording alive with the screen off, `getConcurrentCameraIds` for
        // dual-camera, and `CONTROL_ZOOM_RATIO` for the gapless 0.5x/1x lens
        // crossover. On API 29 those would all silently degrade, so the floor
        // is set where the app actually works.
        minSdk = 30
        // Play requires new bundles to target the previous year's API level.
        // 36 is Android 16.
        targetSdk = 36
        // versionName is kept in step with the iOS build: the two apps ship
        // the same features under the same names, so a bug report naming a
        // version should mean one thing rather than two.
        //
        // 1.1.5, not 1.1.4: iOS 1.1.4 was approved as build 18, and the week
        // of work in build 19 - the stand-in deletion race, the Nextcloud
        // login flow, WebDAV endpoint discovery, the capture-orientation and
        // viewfinder decisions, the cloud-upload toggle - ships as 1.1.5 on
        // both platforms. Calling this build 1.1.4 would give that name two
        // different meanings.
        //
        // versionCode is Play's own counter and does NOT track the iOS build
        // number. Play burns a code permanently the moment a bundle carrying
        // it is uploaded - even to a draft that was never rolled out, and even
        // if that draft is discarded - so this only ever moves forward. It
        // started at 19 to match iOS and has drifted since; that is expected.
        versionCode = 32
        versionName = "1.1.13"

        resourceConfigurations += listOf("en", "it", "es", "fr", "de")
        buildConfigField("String", "DROPBOX_APP_KEY", "\"$dropboxAppKey\"")
    }

    signingConfigs {
        if (hasUploadKey) {
            create("upload") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (hasUploadKey) signingConfig = signingConfigs.getByName("upload")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            // Lets a debug build sit beside the Play Store install of the
            // same app - which a plain debug build cannot replace, being
            // signed with a different key - for testing without wiping the
            // real one: ./gradlew installDebug -PwitnessTestSuffix=.test
            // (Drive sign-in and billing are tied to the real package, so
            // those cannot be tested this way.)
            providers.gradleProperty("witnessTestSuffix").orNull?.let { applicationIdSuffix = it }
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // On-device face and text detection for the blurred, publish-safe copy.
    // The bundled models, so nothing is downloaded and it works on a phone
    // with no Google Play services at all.
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // The crew's invite code is a QR: drawn here, read by Google's code
    // scanner (which needs no camera permission of its own).
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // The crew editor: plays a crew's angles one at a time and renders the cut
    // as one video (Transformer), all on the phone.
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    testImplementation("junit:junit:4.13.2")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.browser)
    implementation(libs.billing.ktx)
    implementation(libs.play.review)
}
