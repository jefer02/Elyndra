package com.elyndra.launcher.ui.masha

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGLContext
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import io.github.sceneview.utils.OpenGL
import com.google.android.filament.Scene as FilamentScene
import com.google.android.filament.Skybox
import com.google.android.filament.Texture
import com.google.android.filament.View
import com.google.android.filament.utils.HDRLoader
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.length
import io.github.sceneview.Scene
import io.github.sceneview.SceneView
import io.github.sceneview.environment.Environment
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.model.Model
import io.github.sceneview.math.Position
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.safeDestroyEnvironment
import io.github.sceneview.utils.readBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Calidad del holograma según el dispositivo. La alta usa el modelo completo,
 * oclusión ambiental y bloom de más niveles; la ligera, el modelo reducido y
 * resolución dinámica. Se decide una vez por RAM y clase de rendimiento.
 */
enum class MashaQuality(val model: String) {
    High("masha/masha.glb"),
    Lite("masha/masha_lite.glb"),
    ;

    companion object {
        fun detect(context: Context): MashaQuality {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (am.isLowRamDevice) return Lite
            val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
            val gb = info.totalMem / (1024.0 * 1024 * 1024)
            val perfClass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0
            return if (gb >= 5.5 || perfClass >= Build.VERSION_CODES.S) High else Lite
        }
    }
}

private const val TAG = "MashaStage"

/** En qué punto está la puesta en escena. */
enum class StageStatus { Loading, Ready, Failed }

/**
 * Encuadre de conversación: de la cintura a la cabeza, en la mitad de arriba
 * de la pantalla (la de abajo es del panel). Con zoom y órbita se la ve entera.
 */
private val TARGET = Position(0f, 1.10f, 0f)
private val HOME = Position(0f, 1.30f, 3.4f)

/**
 * Retrato: la cara de cerca (el botón de encuadre alterna entre los dos). El
 * punto de mira queda bajo la barbilla para que la cara caiga en la mitad de
 * arriba, la que no tapa el panel.
 */
private val FACE_TARGET = Position(0f, 1.52f, 0f)
private val FACE_HOME = Position(0f, 1.60f, 1.05f)

/** En horizontal la conversación va a la derecha: la cámara se desplaza para que ella quede en la mitad izquierda. */
private const val LANDSCAPE_SHIFT = 0.95f

private fun Position.shifted(landscape: Boolean, amount: Float = LANDSCAPE_SHIFT) = if (landscape) Position(x + amount, y, z) else this
private const val MIN_DISTANCE = 0.4f
private const val MAX_DISTANCE = 5.0f

/** Luz ambiental (IBL) y cielo de la sala de control, relativos a la exposición 1. */
private const val IBL_INTENSITY = 0.9f
private const val SKY_INTENSITY = 0.32f

/**
 * El escenario 3D de Masha: el holotanque en la sala de control, con luz de
 * recorte azul, relleno violeta, niebla, bloom y viñeta; ella animada en
 * tiempo real ([HoloRig]); cámara orbital con zoom y desplazamiento acotados.
 *
 * [recenter] cambia para devolver la cámara a su sitio; alterna entre ella
 * entera (par) y un retrato de la cara (impar).
 */
@Composable
fun MashaStage(
    presence: MashaPresence,
    quality: MashaQuality,
    recenter: Int,
    landscape: Boolean,
    modifier: Modifier = Modifier,
    onStatus: (StageStatus) -> Unit,
) {
    val context = LocalContext.current
    val status by rememberUpdatedState(onStatus)

    // Todo lo nativo (Filament) es de este escenario y se libera en UN sitio y
    // en su orden (ver `Gpu.destroy`). Con los `remember*` de SceneView cada
    // pieza se liberaba por su cuenta, en orden inverso al de creación.
    val gpu = remember { Gpu.create(context, quality) }
    val portrait = recenter % 2 == 1
    val home = if (portrait) FACE_HOME.shifted(landscape, 0.2f) else HOME.shifted(landscape)
    val target = if (portrait) FACE_TARGET.shifted(landscape, 0.2f) else TARGET.shifted(landscape)
    val cameraNode = remember {
        CameraNode(gpu.engine).apply {
            position = home
            lookAt(target)
            focalLength = 38.0
            // Exposición 1: los valores de emisión y del HDR se leen tal cual.
            setExposure(1f)
        }
    }
    val keyLight = remember {
        LightNode(gpu.engine, LightManager.Type.DIRECTIONAL) {
            color(0.78f, 0.86f, 1f)
            intensity(2.4f)
            direction(0.35f, -0.55f, -0.75f)
            castShadows(false)
        }
    }
    val childNodes = remember {
        mutableStateListOf<Node>(
            // Recorte azul desde detrás: dibuja la silueta.
            LightNode(gpu.engine, LightManager.Type.DIRECTIONAL) {
                color(0.25f, 0.7f, 1f)
                intensity(7f)
                direction(-0.25f, -0.2f, 1f)
                castShadows(false)
            },
            // Relleno violeta desde la derecha, suave.
            LightNode(gpu.engine, LightManager.Type.DIRECTIONAL) {
                color(0.62f, 0.45f, 1f)
                intensity(1.6f)
                direction(-1f, -0.1f, -0.3f)
                castShadows(false)
            },
        )
    }
    var room by remember { mutableStateOf<HoloRoom?>(null) }
    var model by remember { mutableStateOf<Model?>(null) }
    var rig by remember { mutableStateOf<HoloRig?>(null) }
    val sceneView = remember { arrayOfNulls<SceneView>(1) }

    LaunchedEffect(gpu) {
        status(StageStatus.Loading)
        // Primero la sala (luz y fondo), luego ella.
        room = runCatching { HoloRoom.load(context, gpu.environmentLoader) }.getOrNull()
        val loaded = runCatching { gpu.modelLoader.loadModel(quality.model) }.getOrNull()
        model = loaded
        val instance = loaded?.instance
        if (instance == null) {
            status(StageStatus.Failed)
            return@LaunchedEffect
        }
        val node = ModelNode(modelInstance = instance, autoAnimate = false)
        // Modelo v2: materiales propios. Las texturas se decodifican antes de
        // crear nada nativo (si se sale mientras tanto, no queda nada suelto).
        val shader = if (HoloRig.isV2(node)) {
            val textures = withContext(Dispatchers.IO) {
                val maxSize = if (quality == MashaQuality.High) 2048 else 1024
                runCatching { HoloShader.decodeTextures(context, HoloShader.TextureAssets(maxSize = maxSize)) }.getOrNull()
            }
            HoloShader.create(context, gpu.engine)?.also { s -> textures?.let(s::bindTextures) }
        } else {
            null
        }
        childNodes += node
        // Masha mira a la cámara: el rig lee su posición en cada fotograma.
        rig = HoloRig(node, presence, gpu.engine, shader, cameraEntity = cameraNode.entity)
        status(StageStatus.Ready)
    }

    val manipulator = remember(recenter, landscape) { BoundedOrbit(home, target) }
    LaunchedEffect(recenter, landscape) {
        cameraNode.position = home
        cameraNode.lookAt(target)
    }

    DisposableEffect(gpu) {
        onDispose {
            // 1. Que no haya más fotogramas: quita el callback y suelta la superficie.
            // SceneView puede volver a pedir fotogramas si su vista se vuelve a
            // enganchar mientras Compose la retira, y cada fotograma recorre
            // sus nodos (el del modelo lee el asset glTF). Así que antes de
            // liberar nada se le quita todo: sin nodos ni callback, un
            // fotograma de más ya no toca nada nuestro.
            rig?.stop()
            sceneView[0]?.let { v ->
                v.onFrame = null
                v.childNodes = emptyList()
                v.destroy()
            }
            sceneView[0] = null
            // 2. Lo nuestro sobre el modelo (el rig devuelve los materiales de
            //    gltfio y libera los de HoloShader, con el asset aún vivo), y el
            //    modelo fuera de la escena. El nodo del modelo no se destruye: su
            //    entidad raíz es del asset.
            rig?.destroy(gpu.engine)
            model?.let { m -> runCatching { gpu.scene.removeEntities(m.entities) } }
            // Ojo: la lista NO se vacía. El `update` del AndroidView la observa
            // por su cuenta (sin recomponer): vaciarla lo hacía correr otra vez,
            // y SceneView retiraba el nodo del modelo leyendo el asset glTF ya
            // liberado (SIGSEGV en gltfio unos fotogramas después de salir).
            childNodes.forEach { if (it !is ModelNode) runCatching { it.destroy() } }
            runCatching { keyLight.destroy() }
            runCatching { cameraNode.destroy() }
            // 3. Entorno, asset y el resto (el motor, el último), en la siguiente
            //    vuelta del hilo principal, cuando Compose ya ha retirado la vista.
            val r = room
            val m = model
            Handler(Looper.getMainLooper()).post {
                r?.destroy(gpu.engine)
                gpu.destroy(m)
            }
        }
    }

    Scene(
        modifier = modifier,
        engine = gpu.engine,
        modelLoader = gpu.modelLoader,
        materialLoader = gpu.materialLoader,
        environmentLoader = gpu.environmentLoader,
        view = gpu.view,
        renderer = gpu.renderer,
        scene = gpu.scene,
        environment = room?.environment ?: gpu.black,
        mainLightNode = keyLight,
        cameraNode = cameraNode,
        childNodes = childNodes,
        cameraManipulator = manipulator,
        onFrame = { t -> rig?.frame(t) },
        onViewCreated = { sceneView[0] = this },
    )
}

/**
 * Los objetos nativos del escenario, creados juntos y liberados juntos en el
 * orden que Filament exige: primero lo que usa el motor, al final el motor y
 * su contexto EGL.
 */
private class Gpu(
    val egl: EGLContext,
    val engine: Engine,
    val renderer: Renderer,
    val scene: FilamentScene,
    val view: View,
    val modelLoader: ModelLoader,
    val materialLoader: MaterialLoader,
    val environmentLoader: EnvironmentLoader,
    val black: Environment,
) {
    fun destroy(model: Model?) {
        step("asset") {
            model?.let { m ->
                runCatching { m.releaseSourceData() }
                modelLoader.assetLoader.destroyAsset(m)
            }
        }
        step("model loader") {
            modelLoader.assetLoader.destroy()
            modelLoader.materialProvider.destroyMaterials()
            modelLoader.materialProvider.destroy()
            modelLoader.resourceLoader.destroy()
        }
        step("material loader") {
            materialLoader.destroy()
            // SceneView no libera este proveedor: se hace aquí.
            materialLoader.ubershaderProvider.destroyMaterials()
            materialLoader.ubershaderProvider.destroy()
        }
        step("environment") {
            engine.safeDestroyEnvironment(black)
            environmentLoader.destroy()
        }
        step("view") {
            engine.destroyView(view)
            engine.destroyScene(scene)
            engine.destroyRenderer(renderer)
        }
        step("engine") {
            engine.destroy()
            OpenGL.destroyEglContext(egl)
        }
    }

    /** Cada paso por separado: si uno falla, los siguientes se liberan igual. */
    private inline fun step(name: String, block: () -> Unit) {
        runCatching(block).onFailure { Log.w(TAG, "teardown: $name failed", it) }
    }

    companion object {
        fun create(context: Context, quality: MashaQuality): Gpu {
            val egl = SceneView.createEglContext()
            val engine = SceneView.createEngine(egl)
            return Gpu(
                egl = egl,
                engine = engine,
                renderer = SceneView.createRenderer(engine),
                scene = SceneView.createScene(engine),
                view = SceneView.createView(engine).also { configureView(it, quality) },
                modelLoader = ModelLoader(engine, context),
                materialLoader = MaterialLoader(engine, context),
                environmentLoader = EnvironmentLoader(engine, context),
                black = SceneView.createEnvironment(engine, isOpaque = true, indirectLight = null),
            )
        }
    }
}

/** Posproceso: bloom para la emisión, niebla azul, viñeta y AO si da la talla. */
private fun configureView(view: View, quality: MashaQuality) {
    val high = quality == MashaQuality.High
    view.antiAliasing = View.AntiAliasing.FXAA
    view.bloomOptions = view.bloomOptions.apply {
        enabled = true
        strength = 0.34f
        levels = if (high) 6 else 4
        resolution = if (high) 384 else 256
        threshold = true
        blendMode = View.BloomOptions.BlendMode.ADD
    }
    view.fogOptions = view.fogOptions.apply {
        enabled = true
        distance = 2.2f
        density = 0.05f
        maximumOpacity = 0.5f
        height = 0f
        heightFalloff = 0.7f
        color = floatArrayOf(0.04f, 0.1f, 0.24f)
    }
    view.vignetteOptions = view.vignetteOptions.apply {
        enabled = true
        midPoint = 0.55f
        roundness = 0.75f
        feather = 0.65f
        color = floatArrayOf(0f, 0.005f, 0.02f, 1f)
    }
    view.ambientOcclusionOptions = view.ambientOcclusionOptions.apply {
        enabled = high
        radius = 0.25f
        intensity = 0.6f
    }
    view.dynamicResolutionOptions = view.dynamicResolutionOptions.apply {
        enabled = !high
        homogeneousScaling = true
        minScale = 0.6f
    }
}

/**
 * La sala de control: IBL y cielo a partir de `room.hdr`. Se crean aquí (y no
 * con el cargador de SceneView) para fijar sus intensidades a la exposición 1 y
 * poder liberar las texturas al salir.
 */
private class HoloRoom(val environment: Environment, private val textures: List<Texture>) {

    fun destroy(engine: Engine) {
        engine.safeDestroyEnvironment(environment)
        textures.forEach { runCatching { engine.destroyTexture(it) } }
    }

    companion object {
        fun load(context: Context, loader: EnvironmentLoader): HoloRoom? {
            val engine = loader.engine
            val hdr = HDRLoader.createTexture(engine, context.assets.readBuffer("masha/room.hdr")) ?: return null
            val cube = loader.iblPrefilter.equirectangularToCubemap(hdr)
            engine.destroyTexture(hdr)
            val reflections = loader.iblPrefilter.specularFilter(cube)
            val ibl = IndirectLight.Builder().reflections(reflections).intensity(IBL_INTENSITY).build(engine)
            val sky = Skybox.Builder().environment(cube).intensity(SKY_INTENSITY).build(engine)
            return HoloRoom(Environment(indirectLight = ibl, skybox = sky), listOf(cube, reflections))
        }
    }
}

/**
 * Órbita alrededor de ella con el zoom acotado: ni meterse dentro del
 * holograma ni perderla de vista.
 */
private class BoundedOrbit(home: Position, private val target: Position) :
    CameraGestureDetector.DefaultCameraManipulator(orbitHomePosition = home, targetPosition = target) {

    override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) {
        val distance = length(getTransform().position - Float3(target.x, target.y, target.z))
        val zoomingIn = currSeparation > prevSeparation
        if (zoomingIn && distance <= MIN_DISTANCE) return
        if (!zoomingIn && distance >= MAX_DISTANCE) return
        super.scrollUpdate(x, y, prevSeparation, currSeparation)
    }
}
