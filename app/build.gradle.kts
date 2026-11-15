plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("BUILD_NUMBER") ?: "1").toInt()

android {
    namespace = "com.miller.tpics"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.miller.tpics"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "2.0.$buildNumber"

        // Identificadores públicos del proyecto Firebase (no son secretos).
        buildConfigField("String", "FB_API_KEY", "\"AIzaSyCmAGIaguvPdIW1XHdK4dvyvcFG_cBW7Fk\"")
        buildConfigField("String", "DB_URL", "\"https://t-pics-clientes-default-rtdb.firebaseio.com\"")
        buildConfigField("String", "LEGACY_PERSONAL_DB", "\"https://mi-respaldo-fotos-default-rtdb.firebaseio.com\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "../signing/tpics.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "tpics-app"
            keyAlias = System.getenv("KEY_ALIAS") ?: "tpics"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "tpics-app"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-video:2.6.0")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("net.engawapg.lib:zoomable:1.6.1")
}
