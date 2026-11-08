// Genera el perfil base de :app y mide su arranque (docs/RELEASING.md).
// Es un módulo de pruebas instrumentadas: no entra en el APK ni en testDebugUnitTest.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.baselineprofile)
}

android {
    // El id del APK de pruebas sale del namespace. Lleva ".xts" porque Hiber, el
    // congelador de Tecno/Infinix/itel, congela cualquier app sin ventana (como el
    // ejecutor de pruebas) a los 5 s y solo respeta sus listas internas, que
    // excluyen los ids que contienen ".cts"/".gts"/".vts"/".xts". Sin esto el
    // generador se queda parado en el Tecno. El código sigue en
    // com.elyndra.launcher.baselineprofile; este APK nunca se publica.
    namespace = "com.elyndra.launcher.baselineprofile.xts"
    compileSdk = 35

    defaultConfig {
        // Generar un perfil base sin root pide Android 9 (API 28).
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

baselineProfile {
    // El móvil conectado por adb (el Tecno), no un dispositivo gestionado por Gradle.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
