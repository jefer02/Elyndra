import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/* Las claves nunca se escriben en el código. Se leen de local.properties
   (que está en .gitignore):

     masha.apiKey=...                 Masha (DeepSeek); sin clave, Masha funciona sin conexión
     masha.model=deepseek-chat        modelo de chat (opcional)
     masha.baseUrl=https://api.deepseek.com   (opcional)
     screenscraper.devId=...          credenciales de desarrollador de ScreenScraper
     screenscraper.devPassword=...    (se piden en el foro de screenscraper.fr)
     screenscraper.softname=Elyndra   nombre de software registrado (opcional)

   Las credenciales de usuario de cada servicio (ScreenScraper, IGDB,
   SteamGridDB, RetroAchievements) se introducen dentro de la app, en Ajustes,
   y se guardan cifradas con una clave del Android Keystore. La clave de Masha
   también se puede sustituir desde Ajustes, con el mismo cifrado. */
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
        versionCode = 3
        versionName = "2.0"
        buildConfigField("String", "MASHA_API_KEY", quoted(localProp("masha.apiKey")))
        buildConfigField("String", "MASHA_MODEL", quoted(localProp("masha.model").ifEmpty { "deepseek-chat" }))
        buildConfigField("String", "MASHA_BASE_URL", quoted(localProp("masha.baseUrl").ifEmpty { "https://api.deepseek.com" }))
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

ksp {
    // El esquema de cada versión de la base de datos se versiona en app/schemas:
    // es lo que permite escribir (y probar) las migraciones de Room.
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
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

    // Inyección de dependencias.
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Biblioteca, metadatos, sesiones y memoria de Masha.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Trabajo en segundo plano: metadatos automáticos, widget y avisos de Masha.
    implementation(libs.androidx.work.runtime.ktx)

    // Widget de la pantalla de inicio.
    implementation(libs.androidx.glance.appwidget)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
