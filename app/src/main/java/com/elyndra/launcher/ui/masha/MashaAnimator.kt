package com.elyndra.launcher.ui.masha

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * El cuerpo de Masha, fotograma a fotograma, sin Filament (se prueba en la
 * JVM con un esqueleto sintético; [MashaAnimatorFilament] lo conecta al modelo).
 *
 * Orden en cada [frame]:
 *  1. Máquina de estados ([MotionDirector]) → pila de fundidos ([BlendStack])
 *     → pose mezclada de los clips (+ inercialización si hubo un corte).
 *  2. Capas procedurales sumadas encima: respiración, balanceo con ruido,
 *     cambio de peso ([ProceduralLayer]).
 *  3. Mirada: Spine2/Neck/Head hacia la cámara con muelles críticos y los ojos
 *     (`masha:eye.L/R`) delante, con sacadas ([Saccades]).
 *  4. IK de los pies (dos huesos por pierna): el balanceo no los hace patinar.
 * El resultado queda en [output] (poses locales); los muelles del pelo y
 * el pecho van después, en el adaptador.
 *
 * Los ejes del personaje ([up], [fwd], [left]) se miden en la pose de reposo
 * (dedos de los pies delante, pierna izquierda a la izquierda), no se suponen.
 * No crea objetos por fotograma.
 */
internal class MashaAnimator(
    val skeleton: Skeleton,
    val clips: ClipSet,
    val config: MashaAnimConfig = MashaAnimConfig(),
) {
    private val n = skeleton.n
    val stack = BlendStack(8)
    val director = MotionDirector(clips, config, Random(config.seed))
    private val procedural = ProceduralLayer(config, config.seed + 1)
    val saccades = Saccades(config.look, config.seed + 2)
    private val inertia = Inertializer(n)
    private val ik = TwoBoneIk()

    /** La pose final de este fotograma (local, por articulación). */
    val output = Pose(n)
    private val blend = Pose(n)
    private val tmp = Pose(n)
    private val last = Pose(n)
    private val last2 = Pose(n)
    private var lastDt = 0f
    private var frames = 0
    private val model = FloatArray(16 * n)
    private val clipModel = FloatArray(16 * n)

    /* ── huesos (−1 si el modelo no lo trae: esa parte se salta) ── */

    private fun bone(name: String) = skeleton.index("mixamorig:$name")
    private val hips = bone("Hips")
    private val spine = bone("Spine")
    private val spine1 = bone("Spine1")
    private val spine2 = bone("Spine2")
    private val neck = bone("Neck")
    private val head = bone("Head")
    private val shoulderL = bone("LeftShoulder")
    private val shoulderR = bone("RightShoulder")
    private val armL = bone("LeftArm")
    private val armR = bone("RightArm")
    private val upLegL = bone("LeftUpLeg")
    private val legL = bone("LeftLeg")
    private val footL = bone("LeftFoot")
    private val upLegR = bone("RightUpLeg")
    private val legR = bone("RightLeg")
    private val footR = bone("RightFoot")
    private val eyeL = skeleton.index("masha:eye.L")
    private val eyeR = skeleton.index("masha:eye.R")

    /** Ejes del personaje en el espacio del modelo (medidos en reposo). */
    val up = floatArrayOf(0f, 1f, 0f)
    val fwd = floatArrayOf(0f, 0f, 1f)
    val left = floatArrayOf(1f, 0f, 0f)
    private val headFwd = FloatArray(3)
    private val headUp = FloatArray(3)
    private val eyeFwdL = FloatArray(3)
    private val eyeFwdR = FloatArray(3)

    /** Pies del primer fotograma de cada clip: pos L, rot L, pos R, rot R (14 floats). */
    private val feet = FloatArray(clips.names.size * 14)
    private val feetOk = BooleanArray(clips.names.size)

    /* ── mirada ── */

    private val look = Array(6) { CritSpring() }
    private var lookW = 0f
    private var lastYaw = Float.NaN
    private var lastPitch = 0f

    /* ── para la cara ── */

    /** Pitch medio de los ojos (°, + = arriba): acopla los párpados. */
    var eyePitch = 0f
        private set
    /** Pide un parpadeo (cambio grande de mirada o final de frase); quien lo usa lo apaga. */
    var blinkRequest = false
    /** Sonrisa que añaden los clips que suenan (Var_GlanceSmile, React_Happy…). */
    var smile = 0f
        private set
    /** Peso actual de la mirada a la cámara (0..1). */
    val lookWeight: Float get() = lookW

    /* ── trabajo ── */

    private val q1 = FloatArray(4)
    private val q2 = FloatArray(4)
    private val q3 = FloatArray(4)
    private val pq = FloatArray(4)
    private val rq = FloatArray(4)
    private val acc = FloatArray(4)
    private val ra = FloatArray(4)
    private val rb = FloatArray(4)
    private val va = FloatArray(3)
    private val vb = FloatArray(3)
    private val vc = FloatArray(3)
    private val vt = FloatArray(3)
    private val hf = FloatArray(3)
    private val hu = FloatArray(3)
    private val hl = FloatArray(3)
    private val lockP = FloatArray(3)
    private val lockQ = FloatArray(4)
    private val liveQ = FloatArray(4)

    private var lookClip = 1f
    private var stepW = 0f

    init {
        skeleton.fk(skeleton.rest, model)
        measureFrame()
    }

    private fun measureFrame() {
        val toeL = bone("LeftToeBase")
        val toeR = bone("RightToeBase")
        if (footL >= 0 && toeL >= 0 && footR >= 0 && toeR >= 0) {
            for (i in 0..2) fwd[i] = model[16 * toeL + 12 + i] - model[16 * footL + 12 + i] + model[16 * toeR + 12 + i] - model[16 * footR + 12 + i]
            fwd[1] = 0f
            if (norm3(fwd) < 1e-4f) { fwd[0] = 0f; fwd[1] = 0f; fwd[2] = 1f }
        }
        if (upLegL >= 0 && upLegR >= 0) {
            for (i in 0..2) left[i] = model[16 * upLegL + 12 + i] - model[16 * upLegR + 12 + i]
            left[1] = 0f
            if (norm3(left) < 1e-4f) { left[0] = 1f; left[1] = 0f; left[2] = 0f }
        }
        cross(fwd, left, up)
        if (norm3(up) < 1e-4f) { up[0] = 0f; up[1] = 1f; up[2] = 0f }
        cross(up, fwd, left)
        norm3(left)
        if (head >= 0) {
            skeleton.modelQuat(model, head, q1)
            conj(q1, q2)
            quatRotate(q2, fwd, headFwd)
            quatRotate(q2, up, headUp)
        }
        if (eyeL >= 0) { skeleton.modelQuat(model, eyeL, q1); conj(q1, q2); quatRotate(q2, fwd, eyeFwdL) }
        if (eyeR >= 0) { skeleton.modelQuat(model, eyeR, q1); conj(q1, q2); quatRotate(q2, fwd, eyeFwdR) }
    }

    /** Texto para el registro: ejes medidos y el eje delantero del ojo en su marco local. */
    fun describeFrame(): String =
        "fwd=${fmt(fwd)} left=${fmt(left)} up=${fmt(up)} eyeFwdLocal=${fmt(eyeFwdL)} headFwdLocal=${fmt(headFwd)}"

    private fun fmt(v: FloatArray) = "(%.3f, %.3f, %.3f)".format(v[0], v[1], v[2])

    /** Guarda dónde pone los pies el primer fotograma de cada clip (una vez, al cargar). */
    fun captureFeet(sampler: PoseSampler) {
        if (footL < 0 || footR < 0) return
        for (c in clips.body) {
            sampler.sample(c, 0f, tmp)
            skeleton.fk(tmp, model)
            storeFoot(c * 14, footL)
            storeFoot(c * 14 + 7, footR)
            feetOk[c] = true
        }
    }

    private fun storeFoot(o: Int, j: Int) {
        feet[o] = model[16 * j + 12]; feet[o + 1] = model[16 * j + 13]; feet[o + 2] = model[16 * j + 14]
        skeleton.modelQuat(model, j, q1)
        q1.copyInto(feet, o + 3)
    }

    /** Pide un gesto. */
    fun cue(a: Action) = director.cue(a)

    /**
     * Un fotograma. [t] s desde el inicio, [dt] desde el anterior; [camera] es
     * la posición de la cámara en el espacio del modelo.
     */
    fun frame(t: Float, dt: Float, input: MotionInput, camera: FloatArray, sampler: PoseSampler) {
        // 1. Clips.
        if (frames == 0) director.start(stack, t)
        stack.update(dt)
        director.update(t, dt, input, stack)
        if (stack.size > 0) stack.evaluate(sampler, blend, tmp) else blend.copyFrom(skeleton.rest)
        if (stack.inertiaRequested) {
            stack.inertiaRequested = false
            if (frames > 0) {
                val f = config.fades
                inertia.begin(last, last2, if (frames > 1) lastDt else 0f, blend, f.inertiaHalfLife, f.inertiaMaxSpeed)
            }
        }
        inertia.apply(blend, dt)
        last2.copyFrom(last)
        last.copyFrom(blend)
        lastDt = dt
        frames++
        skeleton.fk(blend, clipModel)
        output.copyFrom(blend)
        weights()

        // 2. Capas procedurales.
        additive(t, dt)
        skeleton.fk(output, model)
        if (director.sentenceEnded) blinkRequest = true

        // 3. Mirada.
        if (config.look.enabled && head >= 0) gaze(t, dt, input.thinking, camera)

        // 4. Pies.
        if (config.feet.enabled) {
            leg(upLegL, legL, footL, 0)
            leg(upLegR, legR, footR, 7)
        }
    }

    /** Pesos visibles de los clips que suenan → mirada, sonrisa, pasos. */
    private fun weights() {
        var lk = 0f
        var sm = 0f
        var st = 0f
        val k = stack.opaqueFloor()
        for (i in k until stack.size) {
            val w = stack.visibleWeight(i)
            val c = stack[i].clip
            if (c !in clips.lookAt.indices) { lk += w; continue }
            lk += w * clips.lookAt[c]
            sm += w * clips.smile[c]
            if (clips.stepping[c]) st += w
        }
        lookClip = if (stack.size == 0) 1f else lk
        smile = sm
        stepW = st
    }

    /* ── capas procedurales ── */

    private fun rotate(j: Int, axis: FloatArray, angle: Float, frame: FloatArray) {
        if (j < 0 || angle == 0f) return
        axisAngle(axis[0], axis[1], axis[2], angle, rq)
        skeleton.parentQuat(frame, j, pq)
        rotateInModel(output, j, rq, pq, q1, q2)
    }

    private fun additive(t: Float, dt: Float) {
        val gesture = director.gesturing || t - director.lastGestureEnd < config.idle.boostSeconds
        // El cambio de peso propio se apaga mientras suena un clip que da pasos.
        val amount = config.procedural
        procedural.update(t, dt, gesture, amount)
        val p = procedural
        val f = clipModel
        rotate(hips, fwd, p.hipsRoll * (1f - stepW), f)
        if (hips >= 0 && p.shiftX != 0f) {
            for (i in 0..2) va[i] = left[i] * p.shiftX * (1f - stepW)
            skeleton.toParent(f, hips, va)
            for (i in 0..2) output.t[3 * hips + i] += va[i]
        }
        rotate(spine, fwd, p.spineRoll, f)
        rotate(spine1, left, p.spine1Pitch, f)
        rotate(spine2, left, p.spine2Pitch, f)
        rotate(shoulderL, fwd, p.shoulderRaise, f)
        rotate(shoulderR, fwd, -p.shoulderRaise, f)
        rotate(head, up, p.headYaw, f)
        rotate(head, left, p.headPitch, f)
        rotate(head, fwd, p.headRoll, f)
        rotate(armL, fwd, p.armL, f)
        rotate(armR, fwd, p.armR, f)
    }

    /* ── mirada ── */

    private fun headFrame() {
        skeleton.modelQuat(model, head, q3)
        quatRotate(q3, headFwd, hf)
        quatRotate(q3, headUp, hu)
        cross(hu, hf, hl)
        norm3(hl)
    }

    private fun gaze(t: Float, dt: Float, thinking: Boolean, camera: FloatArray) {
        val c = config.look
        headFrame()
        // Centro entre los ojos (o un punto de la cara si no hay huesos de ojos).
        if (eyeL >= 0 && eyeR >= 0) {
            for (i in 0..2) va[i] = 0.5f * (model[16 * eyeL + 12 + i] + model[16 * eyeR + 12 + i])
        } else {
            for (i in 0..2) va[i] = model[16 * head + 12 + i] + 0.09f * (hu[i] + hf[i])
        }
        for (i in 0..2) vt[i] = camera[i] - va[i]
        norm3(vt)
        val fy = dot3(vt, hf)
        val ly = dot3(vt, hl)
        val yaw = atan2(ly, fy) / DEG
        val pitch = atan2(dot3(vt, hu), sqrt(fy * fy + ly * ly)) / DEG
        val wTarget = if (abs(yaw) > c.behindYaw) 0f else lookClip
        lookW += (wTarget - lookW) * (1f - exp(-dt / c.weightTau))
        if (!lastYaw.isNaN() && abs(yaw - lastYaw) + abs(pitch - lastPitch) > c.blinkShift) blinkRequest = true
        lastYaw = yaw
        lastPitch = pitch
        saccades.update(t, thinking)
        if (saccades.bigShift) blinkRequest = true

        // Cabeza: la meta total se reparte entre Spine2, cuello y cabeza, cada uno con su muelle.
        val gy = (yaw * c.follow + saccades.headYaw).coerceIn(-c.headYawMax, c.headYawMax) * lookW
        val gp = (pitch * c.follow + saccades.headPitch).coerceIn(-c.headPitchMax, c.headPitchMax) * lookW
        val s2y = look[0].update(gy * c.spine2Share, c.spine2HalfLife, dt)
        val s2p = look[1].update(gp * c.spine2Share, c.spine2HalfLife, dt)
        val ny = look[2].update(gy * c.neckShare, c.neckHalfLife, dt)
        val np = look[3].update(gp * c.neckShare, c.neckHalfLife, dt)
        val hy = look[4].update(gy * c.headShare, c.headHalfLife, dt)
        val hp = look[5].update(gp * c.headShare, c.headHalfLife, dt)
        acc[0] = 0f; acc[1] = 0f; acc[2] = 0f; acc[3] = 1f
        turn(spine2, s2y, s2p)
        turn(neck, ny, np)
        turn(head, hy, hp)
        skeleton.fk(output, model)

        // Ojos: al objetivo desde la cabeza ya girada, más las sacadas; con sus límites.
        headFrame()
        var sum = 0f
        var count = 0
        if (eyeL >= 0) { sum += eye(eyeL, eyeFwdL, camera); count++ }
        if (eyeR >= 0) { sum += eye(eyeR, eyeFwdR, camera); count++ }
        eyePitch = if (count > 0) sum / count else 0f
    }

    /** Gira [j] yaw/pitch (°) en ejes de la cabeza; [acc] lleva lo ya girado en sus padres. */
    private fun turn(j: Int, yawDeg: Float, pitchDeg: Float) {
        if (j < 0) return
        axisAngle(hu[0], hu[1], hu[2], yawDeg * DEG, q1)
        // Pitch + = arriba: gira alrededor de −izquierda.
        axisAngle(hl[0], hl[1], hl[2], -pitchDeg * DEG, q2)
        quatMul(q1, q2, rq)
        skeleton.parentQuat(model, j, q3)
        quatMul(acc, q3, pq)
        rotateInModel(output, j, rq, pq, q1, q2)
        quatMul(rq, acc, q3)
        q3.copyInto(acc)
    }

    /** Orienta un ojo; devuelve su pitch (°). */
    private fun eye(j: Int, fwdLocal: FloatArray, camera: FloatArray): Float {
        val c = config.look
        for (i in 0..2) vt[i] = camera[i] - model[16 * j + 12 + i]
        norm3(vt)
        val fy = dot3(vt, hf)
        val ly = dot3(vt, hl)
        val ty = atan2(ly, fy) / DEG
        val tp = atan2(dot3(vt, hu), sqrt(fy * fy + ly * ly)) / DEG
        val y = (ty * lookW + saccades.yaw).coerceIn(-c.eyeYawMax, c.eyeYawMax)
        val p = (tp * lookW + saccades.pitch).coerceIn(-c.eyePitchDown, c.eyePitchUp)
        val cy = cos(y * DEG); val sy = sin(y * DEG)
        val cp = cos(p * DEG); val sp = sin(p * DEG)
        for (i in 0..2) vb[i] = hf[i] * cp * cy + hl[i] * cp * sy + hu[i] * sp
        skeleton.modelQuat(model, j, q1)
        quatRotate(q1, fwdLocal, vc)
        norm3(vc)
        fromTo(vc, vb, rq)
        skeleton.parentQuat(model, j, pq)
        rotateInModel(output, j, rq, pq, q1, q2)
        return p
    }

    /* ── pies ── */

    private fun leg(a: Int, b: Int, cIdx: Int, off: Int) {
        if (a < 0 || b < 0 || cIdx < 0) return
        if (skeleton.parent[b] != a || skeleton.parent[cIdx] != b) return
        val fc = config.feet
        // Dónde clavar el pie: media (con los pesos visibles) del primer fotograma de cada clip.
        skeleton.modelQuat(clipModel, cIdx, liveQ)
        var lw = 0f
        lockP[0] = 0f; lockP[1] = 0f; lockP[2] = 0f
        lockQ[0] = 0f; lockQ[1] = 0f; lockQ[2] = 0f; lockQ[3] = 0f
        val k = stack.opaqueFloor()
        for (i in k until stack.size) {
            val clip = stack[i].clip
            if (clip !in feetOk.indices || !feetOk[clip] || clips.stepping[clip]) continue
            val w = stack.visibleWeight(i)
            if (w <= 0f) continue
            val o = clip * 14 + off
            lw += w
            for (x in 0..2) lockP[x] += feet[o + x] * w
            val d = feet[o + 3] * liveQ[0] + feet[o + 4] * liveQ[1] + feet[o + 5] * liveQ[2] + feet[o + 6] * liveQ[3]
            val s = if (d < 0f) -w else w
            for (x in 0..3) lockQ[x] += feet[o + 3 + x] * s
        }
        for (x in 0..2) vt[x] = clipModel[16 * cIdx + 12 + x]
        var lockW = 0f
        if (lw > 1e-4f) {
            for (x in 0..2) lockP[x] /= lw
            normQ(lockQ)
            val dx = lockP[0] - vt[0]; val dy = lockP[1] - vt[1]; val dz = lockP[2] - vt[2]
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            // Si el clip aleja mucho el pie de su sitio, lo suelta (está dando un paso).
            val release = smootherstep((dist - fc.releaseStart) / (fc.releaseEnd - fc.releaseStart))
            lockW = lw.coerceAtMost(1f) * (1f - release)
            for (x in 0..2) vt[x] += (lockP[x] - vt[x]) * lockW
            val dq = dot4(lockQ, liveQ)
            val s = if (dq < 0f) -lockW else lockW
            for (x in 0..3) q3[x] = liveQ[x] * (1f - lockW) + lockQ[x] * s
            normQ(q3)
        } else {
            liveQ.copyInto(q3)
        }
        // IK con la pose ya balanceada.
        skeleton.position(model, a, va)
        skeleton.position(model, b, vb)
        skeleton.position(model, cIdx, vc)
        ik.solve(va, vb, vc, vt, fwd, fc.maxReach, fc.softness, ra, rb)
        skeleton.parentQuat(model, a, pq)
        rotateInModel(output, a, ra, pq, q1, q2)
        // El padre de la rodilla (muslo) ya lleva ra.
        skeleton.modelQuat(model, a, q1)
        quatMul(ra, q1, pq)
        rotateInModel(output, b, rb, pq, q1, q2)
        // Tobillo: su rotación del modelo vuelve a la de la meta.
        skeleton.modelQuat(model, b, q1)
        quatMul(ra, q1, q2)
        quatMul(rb, q2, q1)
        conj(q1, q2)
        quatMul(q2, q3, q1)
        normQ(q1)
        q1.copyInto(output.q, 4 * cIdx)
    }

    companion object {
        fun cross(a: FloatArray, b: FloatArray, out: FloatArray) {
            val x = a[1] * b[2] - a[2] * b[1]
            val y = a[2] * b[0] - a[0] * b[2]
            val z = a[0] * b[1] - a[1] * b[0]
            out[0] = x; out[1] = y; out[2] = z
        }
    }
}
