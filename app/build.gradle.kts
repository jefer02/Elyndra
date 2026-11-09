import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // Perfil base: compila de antemano el arranque y la biblioteca (ver :baselineprofile).
    alias(libs.plugins.baselineprofile)
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

// Los dos tipos de compilación que usa el perfil base (:baselineprofile). Nunca se publican.
val BENCHMARK_BUILD_TYPES = listOf("nonMinifiedRelease", "benchmarkRelease")

android {
    namespace = "com.elyndra.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.elyndra.launcher"
        minSdk = 26
        targetSdk = 35
        // versionName = la etiqueta de la release sin la "v" (v1.0.0 → "1.0.0", v1.1.0-beta → "1.1.0-beta"):
        // el actualizador compara las dos como semver. versionCode sube siempre en 1 por
        // release y nunca baja (Android no instala por encima una versión menor).
        // Pasos para publicar: docs/RELEASING.md.
        versionCode = 7
        versionName = "1.2.0"
        buildConfigField("String", "MASHA_API_KEY", quoted(localProp("masha.apiKey")))
        buildConfigField("String", "MASHA_MODEL", quoted(localProp("masha.model").ifEmpty { "deepseek-chat" }))
        buildConfigField("String", "MASHA_BASE_URL", quoted(localProp("masha.baseUrl").ifEmpty { "https://api.deepseek.com" }))
        buildConfigField("String", "SS_DEV_ID", quoted(localProp("screenscraper.devId")))
        buildConfigField("String", "SS_DEV_PASSWORD", quoted(localProp("screenscraper.devPassword")))
        buildConfigField("String", "SS_SOFTNAME", quoted(localProp("screenscraper.softname").ifEmpty { "Elyndra" }))
        // Claves de desarrollador de SteamGridDB e IGDB (Twitch): arte de serie sin configurar nada.
        // Las del usuario (Ajustes → APIs) mandan sobre estas. Vacías = esa fuente no se usa.
        buildConfigField("String", "SGDB_API_KEY", quoted(localProp("sgdb.apiKey")))
        buildConfigField("String", "IGDB_CLIENT_ID", quoted(localProp("igdb.clientId")))
        buildConfigField("String", "IGDB_CLIENT_SECRET", quoted(localProp("igdb.clientSecret")))
        // Solo es true en las variantes del perfil base (ver buildTypes). En debug y release es una
        // constante false: R8 quita lo que cuelga de ella y la release se comporta igual que antes.
        buildConfigField("boolean", "BENCHMARK", "false")
        // Nombre en el lanzador (el manifiesto lo lee de aquí): el de siempre salvo en las variantes del perfil base.
        manifestPlaceholders["appLabel"] = "@string/app_name"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Las variantes del perfil base (nonMinifiedRelease: genera el perfil; benchmarkRelease:
        // mide) se crean aquí a mano en vez de dejar que las cree el plugin: el plugin hace
        // create() + initWith(release), y ese initWith pisaría el sufijo, la etiqueta y la marca
        // de abajo. Si ya existen, el plugin solo les pone lo suyo (no depurables, profileable,
        // nonMinifiedRelease sin R8) y les añade los directorios de src/release.
        //
        // Otro applicationId (com.elyndra.launcher.benchmark): se instalan al lado de la app de
        // verdad (la firmada de release o la de debug), no la sustituyen, no la desinstalan y no
        // ven sus datos. Van firmadas con la clave de debug (release aquí no lleva firma, se
        // firma al publicar: docs/RELEASING.md) y nunca se publican.
        BENCHMARK_BUILD_TYPES.forEach { name ->
            create(name) {
                initWith(getByName("release"))
                applicationIdSuffix = ".benchmark"
                signingConfig = signingConfigs.getByName("debug")
                // Macrobenchmark lee las trazas y los fotogramas de la app: sin esto (y sin ser
                // depurable, que falsearía las medidas) no puede en benchmarkRelease.
                isProfileable = true
                // "Elyndra Bench" en el lanzador, en cualquier idioma, para no confundirla.
                manifestPlaceholders["appLabel"] = "Elyndra Bench"
                // La biblioteca de prueba y lo que se apaga para que las pasadas sean iguales (src/benchmark).
                buildConfigField("boolean", "BENCHMARK", "true")
            }
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

    // El modelo y el entorno de Masha se leen enteros a memoria: sin comprimir
    // en el APK se cargan sin descomprimir (y el Ogg ya viene comprimido).
    // La voz natural solo se usa en procesos de 64 bits (NeuralRuntime.hardwareOk): el ONNX
    // Runtime de 32 bits serían ~55 MB muertos en el APK universal.
    // Con -Pelyndra.releaseApks una sola compilación de release saca los dos APK que se publican:
    // el de arm64-v8a y el universal (docs/RELEASING.md). Sin la propiedad, nada cambia.
    // No usar android.injected.build.abi para publicar: va con invoked.from.ide, que marca
    // el APK como testOnly y Android lo rechaza fuera de adb (también el actualizador).
    splits {
        abi {
            isEnable = project.hasProperty("elyndra.releaseApks")
            reset()
            include("arm64-v8a")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            excludes += listOf("lib/armeabi-v7a/libonnxruntime*.so", "lib/x86/libonnxruntime*.so")
        }
    }

    androidResources {
        noCompress += listOf("glb", "hdr")
    }
}

/*
 * La biblioteca de prueba del perfil base (com.elyndra.launcher.bench):
 *   src/benchmark    lo de verdad: solo nonMinifiedRelease y benchmarkRelease
 *   src/noBenchmark  el stub vacío: debug y release
 * Mismo patrón que UpdateDebug (implementación y stub por variante), pero el stub no puede ir
 * en src/release: el plugin del perfil base copia los directorios de release a las dos variantes
 * suyas (copyBuildTypeSources, en su finalizeDsl) y habría dos BenchmarkHooks. Este finalizeDsl
 * corre después del suyo (el plugin se aplica antes, en plugins {}), así que src/noBenchmark
 * llega a release cuando la copia ya está hecha. Si el orden cambiara, la compilación de las
 * variantes del perfil base fallaría con "Redeclaration: BenchmarkHooks", nunca en silencio.
 */
androidComponents {
    finalizeDsl { android ->
        android.sourceSets.getByName("debug").java.srcDir("src/noBenchmark/java")
        android.sourceSets.getByName("release").java.srcDir("src/noBenchmark/java")
        BENCHMARK_BUILD_TYPES.forEach { android.sourceSets.getByName(it).java.srcDir("src/benchmark/java") }
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
    implementation(libs.androidx.core.splashscreen)
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

    // Fondo animado de la interfaz (Ajustes → "Vídeo de fondo") y ambiente sonoro de Masha.
    implementation(libs.androidx.media3.exoplayer)
    // Traducción de descripciones en el dispositivo, a demanda (modelos ~30 MB por idioma).
    implementation(libs.mlkit.translate)
    implementation(libs.androidx.media3.ui)

    // Masha en 3D: SceneView (Compose) sobre Filament.
    implementation(libs.sceneview)

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

    // Voz neuronal de Masha (Supertonic): ONNX Runtime (MIT); la versión JVM solo para tests.
    // 1.28.0 fijo: desde 1.29 el AAR añade un proveedor de telemetría (TelemetryInitializer) que arranca con la app.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")

    // Instala el perfil base en el primer arranque también fuera de Play Store.
    implementation(libs.androidx.profileinstaller)
    // De dónde sale el perfil (generateReleaseBaselineProfile). Sin perfil generado todo compila igual.
    baselineProfile(project(":baselineprofile"))
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.28.0")
}

baselineProfile {
    // El perfil se genera a mano con un móvil conectado (docs/RELEASING.md) y se versiona en
    // src/release/generated/baselineProfiles; compilar la release no lo regenera.
    automaticGenerationDuringBuild = false
    saveInSrc = true
    // startup-prof.txt ordena el dex: R8 pone en classes.dex solo lo que usa el arranque, así la
    // primera lectura del APK es más corta. AGP 8.7 ya lo hace por defecto con R8 (pide AGP 8.2+ y
    // minify en la release); se deja escrito para no depender de ese valor por defecto.
    dexLayoutOptimization = true
    // La biblioteca de prueba (src/benchmark) solo existe en las variantes del perfil base: sus
    // reglas no tienen clase que compilar en la release. El generador ya las quita de los dos
    // ficheros (filterPredicate en BaselineProfileGenerator); esto es la segunda red, y solo cubre
    // baseline-prof.txt (el plugin 1.3.3 no filtra startup-prof.txt).
    filter { exclude("com.elyndra.launcher.bench.**") }
}

/*
 * Antes de publicar: comprueba que la release que hay en build/outputs/apk/release
 * lleva el perfil base y nada de la biblioteca de prueba (docs/RELEASING.md).
 *
 *   ./gradlew :app:assembleRelease -Pelyndra.releaseApks -Pandroid.injected.signing.… :app:verifyReleaseBaselineProfile
 *
 * No compila nada: mira lo que dejó la última assembleRelease (mustRunAfter), firmada o no.
 */
tasks.register("verifyReleaseBaselineProfile") {
    group = "verification"
    description = "Checks that the release APKs carry the Baseline Profile and no benchmark fixtures."
    mustRunAfter("assembleRelease")
    val apkDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
    val srcProfiles = layout.projectDirectory.dir("src/release/generated/baselineProfiles").asFile
    val mergedArt = layout.buildDirectory.dir("intermediates/merged_art_profile/release").get().asFile
    val mergedStartup = layout.buildDirectory.dir("intermediates/merged_startup_profile/release").get().asFile
    val mapping = layout.buildDirectory.file("outputs/mapping/release/mapping.txt").get().asFile
    val manifest = layout.buildDirectory.file("intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml").get().asFile
    val published = rootProject.layout.projectDirectory.dir("release-apk").asFile
    doLast {
        val problems = mutableListOf<String>()
        val appRule = "Lcom/elyndra/launcher/"
        val fixtureRule = "Lcom/elyndra/launcher/bench/"

        // 1. El perfil generado en el móvil, versionado en src/release.
        for (name in listOf("baseline-prof.txt", "startup-prof.txt")) {
            val f = File(srcProfiles, name)
            when {
                !f.isFile -> problems += "missing ${f.relativeTo(rootDir)}: run :app:generateReleaseBaselineProfile with the phone connected"
                f.useLines { lines -> lines.none { it.contains(appRule) } } -> problems += "${f.name} has no Elyndra rules"
                f.useLines { lines -> lines.any { it.contains(fixtureRule) } } -> problems += "${f.name} still has benchmark fixture rules"
            }
        }

        // 2. Lo que se compiló: el perfil fusionado (antes de R8) con reglas de la app, y el de arranque que usó R8.
        val art = mergedArt.walkTopDown().filter { it.isFile && it.name.endsWith(".txt") }.toList()
        if (art.none { f -> f.useLines { lines -> lines.any { it.contains("${appRule}MainActivity;") } } }) {
            problems += "the merged release ART profile has no Elyndra rules (was assembleRelease run after generating?)"
        }
        val startup = mergedStartup.walkTopDown().filter { it.isFile && it.length() > 0 }.toList()
        if (startup.isEmpty()) problems += "no merged startup profile for R8 (DEX layout optimization did not get startup-prof.txt)"

        // 3. Los APK: el perfil binario dentro y nada de la biblioteca de prueba.
        val apks = apkDir.listFiles { f -> f.extension == "apk" }.orEmpty().sortedBy { it.name }
        if (apks.isEmpty()) problems += "no APK in ${apkDir.relativeTo(rootDir)}: run :app:assembleRelease first"
        for (apk in apks) {
            ZipFile(apk).use { zip ->
                for (entry in listOf("assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm")) {
                    val e = zip.getEntry(entry)
                    if (e == null || e.size <= 0L) problems += "${apk.name}: missing $entry"
                }
                zip.entries().asSequence().map { it.name }.filter { it.contains("benchfixtures") }.forEach {
                    problems += "${apk.name}: benchmark fixture entry $it"
                }
                logger.lifecycle("${apk.name}: baseline.prof ${zip.getEntry("assets/dexopt/baseline.prof")?.size ?: 0} bytes")
            }
        }

        // 4. R8: ninguna clase de la biblioteca de prueba y el instalador del perfil con su nombre.
        if (!mapping.isFile) {
            problems += "missing ${mapping.relativeTo(rootDir)}"
        } else {
            mapping.useLines { lines ->
                var installer = false
                lines.filter { !it.startsWith(" ") }.forEach { line ->
                    if (line.startsWith("com.elyndra.launcher.bench.fixtures.")) problems += "R8 kept fixture class: ${line.substringBefore(" ->")}"
                    if (line == "androidx.profileinstaller.ProfileInstallerInitializer -> androidx.profileinstaller.ProfileInstallerInitializer:") installer = true
                }
                if (!installer) problems += "R8 removed or renamed androidx.profileinstaller.ProfileInstallerInitializer"
            }
        }

        // 5. El manifiesto: App Startup sigue llamando a ProfileInstallerInitializer (instala el perfil fuera de Play Store).
        if (!manifest.isFile || !manifest.readText().contains("androidx.profileinstaller.ProfileInstallerInitializer")) {
            problems += "the merged release manifest has no ProfileInstallerInitializer"
        }

        // 6. Lo que se va a subir: ningún APK de las variantes del perfil base en release-apk/.
        published.listFiles { f -> f.extension == "apk" }.orEmpty()
            .filter { it.name.contains("benchmark", ignoreCase = true) || it.name.contains("nonMinified", ignoreCase = true) }
            .forEach { problems += "release-apk/${it.name} is a benchmark build: never publish it" }

        if (problems.isNotEmpty()) throw GradleException("Release Baseline Profile check failed:\n - " + problems.joinToString("\n - "))
        logger.lifecycle("Release Baseline Profile check passed (${apks.size} APK).")
    }
}
