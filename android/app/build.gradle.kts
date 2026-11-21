import java.util.Properties
import java.time.Instant
import java.time.temporal.ChronoUnit

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.gms.google.services)
}

// Datele private (URL-ul bazei, contul Firebase) se citesc din secrete.properties —
// fisier negit-uit; pentru un clone proaspat se copiaza secrete.properties.example.
val secrete = Properties().apply {
    val fisier = rootProject.file("secrete.properties")
    if (fisier.exists()) fisier.inputStream().use { load(it) }
}
fun secret(cheie: String): String = secrete.getProperty(cheie, "")

android {
    namespace = "com.example.smartpurifier"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.smartpurifier"
        minSdk = 26
        targetSdk = 36
        // Incrementam codul si versiunea la fiecare livrare modificata.
        versionCode = 5
        versionName = "2026.10.04.1"
        buildConfigField("String", "BUILD_TIME_UTC", "\"${Instant.now().truncatedTo(ChronoUnit.SECONDS)}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "DB_URL", "\"${secret("smartair.dbUrl")}\"")
        buildConfigField("String", "FB_EMAIL", "\"${secret("smartair.fbEmail")}\"")
        buildConfigField("String", "FB_PASS", "\"${secret("smartair.fbPass")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.firebase.database)
    implementation(libs.firebase.auth.ktx)
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
