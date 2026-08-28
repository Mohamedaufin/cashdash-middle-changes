import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("kotlin-kapt")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.cash.dash"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cash.dash"
        minSdk = 26
        targetSdk = 36
        versionCode = 24
        versionName = "0.5.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // The keystore path comes from local.properties (KEYSTORE_FILE), which is
            // gitignored, so the release signing key can live outside the repository.
            // Keeping it in the repo root meant a single `git add -f`, or zipping the
            // project folder, would leak the key that signs every release.
            // Falls back to the old in-repo path so a checkout without the property set
            // still builds rather than failing confusingly.
            val keystorePath = localProperties.getProperty("KEYSTORE_FILE")
            storeFile = if (!keystorePath.isNullOrBlank()) file(keystorePath) else file("../keydash.jks")
            storePassword = localProperties.getProperty("KEYSTORE_PASSWORD", "")
            keyAlias = localProperties.getProperty("KEY_ALIAS", "")
            keyPassword = localProperties.getProperty("KEY_PASSWORD", "")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
        // Required for BuildConfig.DEBUG (App Check provider selection).
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-common-java8:2.8.7")

    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    // Google Sign-In. Credential Manager is the supported path -- the GoogleSignInClient
    // API in play-services-auth is deprecated. serverClientId comes from
    // R.string.default_web_client_id, which the google-services plugin generates from the
    // type 3 OAuth client in google-services.json.
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:34.9.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.firebase:firebase-storage")
    // Callable functions: keeps the Gemini keys and the reply-link signing key server-side.
    implementation("com.google.firebase:firebase-functions")
    // App Check: without it, anyone holding the (publicly shipped) API key can talk
    // to Firestore/Auth/Storage directly and bypass every client-side control.
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    implementation("com.google.firebase:firebase-appcheck-debug")

    implementation("com.airbnb.android:lottie:6.1.0")

    // Image Loading
    implementation("com.github.bumptech.glide:glide:4.16.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")
    implementation("com.github.chrisbanes:PhotoView:2.3.0")

    // Encrypted prefs for the cached admin-permission blob (AdminManager).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    
    // WorkManager for delayed tasks
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    // NOTE: the Gemini SDK dependency was removed. Rephrasing now goes through the
    // `rephraseSupportText` Cloud Function so the API keys never ship in the APK.
}
