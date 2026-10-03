package com.elyndra.launcher.ui.masha

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import io.github.sceneview.node.ModelNode
import io.github.sceneview.utils.readBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

private const val TAG = "HoloShader"

/**
 * Los materiales propios del holograma de Masha (Filament 1.56, compilados con
 * `tools/masha_v2/compile_materials.ps1` en `assets/masha/materials/`):
 *
 * - `masha_holo`: cuerpo y piel de la cabeza. Iluminado y translúcido (base
 *   oscura y desaturada para que la luz dibuje los volúmenes), con borde de
 *   Fresnel, pulsos que recorren los circuitos del traje, costuras, líneas de
 *   barrido y glitch. La cara usa el mismo material con menos emisión.
 * - `masha_holo_eye`: ojos (y dientes/lengua). Opaco, iris con un brillo suave
 *   que no llega al umbral del bloom, esclerótica atenuada, córnea con capa
 *   transparente para el reflejo. Encima, todo emisión: la pupila se dilata
 *   ([frame]), anillo holográfico y limbo oscuro en el borde del iris, un brillo
 *   fijo respecto a la cámara (los ojos "vivos") y un lóbulo húmedo en la córnea.
 *   El glitch apenas desgarra los ojos (y lo mismo la cara a su alrededor).
 * - `masha_holo_card`: pelo, cejas y pestañas (tarjetas con alfa).
 *
 * Uso: [create] → [decodeTextures] (fuera del hilo principal si se quiere) →
 * [bindTextures] → [applyTo] (o [apply] por primitiva) → [frame] en cada
 * fotograma → [destroy] antes de liberar el asset y el motor.
 *
 * Todo lo nativo se toca en el hilo principal (el del motor). [frame] no crea
 * objetos.
 */
internal class HoloShader private constructor(
    private val engine: Engine,
    private val holoMaterial: Material,
    private val eyeMaterial: Material,
    private val cardMaterial: Material,
) {

    /** Qué parte del modelo es cada primitiva: decide material y ajustes. */
    enum class Kind(internal val family: Family) {
        Body(Family.Holo),
        Face(Family.Holo),
        MouthInterior(Family.Holo),
        Eyes(Family.Eye),
        Teeth(Family.Eye),
        Tongue(Family.Eye),
        Hair(Family.Card),
        Brows(Family.Card),
        Lashes(Family.Card),
    }

    internal enum class Family { Holo, Eye, Card }

    /**
     * Ajustes de `masha_holo` (cuerpo/cara). Rangos y efecto en la cabecera de
     * `tools/masha_v2/materials/masha_holo.mat`. Los colores no están aquí: los
     * pone [frame] a partir del ánimo.
     */
    data class HoloShaderParams(
        val albedo: Float = 0.30f,
        val desaturate: Float = 0.45f,
        val roughness: Float = 0.42f,
        val suitRoughness: Float = 0.30f,
        val suitDarken: Float = 0.35f,
        val specular: Float = 0.45f,
        val normalStrength: Float = 1f,
        val rimPower: Float = 3f,
        val rimIntensity: Float = 0.9f,
        val rimAlpha: Float = 0.45f,
        /** Cuánto blanco lleva el color del borde respecto al del brillo. */
        val rimWhiten: Float = 0.25f,
        val circuitIntensity: Float = 3.0f,
        val circuitBase: Float = 0.18f,
        val circuitSpeed: Float = 0.30f,
        val pulseWidth: Float = 0.14f,
        val pulseCount: Float = 3f,
        val seamIntensity: Float = 0.35f,
        val scanlineDensity: Float = 110f,
        val scanlineIntensity: Float = 0.08f,
        val scanlineSpeed: Float = 0.04f,
        val sweepIntensity: Float = 0.30f,
        val sweepSpeed: Float = 0.18f,
        /** Máximo de glitch del material cuando [frame] recibe glitch = 1. */
        val glitchAmount: Float = 1f,
        val alpha: Float = 0.86f,
        /** 1 = circuitos, costuras y barrido a tope; la cara 0.3 para que se lean los rasgos. */
        val emissiveDamp: Float = 1f,
        /** Brillo propio uniforme (el holograma emite algo de luz): aclara el lado en sombra. */
        val selfIllum: Float = 0.04f,
        /** Celdas por unidad UV del patrón de repuesto (sin máscara del traje). */
        val proceduralScale: Float = 24f,
        /** Segundo lóbulo de Fresnel, ancho: dibuja los contornos de los rasgos desde dentro (solo la cara). */
        val contourPower: Float = 2f,
        val contourIntensity: Float = 0f,
        /** Cuánto se apagan scanlines, barrido y parpadeo del glitch alrededor de los ojos (solo la cara). */
        val eyeScanCalm: Float = 0f,
    ) {
        companion object {
            val BODY = HoloShaderParams()

            /**
             * Cara: opaca (si no, a través de los párpados y los labios se ven
             * los ojos y los dientes, que son opacos y se dibujan antes), más
             * clara y casi sin circuitos: manda la luz. El borde de Fresnel algo
             * más fuerte y un segundo lóbulo ancho marcan nariz, labios, pómulos y
             * mandíbula; alrededor de los ojos, sin scanlines.
             */
            val FACE = HoloShaderParams(
                albedo = 0.50f,
                desaturate = 0.55f,
                roughness = 0.48f,
                specular = 0.40f,
                rimIntensity = 0.7f,
                rimPower = 3.5f,
                rimAlpha = 0.3f,
                circuitIntensity = 1.2f,
                seamIntensity = 0.2f,
                scanlineIntensity = 0.04f,
                sweepIntensity = 0.15f,
                alpha = 1f,
                emissiveDamp = 0.3f,
                selfIllum = 0.06f,
                contourPower = 2f,
                contourIntensity = 0.22f,
                eyeScanCalm = 0.85f,
            )

            /** Interior de la boca: oscuro, opaco, sin efectos salvo el glitch (se desgarra con la cara). */
            val MOUTH = HoloShaderParams(
                albedo = 0.08f,
                desaturate = 0.6f,
                roughness = 0.6f,
                suitDarken = 0f,
                specular = 0.2f,
                rimIntensity = 0f,
                rimAlpha = 0f,
                alpha = 1f,
                emissiveDamp = 0f,
                selfIllum = 0f,
            )
        }
    }

    /**
     * Ajustes de `masha_holo_eye` (ver la cabecera de `masha_holo_eye.mat`). La geometría del
     * iris es la de `masha_eye_basecolor.png`/`masha_eye_irismask.png` (dos islas UV, una por ojo):
     * centros de la pupila, radio de la pupila y del iris, en UV.
     */
    data class HoloEyeParams(
        val irisEmission: Float = 0.35f,
        val irisTint: Float = 0.25f,
        val irisBrightness: Float = 1f,
        val scleraDim: Float = 0.55f,
        val scleraTint: Float = 0.40f,
        val roughness: Float = 0.35f,
        val corneaGloss: Float = 1f,
        val corneaRoughness: Float = 0.06f,
        val brightness: Float = 1f,
        /** Centros de la pupila de las dos islas, en UV (A: u > v, B: u < v). */
        val pupilAU: Float = 0.7067f,
        val pupilAV: Float = 0.2962f,
        val pupilBU: Float = 0.2937f,
        val pupilBV: Float = 0.7020f,
        /** Radio de la pupila y del iris en la textura (UV); un radio de iris 0 apaga dilatación, limbo y anillo. */
        val pupilRadius: Float = 0.031f,
        val irisRadius: Float = 0.104f,
        val limbusDark: Float = 0.3f,
        val ringGlow: Float = 0.35f,
        /**
         * Brillo fijo respecto a la cámara: hacia dónde está (espacio de la vista: arriba a la
         * izquierda de quien mira, no tan alto que lo tape el párpado superior).
         */
        val catchX: Float = -0.32f,
        val catchY: Float = 0.24f,
        val catchZ: Float = 1f,
        /** Coseno de su radio angular en la córnea (0,99919 ≈ 2,3°) e intensidad (algo más de 1: un destello mínimo de bloom). */
        val catchSize: Float = 0.99919f,
        val catchIntensity: Float = 1.2f,
        val wetIntensity: Float = 0.18f,
    ) {
        companion object {
            /** El iris se lee mejor (más claro y con más tono del ánimo); la pupila se mueve con [frame]. */
            val EYES = HoloEyeParams(irisEmission = 0.42f, irisTint = 0.3f, irisBrightness = 1.15f)

            // Dientes y lengua: sus PNG son opacos (alfa 1), así que van por la rama del "iris":
            // todo lo que es solo del ojo, apagado.
            val TEETH = HoloEyeParams(
                irisEmission = 0f, scleraDim = 0.6f, scleraTint = 0.5f, roughness = 0.4f, corneaGloss = 0.3f, corneaRoughness = 0.2f, brightness = 0.45f,
                irisRadius = 0f, limbusDark = 0f, ringGlow = 0f, catchIntensity = 0f, wetIntensity = 0f,
            )
            val TONGUE = HoloEyeParams(
                irisEmission = 0f, scleraDim = 0.55f, scleraTint = 0.45f, roughness = 0.5f, corneaGloss = 0.2f, corneaRoughness = 0.3f, brightness = 0.4f,
                irisRadius = 0f, limbusDark = 0f, ringGlow = 0f, catchIntensity = 0f, wetIntensity = 0f,
            )
        }
    }

    /** Ajustes de `masha_holo_card` (ver la cabecera de `masha_holo_card.mat`). */
    data class HoloCardParams(
        val albedo: Float = 0.45f,
        val detail: Float = 0.7f,
        val roughness: Float = 0.45f,
        val rimPower: Float = 2.5f,
        val rimIntensity: Float = 0.6f,
        val sheenIntensity: Float = 0.5f,
        val sheenSpeed: Float = 0.25f,
        val sheenFrequency: Float = 2f,
        val glowIntensity: Float = 0.15f,
        val alpha: Float = 0.85f,
        val cutoff: Float = 0.08f,
        val glitchAmount: Float = 1f,
    ) {
        companion object {
            val HAIR = HoloCardParams()
            // Cejas y pestañas: su textura es negra y en el holograma desaparecían
            // (la cara parecía sin cejas). Brillo propio para que se lean. Las pestañas
            // casi no parpadean con el glitch: enmarcan los ojos, que tienen que leerse.
            val BROWS = HoloCardParams(albedo = 0.6f, detail = 0.3f, rimIntensity = 0.3f, sheenIntensity = 0f, glowIntensity = 0.55f, alpha = 1f, cutoff = 0.05f)
            val LASHES = HoloCardParams(albedo = 0.3f, detail = 0.2f, rimIntensity = 0f, sheenIntensity = 0f, glowIntensity = 0.25f, alpha = 1f, cutoff = 0.1f, glitchAmount = 0.3f)
        }
    }

    /**
     * Rutas (en `assets/`) de las texturas. Las que falten se sustituyen: la
     * máscara del traje por el patrón procedural, el normal por uno plano, los
     * ojos por una esclerótica lisa; sin textura de pelo/cejas/pestañas esas
     * partes conservan el material de gltfio ([apply] devuelve false).
     */
    data class TextureAssets(
        // Calidad ligera: las variantes _1024 (o maxSize = 1024).
        val suitMask: String? = "masha/textures/masha_suit_mask_2048.png",
        val suitNormal: String? = "masha/textures/masha_suit_normal_2048.png",
        val eyeColor: String? = "masha/textures/masha_eye_basecolor.png",
        val eyeIrisMask: String? = "masha/textures/masha_eye_irismask.png",
        val teeth: String? = "masha/textures/masha_teeth_basecolor.png",
        val tongue: String? = "masha/textures/masha_tongue_basecolor.png",
        // Texturas MPFB de las tarjetas (las del GLB; gltfio no deja leerlas).
        val hair: String? = "masha/textures/ponytail01_diffuse.png",
        val brows: String? = "masha/textures/eyebrow008.png",
        val lashes: String? = "masha/textures/eyelashes03.png",
        /** Lado máximo: 2048 en calidad alta, 1024 en la ligera. */
        val maxSize: Int = 2048,
    )

    /** Píxeles ya decodificados (RGBA8, fila de arriba primero, sin premultiplicar). */
    class Pixels(val width: Int, val height: Int, val data: ByteBuffer, val srgb: Boolean)

    /** Resultado de [decodeTextures]: se sube a la GPU con [bindTextures]. */
    class DecodedTextures(
        val suitMask: Pixels?,
        val suitNormal: Pixels?,
        val eye: Pixels?,
        val teeth: Pixels?,
        val tongue: Pixels?,
        val hair: Pixels?,
        val brows: Pixels?,
        val lashes: Pixels?,
    )

    /* ── instancias ───────────────────────────────────────────── */

    private class Slot(val kind: Kind, val mi: MaterialInstance) {
        var holo = HoloShaderParams.BODY
        var eye = HoloEyeParams.EYES
        var card = HoloCardParams.HAIR
    }

    private val slots = arrayOfNulls<Slot>(Kind.entries.size)
    private val textures = ArrayList<Texture>()

    /** Primitivas cambiadas y su material original, para devolverlo en [destroy]. */
    private class Swap(val entity: Int, val primitive: Int, val original: MaterialInstance)
    private val swaps = ArrayList<Swap>()

    private val flatNormal = solid(128, 128, 255, 255, srgb = false)
    private val emptyMask = solid(0, 0, 0, 0, srgb = false)
    private val plainEye = solid(200, 200, 205, 0, srgb = true)
    private var suitMask: Texture? = null
    private var suitNormal: Texture? = null
    private val kindTexture = arrayOfNulls<Texture>(Kind.entries.size)

    private val linear = TextureSampler(
        TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.CLAMP_TO_EDGE,
    ).apply { anisotropy = 4f }

    private val cards = TextureSampler(
        TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.REPEAT,
    )

    /** La instancia de un tipo (una por tipo, compartida entre sus primitivas). */
    fun instance(kind: Kind): MaterialInstance = slot(kind).mi

    private fun slot(kind: Kind): Slot {
        slots[kind.ordinal]?.let { return it }
        val mi = when (kind.family) {
            Family.Holo -> holoMaterial.createInstance()
            Family.Eye -> eyeMaterial.createInstance()
            Family.Card -> cardMaterial.createInstance()
        }
        val s = Slot(kind, mi)
        when (kind) {
            Kind.Face -> s.holo = HoloShaderParams.FACE
            Kind.MouthInterior -> s.holo = HoloShaderParams.MOUTH
            Kind.Teeth -> s.eye = HoloEyeParams.TEETH
            Kind.Tongue -> s.eye = HoloEyeParams.TONGUE
            Kind.Brows -> s.card = HoloCardParams.BROWS
            Kind.Lashes -> s.card = HoloCardParams.LASHES
            else -> Unit
        }
        slots[kind.ordinal] = s
        // Pelo: con profundidad (el discard del material abre los huecos). Sin
        // ella las tarjetas de detrás se ven a través de las de delante y la
        // coleta parece un montón de placas.
        if (kind == Kind.Hair) mi.setDepthWrite(true)
        writeStatic(s)
        bindSamplers(s)
        // Colores de partida (neutral) hasta el primer frame().
        writeColors(s, NEUTRAL_SKIN, NEUTRAL_GLOW)
        return s
    }

    /** Cambia los ajustes de un tipo (p. ej. para afinar la cara). */
    fun setParams(kind: Kind, params: HoloShaderParams) {
        require(kind.family == Family.Holo) { "$kind no usa masha_holo" }
        val s = slot(kind)
        s.holo = params
        writeStatic(s)
    }

    fun setParams(kind: Kind, params: HoloEyeParams) {
        require(kind.family == Family.Eye) { "$kind no usa masha_holo_eye" }
        val s = slot(kind)
        s.eye = params
        writeStatic(s)
    }

    fun setParams(kind: Kind, params: HoloCardParams) {
        require(kind.family == Family.Card) { "$kind no usa masha_holo_card" }
        val s = slot(kind)
        s.card = params
        writeStatic(s)
    }

    /* ── texturas ─────────────────────────────────────────────── */

    /**
     * Sube a la GPU las texturas decodificadas y las engancha. Hilo del motor.
     * Se puede llamar antes o después de [apply].
     */
    fun bindTextures(decoded: DecodedTextures) {
        decoded.suitMask?.let { suitMask = upload(it) }
        decoded.suitNormal?.let { suitNormal = upload(it) }
        decoded.eye?.let { kindTexture[Kind.Eyes.ordinal] = upload(it) }
        decoded.teeth?.let { kindTexture[Kind.Teeth.ordinal] = upload(it) }
        decoded.tongue?.let { kindTexture[Kind.Tongue.ordinal] = upload(it) }
        decoded.hair?.let { kindTexture[Kind.Hair.ordinal] = upload(it) }
        decoded.brows?.let { kindTexture[Kind.Brows.ordinal] = upload(it) }
        decoded.lashes?.let { kindTexture[Kind.Lashes.ordinal] = upload(it) }
        for (s in slots) if (s != null) bindSamplers(s)
    }

    private fun bindSamplers(s: Slot) {
        val mi = s.mi
        when (s.kind.family) {
            Family.Holo -> {
                val mask = suitMask
                mi.setParameter("suitMask", mask ?: emptyMask, linear)
                mi.setParameter("useSuitMask", if (mask != null) 1f else 0f)
                mi.setParameter("suitNormal", suitNormal ?: flatNormal, linear)
            }
            Family.Eye -> mi.setParameter("eyeMap", kindTexture[s.kind.ordinal] ?: plainEye, linear)
            Family.Card -> mi.setParameter("cardMap", kindTexture[s.kind.ordinal] ?: plainEye, cards)
        }
    }

    /* ── cambio de materiales ─────────────────────────────────── */

    /**
     * Pone el material de [kind] en la primitiva [primitiveIndex] del renderable
     * [renderable] (entidad de gltfio). Guarda el material original para
     * devolverlo en [destroy]. Devuelve false si no se cambió (tarjetas sin su
     * textura: sin alfa se verían como placas).
     */
    fun apply(renderable: Int, primitiveIndex: Int, kind: Kind): Boolean {
        if (kind.family == Family.Card && kindTexture[kind.ordinal] == null) return false
        val rm = engine.renderableManager
        val ri = rm.getInstance(renderable)
        if (ri == 0 || primitiveIndex !in 0 until rm.getPrimitiveCount(ri)) return false
        val mi = slot(kind).mi
        val current = rm.getMaterialInstanceAt(ri, primitiveIndex)
        if (swaps.none { it.entity == renderable && it.primitive == primitiveIndex }) {
            swaps += Swap(renderable, primitiveIndex, current)
        }
        rm.setMaterialInstanceAt(ri, primitiveIndex, mi)
        return true
    }

    /**
     * Cambia de golpe todas las primitivas reconocidas del modelo (ver
     * [kindOf]). Devuelve cuántas se cambiaron.
     */
    fun applyTo(node: ModelNode): Int {
        var n = 0
        for (r in node.renderableNodes) {
            val name = r.name.orEmpty()
            r.materialInstances.forEachIndexed { i, mi ->
                val kind = kindOf(name, i, mi.name) ?: return@forEachIndexed
                if (apply(r.entity, i, kind)) n++
            }
        }
        return n
    }

    /* ── por fotograma ────────────────────────────────────────── */

    private var lastGlitch = 0f
    private var glitchSeed = 0f

    /**
     * Anima todos los materiales. Sin asignaciones.
     *
     * @param timeSec segundos desde el inicio (se envuelve cada hora).
     * @param energy energía del ánimo 0..1 (velocidad y brillo de circuitos).
     * @param pulse pulso de voz/micrófono 0..~1.2 (borde, circuitos, iris).
     * @param glitch 0..1: la ráfaga de glitch (0 fuera de ella).
     * @param moodSkin color de piel del ánimo, RGB lineal (3 floats).
     * @param moodGlow color de brillo del ánimo, RGB lineal (3 floats).
     * @param pupil dilatación de la pupila (radio relativo, `FaceExpression.pupil`).
     * @param attention 0..1 (escuchar, curiosidad): aviva el anillo del iris y el brillo del ojo.
     * @param eyes centros de los ojos en el mundo (L 0..2, R 3..5), o null si no se saben:
     *   alrededor de ellos el glitch y las scanlines se calman.
     */
    fun frame(
        timeSec: Float,
        energy: Float,
        pulse: Float,
        glitch: Float,
        moodSkin: FloatArray,
        moodGlow: FloatArray,
        pupil: Float = 0f,
        attention: Float = 0f,
        eyes: FloatArray? = null,
    ) {
        val time = timeSec % TIME_WRAP
        // Cada ráfaga nueva, otro patrón de bandas.
        if (glitch > 0f && lastGlitch <= 0f) glitchSeed = (glitchSeed + 0.618034f) % 1f
        lastGlitch = glitch
        for (s in slots) {
            if (s == null) continue
            val mi = s.mi
            when (s.kind.family) {
                Family.Holo -> {
                    mi.setParameter("time", time)
                    mi.setParameter("energy", energy)
                    mi.setParameter("pulse", pulse)
                    mi.setParameter("glitch", glitch * s.holo.glitchAmount)
                    mi.setParameter("glitchSeed", glitchSeed)
                    // Solo la cara llega a la zona de calma de los ojos (cuerpo y boca quedan fuera).
                    if (s.kind == Kind.Face) writeEyes(mi, eyes)
                }
                Family.Eye -> {
                    // El mismo desgarro que la cara: ojos, dientes y lengua van con su banda.
                    mi.setParameter("pulse", pulse)
                    mi.setParameter("time", time)
                    mi.setParameter("glitch", glitch)
                    mi.setParameter("glitchSeed", glitchSeed)
                    // Dientes y lengua quedan fuera de la zona de calma: solo los ojos.
                    if (s.kind == Kind.Eyes) {
                        writeEyes(mi, eyes)
                        mi.setParameter("pupilDilation", pupil)
                        mi.setParameter("attention", attention)
                        if (com.elyndra.launcher.BuildConfig.DEBUG) debugCatch(s)
                    }
                }
                Family.Card -> {
                    mi.setParameter("time", time)
                    mi.setParameter("energy", energy)
                    mi.setParameter("pulse", pulse)
                    mi.setParameter("glitch", glitch * s.card.glitchAmount)
                }
            }
            writeColors(s, moodSkin, moodGlow)
        }
    }

    /** Solo debug: el brillo del ojo que se pide por adb (`MashaDebugPose.catchX/Y/I`), o el de fábrica. */
    private fun debugCatch(s: Slot) {
        val p = s.eye
        val x = MashaDebugPose.catchX
        val y = MashaDebugPose.catchY
        val i = MashaDebugPose.catchI
        s.mi.setParameter("catchDir", if (x.isNaN()) p.catchX else x, if (y.isNaN()) p.catchY else y, p.catchZ)
        s.mi.setParameter("catchIntensity", if (i.isNaN()) p.catchIntensity else i)
    }

    /** Centros de los ojos (zona de calma del glitch y las scanlines); lejos si no se saben. */
    private fun writeEyes(mi: MaterialInstance, eyes: FloatArray?) {
        if (eyes == null) {
            mi.setParameter("eyeCenterL", FAR, FAR, FAR)
            mi.setParameter("eyeCenterR", FAR, FAR, FAR)
        } else {
            mi.setParameter("eyeCenterL", eyes[0], eyes[1], eyes[2])
            mi.setParameter("eyeCenterR", eyes[3], eyes[4], eyes[5])
        }
    }

    private fun writeColors(s: Slot, skin: FloatArray, glow: FloatArray) {
        val mi = s.mi
        when (s.kind.family) {
            Family.Holo -> {
                mi.setParameter("baseColor", skin[0], skin[1], skin[2])
                mi.setParameter("glowColor", glow[0], glow[1], glow[2])
                val w = s.holo.rimWhiten
                mi.setParameter("rimColor", glow[0] + (1f - glow[0]) * w, glow[1] + (1f - glow[1]) * w, glow[2] + (1f - glow[2]) * w)
            }
            Family.Eye -> {
                mi.setParameter("holoColor", skin[0], skin[1], skin[2])
                mi.setParameter("glowColor", glow[0], glow[1], glow[2])
            }
            Family.Card -> {
                mi.setParameter("baseColor", skin[0], skin[1], skin[2])
                mi.setParameter("glowColor", glow[0], glow[1], glow[2])
                mi.setParameter("rimColor", glow[0] + (1f - glow[0]) * 0.25f, glow[1] + (1f - glow[1]) * 0.25f, glow[2] + (1f - glow[2]) * 0.25f)
            }
        }
    }

    private fun writeStatic(s: Slot) {
        val mi = s.mi
        when (s.kind.family) {
            Family.Holo -> with(s.holo) {
                mi.setParameter("albedo", albedo)
                mi.setParameter("desaturate", desaturate)
                mi.setParameter("roughness", roughness)
                mi.setParameter("suitRoughness", suitRoughness)
                mi.setParameter("suitDarken", suitDarken)
                mi.setParameter("specular", specular)
                mi.setParameter("normalStrength", normalStrength)
                mi.setParameter("rimPower", rimPower)
                mi.setParameter("rimIntensity", rimIntensity)
                mi.setParameter("rimAlpha", rimAlpha)
                mi.setParameter("circuitIntensity", circuitIntensity)
                mi.setParameter("circuitBase", circuitBase)
                mi.setParameter("circuitSpeed", circuitSpeed)
                mi.setParameter("pulseWidth", pulseWidth)
                mi.setParameter("pulseCount", pulseCount)
                mi.setParameter("seamIntensity", seamIntensity)
                mi.setParameter("scanlineDensity", scanlineDensity)
                mi.setParameter("scanlineIntensity", scanlineIntensity)
                mi.setParameter("scanlineSpeed", scanlineSpeed)
                mi.setParameter("sweepIntensity", sweepIntensity)
                mi.setParameter("sweepSpeed", sweepSpeed)
                mi.setParameter("alpha", alpha)
                mi.setParameter("emissiveDamp", emissiveDamp)
                mi.setParameter("selfIllum", selfIllum)
                mi.setParameter("proceduralScale", proceduralScale)
                mi.setParameter("contourPower", contourPower)
                mi.setParameter("contourIntensity", contourIntensity)
                mi.setParameter("eyeScanCalm", eyeScanCalm)
                mi.setParameter("eyeCalmRadii", EYE_CALM_INNER, EYE_CALM_OUTER)
                mi.setParameter("eyeGlitch", EYE_GLITCH)
                writeEyes(mi, null)
                mi.setParameter("time", 0f)
                mi.setParameter("glitch", 0f)
                mi.setParameter("glitchSeed", 0f)
                mi.setParameter("energy", 0.5f)
                mi.setParameter("pulse", 0f)
            }
            Family.Eye -> with(s.eye) {
                mi.setParameter("irisEmission", irisEmission)
                mi.setParameter("irisTint", irisTint)
                mi.setParameter("irisBrightness", irisBrightness)
                mi.setParameter("scleraDim", scleraDim)
                mi.setParameter("scleraTint", scleraTint)
                mi.setParameter("roughness", roughness)
                mi.setParameter("corneaGloss", corneaGloss)
                mi.setParameter("corneaRoughness", corneaRoughness)
                mi.setParameter("brightness", brightness)
                mi.setParameter("irisCenters", pupilAU, pupilAV, pupilBU, pupilBV)
                mi.setParameter("irisRadii", pupilRadius, irisRadius)
                mi.setParameter("limbusDark", limbusDark)
                mi.setParameter("ringGlow", ringGlow)
                mi.setParameter("catchDir", catchX, catchY, catchZ)
                mi.setParameter("catchSize", catchSize)
                mi.setParameter("catchIntensity", catchIntensity)
                mi.setParameter("wetIntensity", wetIntensity)
                mi.setParameter("eyeCalmRadii", EYE_CALM_INNER, EYE_CALM_OUTER)
                mi.setParameter("eyeGlitch", EYE_GLITCH)
                writeEyes(mi, null)
                mi.setParameter("pupilDilation", 0f)
                mi.setParameter("attention", 0f)
                mi.setParameter("pulse", 0f)
                mi.setParameter("time", 0f)
                mi.setParameter("glitch", 0f)
                mi.setParameter("glitchSeed", 0f)
            }
            Family.Card -> with(s.card) {
                mi.setParameter("albedo", albedo)
                mi.setParameter("detail", detail)
                mi.setParameter("roughness", roughness)
                mi.setParameter("rimPower", rimPower)
                mi.setParameter("rimIntensity", rimIntensity)
                mi.setParameter("sheenIntensity", sheenIntensity)
                mi.setParameter("sheenSpeed", sheenSpeed)
                mi.setParameter("sheenFrequency", sheenFrequency)
                mi.setParameter("glowIntensity", glowIntensity)
                mi.setParameter("alpha", alpha)
                mi.setParameter("cutoff", cutoff)
                mi.setParameter("time", 0f)
                mi.setParameter("glitch", 0f)
                mi.setParameter("energy", 0.5f)
                mi.setParameter("pulse", 0f)
            }
        }
    }

    /* ── liberación ───────────────────────────────────────────── */

    private var destroyed = false

    /**
     * Devuelve a cada primitiva su material de gltfio y libera, en este orden,
     * instancias → texturas → materiales. Llamar en el hilo del motor, ya sin
     * fotogramas en curso, con el asset aún vivo (para restaurar) y SIEMPRE
     * antes de `engine.destroy()`. En MashaStage: junto a `rig?.destroy(engine)`.
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        val rm = engine.renderableManager
        for (sw in swaps) {
            runCatching {
                val ri = rm.getInstance(sw.entity)
                if (ri != 0) rm.setMaterialInstanceAt(ri, sw.primitive, sw.original)
            }
        }
        swaps.clear()
        for (i in slots.indices) {
            slots[i]?.let { s -> step("instance ${s.kind}") { engine.destroyMaterialInstance(s.mi) } }
            slots[i] = null
        }
        for (t in textures) step("texture") { engine.destroyTexture(t) }
        textures.clear()
        step("materials") {
            engine.destroyMaterial(holoMaterial)
            engine.destroyMaterial(eyeMaterial)
            engine.destroyMaterial(cardMaterial)
        }
    }

    private inline fun step(name: String, block: () -> Unit) {
        runCatching(block).onFailure { Log.w(TAG, "destroy: $name failed", it) }
    }

    /* ── utilidades de textura ────────────────────────────────── */

    private fun solid(r: Int, g: Int, b: Int, a: Int, srgb: Boolean): Texture {
        val data = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        data.put(r.toByte()).put(g.toByte()).put(b.toByte()).put(a.toByte()).flip()
        return upload(Pixels(1, 1, data, srgb))
    }

    private fun upload(p: Pixels): Texture {
        val levels = if (p.width == 1 && p.height == 1) 1 else 32 - Integer.numberOfLeadingZeros(max(p.width, p.height))
        val tex = Texture.Builder()
            .width(p.width)
            .height(p.height)
            .levels(levels)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(if (p.srgb) Texture.InternalFormat.SRGB8_A8 else Texture.InternalFormat.RGBA8)
            .usage(Texture.Usage.DEFAULT or Texture.Usage.COLOR_ATTACHMENT or Texture.Usage.BLIT_SRC or Texture.Usage.BLIT_DST)
            .build(engine)
        p.data.rewind()
        tex.setImage(engine, 0, Texture.PixelBufferDescriptor(p.data, Texture.Format.RGBA, Texture.Type.UBYTE))
        if (levels > 1) tex.generateMipmaps(engine)
        textures += tex
        return tex
    }

    companion object {
        const val MATERIAL_DIR = "masha/materials"

        /** El reloj del shader se envuelve cada hora (precisión de float). */
        private const val TIME_WRAP = 3600f

        /**
         * Zona de calma alrededor de cada ojo (m): entera dentro de [EYE_CALM_INNER] (párpados,
         * pestañas), nada más allá de [EYE_CALM_OUTER] (cejas, pómulo). Ahí el glitch desgarra
         * solo [EYE_GLITCH] y la cara apaga sus scanlines. Los dos materiales usan los mismos valores.
         */
        const val EYE_CALM_INNER = 0.022f
        const val EYE_CALM_OUTER = 0.045f
        const val EYE_GLITCH = 0.3f

        /** Ojos desconocidos: centros lejísimos (sin zona de calma). */
        private const val FAR = 1e4f

        private val NEUTRAL_SKIN = floatArrayOf(0.07f, 0.29f, 1f)
        private val NEUTRAL_GLOW = floatArrayOf(0.24f, 0.67f, 1f)

        /** Carga los tres materiales. Hilo del motor. Null si falta algún `.filamat`. */
        fun create(context: Context, engine: Engine): HoloShader? {
            val loaded = ArrayList<Material>(3)
            return try {
                for (name in listOf("masha_holo", "masha_holo_eye", "masha_holo_card")) {
                    val buffer = context.assets.readBuffer("$MATERIAL_DIR/$name.filamat")
                    loaded += Material.Builder().payload(buffer, buffer.remaining()).build(engine)
                }
                HoloShader(engine, loaded[0], loaded[1], loaded[2])
            } catch (e: Exception) {
                Log.w(TAG, "could not load hologram materials", e)
                loaded.forEach { runCatching { engine.destroyMaterial(it) } }
                null
            }
        }

        /**
         * Qué es cada primitiva del GLB v2 (nodo + material glTF). Null = no se
         * toca (holotanque, etc.).
         */
        fun kindOf(nodeName: String, index: Int, materialName: String?): Kind? {
            when (materialName) {
                "Masha_Skin" -> return if (nodeName == "Masha_Head") Kind.Face else Kind.Body
                "Masha_MouthInterior" -> return Kind.MouthInterior
                "Masha_Teeth" -> return Kind.Teeth
                "Masha_Tongue" -> return Kind.Tongue
                "Masha_Brows" -> return Kind.Brows
                "Masha_Lashes" -> return Kind.Lashes
                "Masha_Eye", "Masha_Eyes" -> return Kind.Eyes
                "Masha_Hair" -> return Kind.Hair
            }
            // Si el cargador no conserva los nombres de material: por nodo y
            // orden de ranuras (Skin, MouthInterior, Teeth, Tongue, Brows, Lashes).
            return when (nodeName) {
                "Masha_Body" -> Kind.Body
                "Masha_Eyes" -> Kind.Eyes
                "Masha_Hair" -> Kind.Hair
                "Masha_Head" -> HEAD_SLOTS.getOrNull(index)
                else -> null
            }
        }

        private val HEAD_SLOTS = listOf(Kind.Face, Kind.MouthInterior, Kind.Teeth, Kind.Tongue, Kind.Brows, Kind.Lashes)

        /**
         * Decodifica las texturas de [assets] (puro CPU: vale cualquier hilo,
         * mejor `Dispatchers.IO`). Las que no existan quedan en null.
         */
        fun decodeTextures(context: Context, assets: TextureAssets = TextureAssets()): DecodedTextures {
            fun load(path: String?, srgb: Boolean) = path?.let { decode(context, it, assets.maxSize, srgb) }
            val eyeColor = assets.eyeColor?.let { decodeBitmap(context, it, assets.maxSize) }
            val eye = eyeColor?.let { color ->
                val mask = assets.eyeIrisMask?.let { decodeBitmap(context, it, assets.maxSize) }
                packAlpha(color, mask).also {
                    color.recycle()
                    mask?.recycle()
                }
            }
            return DecodedTextures(
                suitMask = load(assets.suitMask, srgb = false),
                suitNormal = load(assets.suitNormal, srgb = false),
                eye = eye,
                teeth = load(assets.teeth, srgb = true),
                tongue = load(assets.tongue, srgb = true),
                hair = load(assets.hair, srgb = true),
                brows = load(assets.brows, srgb = true),
                lashes = load(assets.lashes, srgb = true),
            )
        }

        private fun decode(context: Context, path: String, maxSize: Int, srgb: Boolean): Pixels? {
            val bmp = decodeBitmap(context, path, maxSize) ?: return null
            // ARGB_8888 en memoria es R,G,B,A: tal cual para Filament. Sin
            // premultiplicar (la máscara lleva datos en A, no opacidad).
            val data = ByteBuffer.allocateDirect(bmp.byteCount).order(ByteOrder.nativeOrder())
            bmp.copyPixelsToBuffer(data)
            data.flip()
            return Pixels(bmp.width, bmp.height, data, srgb).also { bmp.recycle() }
        }

        /** Color sRGB en RGB + la máscara (su canal rojo) en A, una sola textura. */
        private fun packAlpha(color: Bitmap, mask: Bitmap?): Pixels {
            val w = color.width
            val h = color.height
            val scaled = mask?.let { if (it.width == w && it.height == h) it else Bitmap.createScaledBitmap(it, w, h, true) }
            val row = IntArray(w)
            val maskRow = IntArray(w)
            val data = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            for (y in 0 until h) {
                color.getPixels(row, 0, w, 0, y, w, 1)
                scaled?.getPixels(maskRow, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    val c = row[x]
                    data.put((c shr 16).toByte()).put((c shr 8).toByte()).put(c.toByte())
                    data.put(if (scaled != null) (maskRow[x] shr 16).toByte() else 0.toByte())
                }
            }
            if (scaled != null && scaled !== mask) scaled.recycle()
            data.flip()
            return Pixels(w, h, data, srgb = true)
        }

        private fun decodeBitmap(context: Context, path: String, maxSize: Int): Bitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return@runCatching null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > maxSize) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPremultiplied = false
                inScaled = false
                inSampleSize = sample
            }
            context.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }
}
