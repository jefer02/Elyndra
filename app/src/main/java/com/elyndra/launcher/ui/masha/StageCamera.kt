package com.elyndra.launcher.ui.masha

import com.google.android.filament.Camera
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Quién mueve la cámara del escenario y cómo.
 *
 * El plano ([MashaShot]) se resuelve en cada fotograma con [MashaFraming] a
 * partir de la vista, del hueco libre de la interfaz y de sus ojos en la pose
 * actual (suavizados): la distancia, la altura a la que se mira y el
 * desplazamiento salen de proporciones, no de posiciones fijas, así que sirve
 * en vertical y en horizontal, en móvil y en tableta, con los dos modelos.
 * Siempre de frente y centrada en horizontal en el hueco.
 *
 * Cambiar de plano (el botón o girar el móvil) se anima desde donde esté la
 * cámara; con "reducir movimiento", salta. En cuanto el usuario toca la
 * escena, la cámara es suya: el escenario le da la órbita en la posición
 * actual ([takeOver]) y aquí se deja de escribir hasta el siguiente [show].
 *
 * Todo en el hilo principal, en el `onFrame` de SceneView, después del rig y
 * del manipulador de SceneView (así lo que se escribe aquí manda). No crea
 * objetos por fotograma.
 */
internal class StageCamera(private val camera: Camera, focalLengthMm: Double) {

    private val tanHalf = MashaFraming.tanHalfFov(focalLengthMm)

    var shot = MashaShot.CloseUp
        private set

    /** Hay que escribir la cámara (hasta que el usuario la toma). */
    private var driving = true

    var reduced = false

    private val eye = DoubleArray(3)
    private val target = DoubleArray(3)
    private val shift = DoubleArray(2)
    private var placed = false

    private val fromEye = DoubleArray(3)
    private val fromTarget = DoubleArray(3)
    private val fromShift = DoubleArray(2)
    private var transitionStart = -1L

    private val goalEye = DoubleArray(3)
    private val goalTarget = DoubleArray(3)
    private val goalShift = DoubleArray(2)

    /** Ojos suavizados (mundo) y encuadre suavizado (distancia, altura, desvío lateral). */
    private val face = MashaFraming.REST_EYES.copyOf()
    private val raw = FloatArray(3)
    private var faceSeen = false
    private val frame = FloatArray(3)
    private val frameRaw = FloatArray(3)
    private var frameSeen = false

    private var areaL = 0f
    private var areaT = 0f
    private var areaR = 0f
    private var areaB = 0f

    private var lastNanos = 0L

    private val pos = FloatArray(3)
    private val fwd = FloatArray(3)

    /** El hueco libre, en px de la vista del escenario (sin área = toda la vista). */
    fun setArea(left: Float, top: Float, right: Float, bottom: Float) {
        areaL = left
        areaT = top
        areaR = right
        areaB = bottom
    }

    /**
     * Pasa a [shot]. [animate] = transición desde la cámara actual (si no, o con
     * "reducir movimiento", salta). Vuelve a llevar la cámara aunque el usuario
     * la hubiese movido.
     */
    fun show(shot: MashaShot, animate: Boolean) {
        this.shot = shot
        driving = true
        if (!placed || !animate || reduced) {
            transitionStart = -1L
            // El encuadre de un plano a otro no se suaviza: salta al nuevo.
            frameSeen = false
            return
        }
        // Desde donde esté de verdad (el usuario pudo orbitar): posición y
        // hacia dónde mira, a la distancia del último punto de mira.
        camera.getPosition(pos)
        camera.getForwardVector(fwd)
        val dx = target[0] - pos[0]
        val dy = target[1] - pos[1]
        val dz = target[2] - pos[2]
        val dist = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.3)
        for (i in 0..2) {
            fromEye[i] = pos[i].toDouble()
            fromTarget[i] = pos[i] + fwd[i] * dist
        }
        fromShift[0] = shift[0]
        fromShift[1] = shift[1]
        frameSeen = false
        transitionStart = 0L
    }

    /**
     * El usuario ha tocado la escena: deja de llevar la cámara. Devuelve true
     * si hasta ahora la llevaba (el escenario tiene que darle la órbita en la
     * posición actual, que se lee con [eyeOut]/[targetOut]).
     */
    fun takeOver(): Boolean {
        if (!driving) return false
        driving = false
        transitionStart = -1L
        return true
    }

    fun eyeOut(out: FloatArray) { for (i in 0..2) out[i] = eye[i].toFloat() }
    fun targetOut(out: FloatArray) { for (i in 0..2) out[i] = target[i].toFloat() }

    /** Un fotograma. [rig] da los ojos de esta pose; [width]/[height], la vista en px. */
    fun frame(frameTimeNanos: Long, rig: HoloRig?, width: Int, height: Int) {
        val dt = if (lastNanos == 0L) 0f else ((frameTimeNanos - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastNanos = frameTimeNanos

        // Los ojos: la primera pose se toma tal cual; después, suavizada, para
        // que la cámara no tiemble con cada cabeceo o sacada.
        if (rig != null && rig.faceWorld(raw)) {
            if (!faceSeen) {
                raw.copyInto(face)
                faceSeen = true
            } else {
                val k = 1f - exp(-dt / FOLLOW_TAU)
                for (i in 0..2) face[i] += (raw[i] - face[i]) * k
            }
        }
        if (!driving) return

        MashaFraming.solve(
            width.toFloat(), height.toFloat(), areaL, areaT, areaR, areaB, tanHalf, shot, face[1], frameRaw,
        )
        if (!frameSeen) {
            frameRaw.copyInto(frame)
            frameSeen = true
        } else {
            // El hueco cambia (teclado, barras): el encuadre lo sigue suave.
            val k = 1f - exp(-dt / FRAME_TAU)
            for (i in 0..2) frame[i] += (frameRaw[i] - frame[i]) * k
        }

        // De frente y en horizontal: la cámara, desplazada en paralelo, a la
        // altura y al lado que pide el encuadre. Sin desplazamiento de imagen.
        // Ella está en x≈0 del mundo: el seguimiento lateral de sus ojos se acota
        // para que una medida rara nunca la saque de la pantalla.
        val faceX = face[0].coerceIn(-MAX_SIDE, MAX_SIDE)
        goalTarget[0] = (faceX + frame[2]).toDouble()
        goalTarget[1] = frame[1].toDouble()
        goalTarget[2] = face[2].toDouble()
        goalEye[0] = goalTarget[0]
        goalEye[1] = goalTarget[1]
        goalEye[2] = goalTarget[2] + frame[0]
        goalShift[0] = 0.0
        goalShift[1] = 0.0

        var s = 1.0
        if (transitionStart >= 0L) {
            if (transitionStart == 0L) transitionStart = frameTimeNanos
            val p = ((frameTimeNanos - transitionStart) / 1e9 / TRANSITION_S).coerceIn(0.0, 1.0)
            s = smooth(p)
            if (p >= 1.0) transitionStart = -1L
        }
        for (i in 0..2) {
            eye[i] = if (s >= 1.0) goalEye[i] else fromEye[i] + (goalEye[i] - fromEye[i]) * s
            target[i] = if (s >= 1.0) goalTarget[i] else fromTarget[i] + (goalTarget[i] - fromTarget[i]) * s
        }
        for (i in 0..1) shift[i] = if (s >= 1.0) goalShift[i] else fromShift[i] + (goalShift[i] - fromShift[i]) * s
        camera.lookAt(eye[0], eye[1], eye[2], target[0], target[1], target[2], 0.0, 1.0, 0.0)
        camera.setShift(shift[0], shift[1])
        placed = true
        if (com.elyndra.launcher.BuildConfig.DEBUG && frameTimeNanos - lastLog > 1_000_000_000L) {
            lastLog = frameTimeNanos
            android.util.Log.d(
                "MashaCamera",
                "shot=$shot view=${width}x$height area=[$areaL,$areaT,$areaR,$areaB] eyes=(${face[0]},${face[1]}) " +
                    "cam=(${eye[0]},${eye[1]},${eye[2]}) dist=${frame[0]}",
            )
        }
    }

    private var lastLog = 0L

    private fun smooth(x: Double): Double = x * x * x * (x * (x * 6 - 15) + 10)

    private companion object {
        /** Suavizado del seguimiento de los ojos (s). */
        const val FOLLOW_TAU = 0.9f

        /** Suavizado del encuadre cuando cambia el hueco (teclado, barras). */
        const val FRAME_TAU = 0.25f

        const val TRANSITION_S = 0.75

        /** Hasta dónde puede seguir la cámara sus ojos a los lados (m). */
        const val MAX_SIDE = 0.3f
    }
}
