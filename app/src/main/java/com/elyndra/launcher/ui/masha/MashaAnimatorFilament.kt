package com.elyndra.launcher.ui.masha

import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.TransformManager
import com.google.android.filament.gltfio.Animator
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.FilamentInstance

/**
 * Conecta [MashaAnimator] con el modelo cargado en Filament.
 *
 * Muestreo de clips: `Animator.applyAnimation(clip, t)` escribe las locales
 * de los huesos en el TransformManager y aquí se leen con `getTransform`. Se
 * eligió esto y no leer los datos del GLB a mano porque gltfio ya interpola
 * (STEP/LINEAR, cuaterniones) en C++, no hace falta el búfer binario (se
 * libera tras cargar) y cuesta poco: por capa, 1 llamada JNI para aplicar y
 * una lectura por articulación (68 en el modelo v2). Con 1–3 capas son
 * ~70–210 lecturas + 68 escrituras por fotograma, todas dentro de una
 * transacción de transformaciones locales (el mundo se recalcula una vez, al
 * cerrarla) → del orden de 0,1–0,3 ms en un móvil medio. Nada crea objetos.
 *
 * Qué canales anima cada clip se averigua al cargar con un valor centinela
 * (lo que un clip no anima se toma de la pose de reposo, no de lo que dejó el
 * clip muestreado antes). Construir ANTES de aplicar ningún clip y DESPUÉS de
 * [SpringBonesFilament] (que guarda la pose de reposo): al terminar deja la
 * pose de reposo como estaba.
 *
 * Por fotograma, en el hilo principal: [frame] (clips, capas, mirada, pies y
 * escritura de las locales). Después el que llama aplica los anillos, los
 * muelles (`SpringBonesFilament.frame`) y `animator.updateBoneMatrices()`.
 */
internal class MashaAnimatorFilament(
    engine: Engine,
    private val asset: FilamentAsset,
    private val instance: FilamentInstance,
    private val animator: Animator,
    config: MashaAnimConfig = MashaAnimConfig(),
) : PoseSampler {

    private val tm: TransformManager = engine.transformManager
    private val rootInst = tm.getInstance(instance.root)
    private val inst: IntArray
    val core: MashaAnimator
    private val keyR: Array<BooleanArray?>
    private val keyT: Array<BooleanArray?>

    private val m = FloatArray(16)
    private val qs = FloatArray(4)
    private val rootWorld = FloatArray(16)
    private val rootInv = FloatArray(16)
    private val camWorld = FloatArray(16)
    private val camera = FloatArray(3)
    private val p = FloatArray(3)

    init {
        // Las articulaciones: nodos mixamorig:* y masha:* (padres antes que hijos).
        val found = ArrayList<Pair<String, Int>>()
        for (e in instance.entities) {
            val name = asset.getName(e) ?: continue
            if (name.startsWith("mixamorig:") || name.startsWith("masha:")) found += name to e
        }
        fun depth(e: Int): Int {
            var d = 0
            var x = e
            while (true) {
                val i = tm.getInstance(x)
                if (i == 0) return d
                x = tm.getParent(i)
                if (x == 0) return d
                d++
            }
        }
        val sorted = found.sortedBy { depth(it.second) }
        val names = Array(sorted.size) { sorted[it].first }
        val entities = IntArray(sorted.size) { sorted[it].second }
        inst = IntArray(sorted.size) { tm.getInstance(entities[it]) }
        val index = HashMap<Int, Int>()
        entities.forEachIndexed { i, e -> index[e] = i }

        tm.getWorldTransform(rootInst, rootWorld)
        invertAffine(rootWorld, rootInv)
        val parent = IntArray(sorted.size)
        val rootParent = arrayOfNulls<FloatArray>(sorted.size)
        val rest = Array(sorted.size) { FloatArray(16) }
        for (j in sorted.indices) {
            val pe = tm.getParent(inst[j])
            parent[j] = index[pe] ?: -1
            if (parent[j] < 0 && pe != 0) {
                tm.getWorldTransform(tm.getInstance(pe), m)
                rootParent[j] = FloatArray(16).also { mul4(rootInv, m, it) }
            }
            tm.getTransform(inst[j], rest[j])
        }
        val skeleton = Skeleton(names, parent, rest, rootParent)

        val clipNames = List(animator.animationCount) { animator.getAnimationName(it) ?: "" }
        val durations = FloatArray(clipNames.size) { animator.getAnimationDuration(it) }
        val clips = ClipSet(clipNames, durations, config)
        core = MashaAnimator(skeleton, clips, config)

        // Qué anima cada clip: centinela en todas las articulaciones, aplicar, comparar.
        keyR = arrayOfNulls(clipNames.size)
        keyT = arrayOfNulls(clipNames.size)
        val sq = floatArrayOf(0.123f, 0.456f, 0.789f, 0.321f).also { normQ(it) }
        val sentinel = FloatArray(16).also { quatToMat(sq, it); it[12] = 123f; it[13] = 456f; it[14] = 789f }
        tm.openLocalTransformTransaction()
        try {
            for (c in clips.body) {
                for (j in inst.indices) tm.setTransform(inst[j], sentinel)
                animator.applyAnimation(c, 0f)
                val r = BooleanArray(inst.size)
                val t = BooleanArray(inst.size)
                for (j in inst.indices) {
                    tm.getTransform(inst[j], m)
                    matToQuat(m, qs)
                    r[j] = kotlin.math.abs(dot4(qs, sq)) < 0.99999f
                    t[j] = kotlin.math.abs(m[12] - 123f) + kotlin.math.abs(m[13] - 456f) + kotlin.math.abs(m[14] - 789f) > 1e-2f
                }
                keyR[c] = r
                keyT[c] = t
            }
            restore(rest)
            core.captureFeet(this)
            restore(rest)
        } finally {
            tm.commitLocalTransformTransaction()
        }
        val keyed = clips.body.joinToString { c -> "${clipNames[c]}:${keyR[c]?.count { it }}r/${keyT[c]?.count { it }}t" }
        Log.i(TAG, "joints=${names.size} clips: ${clips.describe()}")
        Log.i(TAG, "keyed $keyed")
        Log.i(TAG, "frame ${core.describeFrame()}")
    }

    private fun restore(rest: Array<FloatArray>) {
        for (j in inst.indices) tm.setTransform(inst[j], rest[j])
    }

    override fun sample(clip: Int, time: Float, out: Pose) {
        val kr = keyR.getOrNull(clip)
        val kt = keyT.getOrNull(clip)
        val rest = core.skeleton.rest
        if (kr == null || kt == null) {
            out.copyFrom(rest)
            return
        }
        animator.applyAnimation(clip, time)
        for (j in inst.indices) {
            val r = kr[j]
            val t = kt[j]
            if (r || t) tm.getTransform(inst[j], m)
            if (r) {
                matToQuat(m, qs)
                qs.copyInto(out.q, 4 * j)
            } else {
                rest.q.copyInto(out.q, 4 * j, 4 * j, 4 * j + 4)
            }
            if (t) {
                out.t[3 * j] = m[12]; out.t[3 * j + 1] = m[13]; out.t[3 * j + 2] = m[14]
            } else {
                rest.t.copyInto(out.t, 3 * j, 3 * j, 3 * j + 3)
            }
        }
    }

    /**
     * Un fotograma: lee la cámara ([cameraEntity], 0 = la de por defecto),
     * corre [MashaAnimator.frame] y escribe las locales de todas las articulaciones.
     */
    fun frame(t: Float, dt: Float, input: MotionInput, cameraEntity: Int) {
        tm.getWorldTransform(rootInst, rootWorld)
        invertAffine(rootWorld, rootInv)
        val ci = if (cameraEntity != 0) tm.getInstance(cameraEntity) else 0
        if (ci != 0) {
            tm.getWorldTransform(ci, camWorld)
            p[0] = camWorld[12]; p[1] = camWorld[13]; p[2] = camWorld[14]
            point4(rootInv, p, camera)
        } else {
            core.config.look.defaultTarget.copyInto(camera)
        }
        tm.openLocalTransformTransaction()
        try {
            core.frame(t, dt, input, camera, this)
            val out = core.output
            val sk = core.skeleton
            for (j in inst.indices) {
                sk.local(out, j, m)
                tm.setTransform(inst[j], m)
            }
        } finally {
            tm.commitLocalTransformTransaction()
        }
    }

    private val face = FloatArray(3)
    private val eyeL = core.skeleton.index("masha:eye.L")
    private val eyeR = core.skeleton.index("masha:eye.R")
    private val eyeM = FloatArray(16)

    /** [MashaAnimator.faceCenter] en coordenadas del mundo (tras [frame]). No crea objetos. */
    fun faceWorld(out: FloatArray): Boolean {
        if (!core.faceCenter(face)) return false
        point4(rootWorld, face, out)
        return true
    }

    /**
     * Centros de los ojos en el mundo tal como se dibujan (transformación final de los huesos
     * `masha:eye.L/R`, con los gestos de cabeza ya puestos; tras [frame]): L en [out] 0..2, R en
     * 3..5. False sin huesos de ojos. No crea objetos.
     */
    fun eyesWorld(out: FloatArray): Boolean {
        if (eyeL < 0 || eyeR < 0) return false
        tm.getWorldTransform(inst[eyeL], eyeM)
        out[0] = eyeM[12]; out[1] = eyeM[13]; out[2] = eyeM[14]
        tm.getWorldTransform(inst[eyeR], eyeM)
        out[3] = eyeM[12]; out[4] = eyeM[13]; out[5] = eyeM[14]
        return true
    }

    private companion object {
        const val TAG = "MashaAnimator"
    }
}
