import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/* Las claves nunca se escriben en el código. Se leen de local.properties
   (que está en .gitignore):

     lucy.apiKey=...                  Lucy (modo demo si falta)
     screenscraper.devId=...          credenciales de desarrollador de ScreenScraper
     screenscraper.devPassword=...    (se piden en el foro de screenscraper.fr)
     screenscraper.softname=Elyndra   nombre de software registrado (opcional)

   Las credenciales de usuario de cada servicio (ScreenScraper, IGDB,
   SteamGridDB, RetroAchievements) se introducen dentro de la app, en Ajustes,
   y se guardan cifradas con una clave del Android Keystore. */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun localProp(name: String): String = localProps.getProperty(name, "").trim()

fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.elyndra.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.elyndra.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
        buildConfigField("String", "LUCY_API_KEY", quoted(localProp("lucy.apiKey")))
        buildConfigField("String", "SS_DEV_ID", quoted(localProp("screenscraper.devId")))
        buildConfigField("String", "SS_DEV_PASSWORD", quoted(localProp("screenscraper.devPassword")))
        buildConfigField("String", "SS_SOFTNAME", quoted(localProp("screenscraper.softname").ifEmpty { "Elyndra" }))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.ui.text.google.fonts)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)

    // Fondo animado de la interfaz (Ajustes → "Vídeo de fondo").
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
