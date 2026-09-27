package com.elyndra.launcher.ui.masha

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Poses del esqueleto de Masha sin Filament (se prueban en la JVM): mezcla de
 * N clips con una pila de fundidos, inercialización, muelles críticos y la IK
 * de dos huesos de las piernas.
 *
 * Convenciones (las de SpringBones.kt): matrices 4x4 por columnas (Filament),
 * cuaterniones (x, y, z, w) (glTF). "Espacio del modelo" = el de la raíz del
 * asset: Y arriba, metros, +Z delante de ella, +X su izquierda. Nada aquí crea
 * objetos por fotograma.
 */

/** 0..1 → 0..1 con primera y segunda derivada nulas en los extremos. */
internal fun smootherstep(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * c * (c * (c * 6f - 15f) + 10f)
}

internal const val LN2 = 0.6931472f
internal const val DEG = (Math.PI / 180.0).toFloat()

/**
 * Una pose: rotación y traslación locales (respecto al padre) de cada
 * articulación. La escala es siempre la de reposo ([Skeleton.scale]).
 */
internal class Pose(val n: Int) {
    val q = FloatArray(4 * n)
    val t = FloatArray(3 * n)

    init {
        for (j in 0 until n) q[4 * j + 3] = 1f
    }

    fun copyFrom(o: Pose) {
        o.q.copyInto(q)
        o.t.copyInto(t)
    }
}

/** out = mezcla de [a] y [b] con peso [w] de [b] (nlerp por el camino corto). out puede ser a o b. */
internal fun blendPose(a: Pose, b: Pose, w: Float, out: Pose) {
    val u = 1f - w
    val aq = a.q; val bq = b.q; val oq = out.q
    for (j in 0 until a.n) {
        val o = 4 * j
        val d = aq[o] * bq[o] + aq[o + 1] * bq[o + 1] + aq[o + 2] * bq[o + 2] + aq[o + 3] * bq[o + 3]
        val wb = if (d < 0f) -w else w
        val x = aq[o] * u + bq[o] * wb
        val y = aq[o + 1] * u + bq[o + 1] * wb
        val z = aq[o + 2] * u + bq[o + 2] * wb
        val ww = aq[o + 3] * u + bq[o + 3] * wb
        val l = sqrt(x * x + y * y + z * z + ww * ww)
        val k = if (l > 1e-12f) 1f / l else 0f
        oq[o] = x * k; oq[o + 1] = y * k; oq[o + 2] = z * k; oq[o + 3] = if (l > 1e-12f) ww * k else 1f
    }
    val at = a.t; val bt = b.t; val ot = out.t
    for (i in 0 until 3 * a.n) ot[i] = at[i] * u + bt[i] * w
}

/** Logaritmo de un cuaternión unitario → vector de rotación (eje·ángulo), por el camino corto. */
internal fun quatLog(q: FloatArray, qo: Int, out: FloatArray, oo: Int) {
    var x = q[qo]; var y = q[qo + 1]; var z = q[qo + 2]; var w = q[qo + 3]
    if (w < 0f) { x = -x; y = -y; z = -z; w = -w }
    val s = sqrt(x * x + y * y + z * z)
    if (s < 1e-7f) {
        out[oo] = 2f * x; out[oo + 1] = 2f * y; out[oo + 2] = 2f * z
        return
    }
    val k = 2f * atan2(s, w) / s
    out[oo] = x * k; out[oo + 1] = y * k; out[oo + 2] = z * k
}

/** Exponencial: vector de rotación → cuaternión unitario. */
internal fun quatExp(x: Float, y: Float, z: Float, out: FloatArray) {
    val a = sqrt(x * x + y * y + z * z)
    if (a < 1e-7f) {
        out[0] = x * 0.5f; out[1] = y * 0.5f; out[2] = z * 0.5f; out[3] = 1f
        normQ(out)
        return
    }
    val s = sin(a * 0.5f) / a
    out[0] = x * s; out[1] = y * s; out[2] = z * s; out[3] = cos(a * 0.5f)
}

/** out = a·b con a y b en arrays con desplazamiento (out no puede solapar). */
internal fun quatMulAt(a: FloatArray, ao: Int, b: FloatArray, bo: Int, out: FloatArray, oo: Int) {
    val ax = a[ao]; val ay = a[ao + 1]; val az = a[ao + 2]; val aw = a[ao + 3]
    val bx = b[bo]; val by = b[bo + 1]; val bz = b[bo + 2]; val bw = b[bo + 3]
    out[oo] = aw * bx + ax * bw + ay * bz - az * by
    out[oo + 1] = aw * by - ax * bz + ay * bw + az * bx
    out[oo + 2] = aw * bz + ax * by - ay * bx + az * bw
    out[oo + 3] = aw * bw - ax * bx - ay * by - az * bz
}

/**
 * El esqueleto: nombres, padres (−1 si el padre no es una articulación de la
 * lista; su matriz del modelo va en [rootModel]) y la pose de reposo. Los
 * padres van siempre antes que los hijos.
 */
internal class Skeleton(
    val names: Array<String>,
    val parent: IntArray,
    restLocal: Array<FloatArray>,
    rootParentModel: Array<FloatArray?>,
) {
    val n = names.size
    val rest = Pose(n)
    /** Escala local de reposo (x, y, z) por articulación. */
    val scale = FloatArray(3 * n)
    /** Matriz del modelo del padre de cada raíz (identidad si no hay). */
    val rootModel = FloatArray(16 * n)
    private val m = FloatArray(16)
    private val qTmp = FloatArray(4)

    init {
        for (j in 0 until n) {
            require(parent[j] < j) { "el padre de ${names[j]} va después" }
            val r = restLocal[j]
            matToQuat(r, qTmp)
            qTmp.copyInto(rest.q, 4 * j)
            rest.t[3 * j] = r[12]; rest.t[3 * j + 1] = r[13]; rest.t[3 * j + 2] = r[14]
            scale[3 * j] = sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2])
            scale[3 * j + 1] = sqrt(r[4] * r[4] + r[5] * r[5] + r[6] * r[6])
            scale[3 * j + 2] = sqrt(r[8] * r[8] + r[9] * r[9] + r[10] * r[10])
            val p = rootParentModel[j]
            if (p != null) p.copyInto(rootModel, 16 * j) else {
                rootModel[16 * j] = 1f; rootModel[16 * j + 5] = 1f; rootModel[16 * j + 10] = 1f; rootModel[16 * j + 15] = 1f
            }
        }
    }

    fun index(name: String): Int = names.indexOf(name)

    /** Matriz local de [j] en [pose] (T·R·S de reposo). */
    fun local(pose: Pose, j: Int, out: FloatArray) {
        val o = 4 * j
        qTmp[0] = pose.q[o]; qTmp[1] = pose.q[o + 1]; qTmp[2] = pose.q[o + 2]; qTmp[3] = pose.q[o + 3]
        quatToMat(qTmp, out)
        val sx = scale[3 * j]; val sy = scale[3 * j + 1]; val sz = scale[3 * j + 2]
        for (i in 0..2) { out[i] *= sx; out[4 + i] *= sy; out[8 + i] *= sz }
        out[12] = pose.t[3 * j]; out[13] = pose.t[3 * j + 1]; out[14] = pose.t[3 * j + 2]
    }

    /** Cinemática directa: matriz del modelo de cada articulación en [out] (16·n). */
    fun fk(pose: Pose, out: FloatArray) {
        for (j in 0 until n) {
            local(pose, j, m)
            val p = parent[j]
            if (p < 0) mulAt(rootModel, 16 * j, m, 0, out, 16 * j) else mulAt(out, 16 * p, m, 0, out, 16 * j)
        }
    }

    /** Rotación (sin escala) de la matriz del modelo de [j]; −1 = la del padre de la raíz. */
    fun modelQuat(model: FloatArray, j: Int, out: FloatArray) {
        model.copyInto(m, 0, 16 * j, 16 * j + 16)
        matToQuat(m, out)
    }

    /** Rotación del padre de [j] en el modelo. */
    fun parentQuat(model: FloatArray, j: Int, out: FloatArray) {
        val p = parent[j]
        if (p >= 0) modelQuat(model, p, out) else {
            rootModel.copyInto(m, 0, 16 * j, 16 * j + 16)
            matToQuat(m, out)
        }
    }

    /** Posición en el modelo de [j]. */
    fun position(model: FloatArray, j: Int, out: FloatArray) {
        out[0] = model[16 * j + 12]; out[1] = model[16 * j + 13]; out[2] = model[16 * j + 14]
    }

    /** Pasa un desplazamiento del modelo al espacio del padre de [j] (para su traslación local). */
    fun toParent(model: FloatArray, j: Int, d: FloatArray) {
        val p = parent[j]
        if (p >= 0) model.copyInto(m, 0, 16 * p, 16 * p + 16) else rootModel.copyInto(m, 0, 16 * j, 16 * j + 16)
        invMul3(m, d, d)
    }

    companion object {
        /** Producto afín por columnas con desplazamientos: out(oo) = a(ao)·b(bo). out puede ser a (en otro tramo) pero no b. */
        fun mulAt(a: FloatArray, ao: Int, b: FloatArray, bo: Int, out: FloatArray, oo: Int) {
            for (c in 0..3) {
                val b0 = b[bo + c * 4]; val b1 = b[bo + c * 4 + 1]; val b2 = b[bo + c * 4 + 2]; val b3 = b[bo + c * 4 + 3]
                for (r in 0..3) {
                    out[oo + c * 4 + r] = a[ao + r] * b0 + a[ao + 4 + r] * b1 + a[ao + 8 + r] * b2 + a[ao + 12 + r] * b3
                }
            }
        }
    }
}

/**
 * Gira la articulación [j] de [pose] con [r] expresada en el espacio del
 * modelo, alrededor de su pivote: q' = P⁻¹·r·P·q, con P la rotación de su
 * padre en el modelo ([parentQ]).
 */
internal fun rotateInModel(pose: Pose, j: Int, r: FloatArray, parentQ: FloatArray, s1: FloatArray, s2: FloatArray) {
    conj(parentQ, s1)
    quatMul(s1, r, s2)
    quatMul(s2, parentQ, s1)
    val o = 4 * j
    quatMulAt(s1, 0, pose.q, o, s2, 0)
    normQ(s2)
    s2.copyInto(pose.q, o, 0, 4)
}

/** Muestrea un clip en la pose de salida (lo implementa el adaptador de Filament o una prueba). */
internal interface PoseSampler {
    fun sample(clip: Int, time: Float, out: Pose)
}

/**
 * Pila de fundidos: N clips a la vez, cada uno con su peso (smootherstep de
 * entrada, de salida y amplitud). La pose es el pliegue de abajo arriba:
 * `pose = mezcla(pose, clip_i, alfa_i)`. Una entrada nueva empieza con alfa 0 y
 * una que termina de salir llega a 0: así ningún cambio, ni a mitad de otro
 * fundido, salta.
 *
 * Hay dos clases de entradas: las "base" (reposo, escuchar, pensar, habla en
 * bucle) van siempre debajo de los gestos; cuando una base llega a alfa 1, las
 * bases de debajo sobran y se quitan. Los gestos (variaciones, saludo, golpes
 * del habla) van encima y salen con su fundido. Lo que queda debajo de una
 * entrada opaca no se muestrea.
 */
internal class BlendStack(val capacity: Int = 8) {

    class Entry {
        var id = 0
        var clip = -1
        var time = 0f
        var duration = 0f
        var speed = 1f
        var loop = false
        var base = false
        var amp = 1f
        var fadeIn = 0f
        var inT = 0f
        var fadeOut = -1f
        var outT = 0f
        /** Libre para quien la usa (tipo de gesto). */
        var tag = 0

        val fading: Boolean get() = fadeOut >= 0f
        val done: Boolean get() = !loop && time >= duration

        fun alpha(): Float {
            val a = if (fadeIn <= 0f) 1f else smootherstep(inT / fadeIn)
            val b = if (fadeOut < 0f) 1f else if (fadeOut <= 0f) 0f else 1f - smootherstep(outT / fadeOut)
            return amp * a * b
        }
    }

    private val items = Array(capacity) { Entry() }
    var size = 0
        private set
    private var nextId = 1

    /** Alguien cortó de golpe: el animador debe inercializar este fotograma. */
    var inertiaRequested = false

    operator fun get(i: Int): Entry = items[i]

    fun find(id: Int): Entry? {
        for (i in 0 until size) if (items[i].id == id) return items[i]
        return null
    }

    val full: Boolean get() = size >= capacity

    /**
     * Añade un clip. Las bases entran justo encima de la última base (debajo
     * de los gestos), los gestos arriba del todo. Si la pila está llena,
     * inercializa: se queda solo con este clip (base) o con la base de arriba
     * y este gesto. Devuelve el id.
     */
    fun push(
        clip: Int, duration: Float, time: Float, loop: Boolean, fade: Float,
        base: Boolean, amp: Float = 1f, speed: Float = 1f, tag: Int = 0,
    ): Int {
        if (full) {
            if (base) {
                clear()
                inertiaRequested = true
                return push(clip, duration, time, loop, 0f, true, amp, speed, tag)
            }
            // Deja la base visible más alta y nada más.
            var top = -1
            for (i in size - 1 downTo 0) if (items[i].base) { top = i; break }
            if (top > 0) removeRange(0, top)
            while (size > 1) removeAt(size - 1)
            inertiaRequested = true
            return push(clip, duration, time, loop, 0f, false, amp, speed, tag)
        }
        var index = size
        if (base) {
            index = 0
            for (i in size - 1 downTo 0) if (items[i].base) { index = i + 1; break }
        }
        // Desplaza hacia arriba reutilizando el objeto libre del final.
        val e = items[size]
        for (i in size downTo index + 1) items[i] = items[i - 1]
        items[index] = e
        size++
        e.id = nextId++
        e.clip = clip; e.duration = duration; e.time = time; e.loop = loop
        e.fadeIn = if (size == 1) 0f else fade
        e.inT = 0f; e.fadeOut = -1f; e.outT = 0f
        e.base = base; e.amp = amp; e.speed = speed; e.tag = tag
        return e.id
    }

    /** Empieza a sacar la entrada [id] en [fade] s (si ya salía antes, se queda con lo que acabe antes). */
    fun fadeOut(id: Int, fade: Float) {
        val e = find(id) ?: return
        if (e.fading && e.fadeOut - e.outT <= fade) return
        if (fade <= 0f) {
            remove(id)
            inertiaRequested = true
            return
        }
        // Entra en la salida desde el alfa que tenía (el producto no salta).
        e.fadeOut = fade
        e.outT = 0f
    }

    /** Quita la entrada ya (salta: quien llama debe querer inercializar). */
    fun remove(id: Int) {
        for (i in 0 until size) if (items[i].id == id) { removeAt(i); return }
    }

    fun clear() {
        size = 0
    }

    /** Corta a este clip de golpe (con inercialización). */
    fun cut(clip: Int, duration: Float, time: Float, loop: Boolean) {
        clear()
        inertiaRequested = true
        push(clip, duration, time, loop, 0f, true)
    }

    private fun removeAt(index: Int) {
        val e = items[index]
        for (i in index until size - 1) items[i] = items[i + 1]
        items[size - 1] = e
        size--
    }

    private fun removeRange(from: Int, to: Int) {
        repeat(to - from) { removeAt(from) }
    }

    /** Avanza relojes y fundidos; quita lo que ya no se ve. */
    fun update(dt: Float) {
        for (i in 0 until size) {
            val e = items[i]
            val t = e.time + dt * e.speed
            e.time = if (e.loop && e.duration > 0f) t % e.duration else min(t, e.duration)
            e.inT += dt
            if (e.fading) e.outT += dt
        }
        var i = 0
        while (i < size) {
            val e = items[i]
            if (e.fading && e.outT >= e.fadeOut && size > 1) removeAt(i) else i++
        }
        for (k in size - 1 downTo 1) {
            val e = items[k]
            if (e.base && !e.fading && e.alpha() >= 0.9999f) {
                removeRange(0, k)
                break
            }
        }
    }

    /** La entrada más alta que tapa todo lo de debajo. */
    fun opaqueFloor(): Int {
        for (i in size - 1 downTo 1) if (items[i].alpha() >= 0.9999f) return i
        return 0
    }

    /** Pliega la pila en [out] ([tmp] es de trabajo). Sin entradas, deja [out] como está. */
    fun evaluate(sampler: PoseSampler, out: Pose, tmp: Pose) {
        if (size == 0) return
        val k = opaqueFloor()
        sampler.sample(items[k].clip, items[k].time, out)
        for (i in k + 1 until size) {
            val a = items[i].alpha()
            if (a <= 1e-4f) continue
            sampler.sample(items[i].clip, items[i].time, tmp)
            blendPose(out, tmp, a, out)
        }
    }

    /** Peso con el que se ve la entrada [i] en la pose final (la suma de todas es 1). */
    fun visibleWeight(i: Int): Float {
        val k = opaqueFloor()
        if (i < k) return 0f
        var w = if (i == k) 1f else items[i].alpha()
        for (j in i + 1 until size) w *= 1f - items[j].alpha()
        return w
    }
}

/**
 * Inercialización por diferencia: al cortar de golpe, se guarda lo que separa
 * la última pose que se vio de la nueva (y su velocidad) y se deshace con un
 * muelle crítico de semivida [halfLife]:
 * x(t) = (x0 + (v0 + y·x0)·t)·e^(−y·t), y = 2·ln2/semivida.
 * Es analítico en t: da lo mismo a 30 que a 60 fps.
 */
internal class Inertializer(val n: Int) {
    private val x0 = FloatArray(6 * n)
    private val v0 = FloatArray(6 * n)
    private var elapsed = 0f
    private var y = 1f
    var active = false
        private set
    private val s = FloatArray(4)
    private val r = FloatArray(4)
    private val v = FloatArray(3)

    /**
     * Empieza desde la última pose vista [prev] (y la anterior, [prev2], [prevDt]
     * s antes, para su velocidad) hacia la nueva [target] de este fotograma.
     */
    fun begin(prev: Pose, prev2: Pose, prevDt: Float, target: Pose, halfLife: Float, maxSpeed: Float) {
        y = 2f * LN2 / max(halfLife, 1e-3f)
        elapsed = 0f
        active = true
        val velOk = prevDt > 1e-4f
        for (j in 0 until n) {
            val o = 4 * j
            // Rotación: log(prev·target⁻¹).
            conjAt(target.q, o, s)
            quatMulAt(prev.q, o, s, 0, r, 0)
            quatLog(r, 0, x0, 6 * j)
            // Velocidad angular de la pose que se deja: log(prev·prev2⁻¹)/dt.
            if (velOk) {
                conjAt(prev2.q, o, s)
                quatMulAt(prev.q, o, s, 0, r, 0)
                quatLog(r, 0, v, 0)
                for (k in 0..2) v0[6 * j + k] = (v[k] / prevDt).coerceIn(-maxSpeed, maxSpeed)
            } else {
                for (k in 0..2) v0[6 * j + k] = 0f
            }
            for (k in 0..2) {
                val i = 3 * j + k
                x0[6 * j + 3 + k] = prev.t[i] - target.t[i]
                v0[6 * j + 3 + k] = if (velOk) ((prev.t[i] - prev2.t[i]) / prevDt).coerceIn(-maxSpeed, maxSpeed) else 0f
            }
        }
    }

    private fun conjAt(q: FloatArray, o: Int, out: FloatArray) {
        out[0] = -q[o]; out[1] = -q[o + 1]; out[2] = -q[o + 2]; out[3] = q[o + 3]
    }

    /** Avanza [dt] y suma lo que queda de la diferencia a [pose]. */
    fun apply(pose: Pose, dt: Float) {
        if (!active) return
        elapsed += dt
        val t = elapsed
        val e = exp(-y * t)
        if (e < 1e-4f) {
            active = false
            return
        }
        for (j in 0 until n) {
            val b = 6 * j
            val rx = (x0[b] + (v0[b] + y * x0[b]) * t) * e
            val ry = (x0[b + 1] + (v0[b + 1] + y * x0[b + 1]) * t) * e
            val rz = (x0[b + 2] + (v0[b + 2] + y * x0[b + 2]) * t) * e
            quatExp(rx, ry, rz, r)
            val o = 4 * j
            quatMulAt(r, 0, pose.q, o, s, 0)
            normQ(s)
            s.copyInto(pose.q, o, 0, 4)
            for (k in 0..2) pose.t[3 * j + k] += (x0[b + 3 + k] + (v0[b + 3 + k] + y * x0[b + 3 + k]) * t) * e
        }
    }

    /** Lo que queda de la diferencia (rad, el mayor de todas las articulaciones), para pruebas. */
    fun remaining(): Float {
        if (!active) return 0f
        val t = elapsed
        val e = exp(-y * t)
        var worst = 0f
        for (j in 0 until n) {
            val b = 6 * j
            for (k in 0..2) worst = max(worst, abs((x0[b + k] + (v0[b + k] + y * x0[b + k]) * t) * e))
        }
        return worst
    }

    fun reset() {
        active = false
    }
}

/**
 * Muelle crítico exacto (sin rebote) hacia una meta: con la meta quieta
 * durante el fotograma, el resultado no depende de la frecuencia de fotogramas.
 */
internal class CritSpring {
    var x = 0f
    var v = 0f

    fun update(goal: Float, halfLife: Float, dt: Float): Float {
        val y = 2f * LN2 / max(halfLife, 1e-4f)
        val j0 = x - goal
        val j1 = v + j0 * y
        val e = exp(-y * dt)
        x = e * (j0 + j1 * dt) + goal
        v = e * (v - j1 * y * dt)
        return x
    }

    fun reset(value: Float = 0f) {
        x = value
        v = 0f
    }
}

/**
 * IK analítica de dos huesos (cadera–rodilla–tobillo) en el espacio del modelo.
 *
 * Con A, B, C (cadera, rodilla, tobillo) y la meta T, deja la rodilla en el
 * plano de A→T y de la rodilla actual (el "polo": la dirección a la que ya
 * dobla; si la pierna está recta, [solve] usa la pista que se le dé), con la
 * ley de cosenos. El alcance se limita con una zona suave: nunca estira más
 * de [FootIkConfig.maxReach] de la pierna (salvo que el clip ya la tenga así).
 * Devuelve dos rotaciones del modelo: la del muslo (en A) y la de la
 * espinilla (en B, aplicada después).
 */
internal class TwoBoneIk {
    private val ab = FloatArray(3)
    private val bc = FloatArray(3)
    private val u = FloatArray(3)
    private val p = FloatArray(3)
    private val nb = FloatArray(3)
    private val nc = FloatArray(3)
    private val d1 = FloatArray(3)
    private val d2 = FloatArray(3)
    private val rq = FloatArray(4)

    /** Distancia cadera→tobillo que se usó (tras el límite), para pruebas. */
    var reach = 0f
        private set

    fun solve(
        a: FloatArray, b: FloatArray, c: FloatArray, target: FloatArray, poleHint: FloatArray,
        maxReach: Float, softness: Float, outRa: FloatArray, outRb: FloatArray,
    ) {
        for (i in 0..2) { ab[i] = b[i] - a[i]; bc[i] = c[i] - b[i]; u[i] = target[i] - a[i] }
        val l1 = sqrt(dot3(ab, ab))
        val l2 = sqrt(dot3(bc, bc))
        val len = l1 + l2
        var d = norm3(u)
        if (l1 < 1e-5f || l2 < 1e-5f || d < 1e-5f) {
            identity(outRa); identity(outRb)
            reach = d
            return
        }
        // Alcance: suave hasta el límite, que es el mayor entre maxReach y lo que ya estira el clip.
        val dClip = sqrt((c[0] - a[0]).let { it * it } + (c[1] - a[1]).let { it * it } + (c[2] - a[2]).let { it * it })
        val w = max(softness * len, 1e-4f)
        val start = max(maxReach * len - w, min(dClip, 0.999f * len))
        val limit = min(max(maxReach * len, start + 0.25f * w), 0.9999f * len)
        if (d > start && limit > start) {
            val k = limit - start
            d = start + k * (1f - exp(-(d - start) / k))
        }
        d = d.coerceIn(abs(l1 - l2) + 1e-4f, 0.9999f * len)
        reach = d
        // Polo: la componente de A→B perpendicular a A→T.
        val pd = dot3(ab, u)
        for (i in 0..2) p[i] = ab[i] - u[i] * pd
        if (norm3(p) < 1e-4f) {
            val hd = dot3(poleHint, u)
            for (i in 0..2) p[i] = poleHint[i] - u[i] * hd
            if (norm3(p) < 1e-4f) { p[0] = 0f; p[1] = 0f; p[2] = 1f }
        }
        val cosA = ((l1 * l1 + d * d - l2 * l2) / (2f * l1 * d)).coerceIn(-1f, 1f)
        val sinA = sqrt(1f - cosA * cosA)
        // Rodilla nueva (relativa a A) y tobillo nuevo.
        for (i in 0..2) {
            nb[i] = (u[i] * cosA + p[i] * sinA) * l1
            nc[i] = u[i] * d
        }
        // Muslo: A→B pasa a A→B'.
        d1[0] = ab[0] / l1; d1[1] = ab[1] / l1; d1[2] = ab[2] / l1
        d2[0] = nb[0] / l1; d2[1] = nb[1] / l1; d2[2] = nb[2] / l1
        fromTo(d1, d2, outRa)
        // Espinilla, ya girada con el muslo: pasa a B'→C'.
        quatRotate(outRa, bc, d1)
        norm3(d1)
        for (i in 0..2) d2[i] = nc[i] - nb[i]
        norm3(d2)
        fromTo(d1, d2, outRb)
    }

    private fun identity(q: FloatArray) {
        q[0] = 0f; q[1] = 0f; q[2] = 0f; q[3] = 1f
    }

    /** Para pruebas: dónde queda el tobillo tras aplicar [ra] en A y [rb] en B. */
    fun ankleAfter(a: FloatArray, b: FloatArray, c: FloatArray, ra: FloatArray, rb: FloatArray, out: FloatArray) {
        for (i in 0..2) { ab[i] = b[i] - a[i]; bc[i] = c[i] - b[i] }
        quatRotate(ra, ab, d1)
        quatMul(rb, ra, rq)
        quatRotate(rq, bc, d2)
        for (i in 0..2) out[i] = a[i] + d1[i] + d2[i]
    }
}
