package com.elyndra.launcher.ui.masha

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * JVM tests of the secondary-motion core on a synthetic rig that mirrors the v2 Masha rig
 * (positions from the Blender build, converted to glTF Y-up, +Z front).
 */
class SpringBonesTest {

    /* ── matrix helpers ── */

    private fun ident() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    private fun trs(tx: Float, ty: Float, tz: Float, q: FloatArray = floatArrayOf(0f, 0f, 0f, 1f)): FloatArray {
        val m = FloatArray(16)
        quatToMat(q, m)
        m[12] = tx; m[13] = ty; m[14] = tz
        return m
    }

    private fun aa(x: Float, y: Float, z: Float, deg: Float): FloatArray {
        val l = sqrt(x * x + y * y + z * z)
        return FloatArray(4).also { axisAngle(x / l, y / l, z / l, deg * PI.toFloat() / 180f, it) }
    }

    private fun qmul(a: FloatArray, b: FloatArray) = FloatArray(4).also { quatMul(a, b, it) }

    private fun quatOf(m: FloatArray) = FloatArray(4).also { matToQuat(m, it) }

    /** Angle (deg) of the rotation between two local matrices' rotations. */
    private fun angleBetween(a: FloatArray, b: FloatArray): Float {
        val qa = quatOf(a); val qb = quatOf(b)
        val d = abs(dot4(qa, qb)).coerceAtMost(1f)
        return (2.0 * acos(d.toDouble()) * 180.0 / PI).toFloat()
    }

    private fun finite(a: FloatArray) = a.all { it.isFinite() }

    /* ── soft tissue rig ── */

    private val spine2Rest = trs(0f, 1.35f, 0.012f)
    // Breast bone: head 5 cm in front of Spine2 level; its +Y points forward (+Z).
    private val breastRest = trs(0.097f, -0.05f, 0.07f, aa(1f, 0f, 0f, -90f).let { q -> FloatArray(4).also { conj(q, it) } })

    private class Soft(val sb: SpringBones, val bone: SpringBones.SoftBone)

    private fun softRig(config: SpringConfig = SpringConfig(), cfg: SoftTissueConfig = config.chest): Soft {
        val sb = SpringBones(config)
        val d = sb.addDriver(spine2Rest)
        return Soft(sb, sb.addSoftTissue(d, breastRest, cfg))
    }

    /** Parent (chest) model matrix for a body motion: translation (x,y,z) and yaw/pitch (deg). */
    private fun chest(out: FloatArray, x: Float, y: Float, z: Float, yaw: Float = 0f, pitch: Float = 0f) {
        val q = qmul(aa(0f, 1f, 0f, yaw), aa(1f, 0f, 0f, pitch))
        val m = trs(spine2Rest[12] + x, spine2Rest[13] + y, spine2Rest[14] + z, q)
        m.copyInto(out)
    }

    private fun sway(t: Float, out: FloatArray) {
        val w = 2f * PI.toFloat()
        chest(
            out,
            x = 0.04f * sin(w * 0.9f * t),
            y = 0.02f * sin(w * 1.8f * t + 0.3f),
            z = 0.03f * sin(w * 0.6f * t),
            yaw = 25f * sin(w * 0.5f * t),
            pitch = 8f * sin(w * 0.35f * t),
        )
    }

    private fun len(v: FloatArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    /** Mechanical energy of the spring (per unit mass) about x = 0. */
    private fun energy(b: SpringBones.SoftBone): Float {
        val w = 2f * PI.toFloat() * b.cfg.frequencyHz
        return 0.5f * w * w * (b.x[0] * b.x[0] + b.x[1] * b.x[1] + b.x[2] * b.x[2]) +
            0.5f * (b.u[0] * b.u[0] + b.u[1] * b.u[1] + b.u[2] * b.u[2])
    }

    @Test
    fun `energy decays after an impulse`() {
        val s = softRig()
        val m = s.sb.driver(0)
        chest(m, 0f, 0f, 0f)
        repeat(3) { s.sb.advance(1f / 60f) }
        chest(m, 0f, 0.02f, 0f) // 2 cm hop in one frame, then still
        s.sb.advance(1f / 60f)
        var last = energy(s.bone)
        assertTrue(last > 0f)
        for (k in 1..6) { // energy per 1/6 s window never grows
            repeat(10) { s.sb.advance(1f / 60f) }
            val e = energy(s.bone)
            assertTrue("window $k: $e > $last", e <= last * 1.0001f)
            last = e
        }
        repeat(60) { s.sb.advance(1f / 60f) }
        assertTrue("energy after 2 s ${energy(s.bone)}", energy(s.bone) < 1e-9f)
    }

    /** Runs [seconds] with a dt generator; motion stops at [stopAt]. Returns the max |x| seen. */
    private fun runSoft(s: Soft, seconds: Float, stopAt: Float, dtOf: (Int) -> Float, check: (Float) -> Unit = {}): Float {
        var t = 0f
        var i = 0
        var maxX = 0f
        val m = s.sb.driver(0)
        while (t < seconds) {
            val dt = dtOf(i++)
            t += dt
            sway(if (t < stopAt) t else stopAt, m)
            s.sb.advance(dt)
            assertTrue("NaN at t=$t", finite(s.bone.x) && finite(s.bone.u) && finite(s.bone.out))
            maxX = max(maxX, len(s.bone.x))
            check(t)
        }
        return maxX
    }

    @Test
    fun `soft tissue is stable at 30, 60 and 120 fps and settles when the body stops`() {
        for (fps in listOf(30f, 60f, 120f)) {
            val s = softRig()
            val maxX = runSoft(s, 60f, stopAt = 55f, dtOf = { 1f / fps })
            assertTrue("fps=$fps max ${maxX}", maxX <= s.bone.cfg.maxDisplacement + 1e-6f)
            assertTrue("fps=$fps: moved at all ($maxX)", maxX > 1e-4f)
            assertTrue("fps=$fps: must come to rest, |v| = ${len(s.bone.u)}", len(s.bone.u) < 1e-5f)
        }
    }

    @Test
    fun `soft tissue survives random dt jitter and hitches`() {
        val rnd = Random(42)
        val s = softRig()
        runSoft(s, 60f, stopAt = 54f, dtOf = {
            when {
                rnd.nextFloat() < 0.01f -> 0.12f            // hitch > maxSubsteps·h: time dropped
                rnd.nextFloat() < 0.002f -> 0.6f            // resume: teleport
                else -> 0.004f + rnd.nextFloat() * 0.04f   // 25–250 fps jitter
            }
        })
        assertTrue("must come to rest, |v| = ${len(s.bone.u)}", len(s.bone.u) < 1e-5f)
    }

    @Test
    fun `same response at 30 and 60 fps (fixed step)`() {
        fun trace(fps: Int): FloatArray {
            val s = softRig()
            val out = FloatArray(300)
            val m = s.sb.driver(0)
            val steps = 10 * fps
            var k = 0
            for (i in 1..steps) {
                val t = i / fps.toFloat()
                sway(t, m)
                s.sb.advance(1f / fps)
                if (i % (fps / 30) == 0) out[k++] = s.bone.x[1] // sample at 30 Hz
            }
            return out
        }
        val a = trace(30)
        val b = trace(60)
        val c = trace(120)
        val peak = a.maxOf { abs(it) }
        var err60 = 0f
        var err120 = 0f
        for (i in 30 until a.size) { // skip 1 s: start-up transient depends on the teleport frame
            err60 = max(err60, abs(a[i] - b[i]))
            err120 = max(err120, abs(b[i] - c[i]))
        }
        assertTrue("peak $peak", peak > 1e-4f)
        assertTrue("30 vs 60: ${err60 / peak}", err60 < 0.06f * peak)
        assertTrue("60 vs 120: ${err120 / peak}", err120 < 0.03f * peak)
    }

    /** Step of acceleration: the chest starts accelerating at 1.5 m/s² along −Z (backwards). */
    private fun stepTrace(fps: Int, cfg: SoftTissueConfig, seconds: Float = 2f): Pair<FloatArray, Float> {
        val s = softRig(cfg = cfg)
        val m = s.sb.driver(0)
        val a = 1.5f
        val n = (seconds * fps).toInt()
        val zs = FloatArray(n)
        chest(m, 0f, 0f, 0f)
        repeat(3) { s.sb.advance(1f / fps) } // teleport + prime at rest
        for (i in 0 until n) {
            val t = (i + 1) / fps.toFloat()
            chest(m, 0f, 0f, -0.5f * a * t * t)
            s.sb.advance(1f / fps)
            zs[i] = s.bone.x[2]
        }
        val w = 2 * PI.toFloat() * cfg.frequencyHz
        return zs to (a / (w * w)) // equilibrium: tissue lags forward by a/ω²
    }

    @Test
    fun `step response overshoot matches damping and settles under a second`() {
        for (cfg in listOf(SoftTissueConfig.CHEST, SoftTissueConfig.GLUTE)) {
            val (zs, eq) = stepTrace(60, cfg)
            val peak = zs.max()
            val overshoot = (peak - eq) / eq
            val zeta = cfg.dampingRatio.toDouble()
            val expected = kotlin.math.exp(-zeta * PI / sqrt(1 - zeta * zeta)).toFloat()
            assertTrue("overshoot $overshoot", overshoot < 0.25f)
            assertEquals("overshoot vs theory (ζ=$zeta)", expected, overshoot, 0.05f)
            // settled within 5 % of equilibrium after 1 s
            for (i in 60 until zs.size) assertEquals("t=${i / 60f}", eq, zs[i], 0.05f * eq)
        }
    }

    @Test
    fun `clamp is respected under violent motion`() {
        val s = softRig()
        val m = s.sb.driver(0)
        val cfg = s.bone.cfg
        for (i in 1..600) {
            val t = i / 60f
            // 12 cm shakes at 4 Hz (≈ 75 m/s²) with 60° twists
            chest(m, 0.12f * sin(25f * t), 0.12f * cos(23f * t), 0f, yaw = 60f * sin(20f * t))
            s.sb.advance(1f / 60f)
            assertTrue(len(s.bone.x) <= cfg.maxDisplacement + 1e-6f)
            assertTrue("angle ${angleBetween(s.bone.out, breastRest)}", angleBetween(s.bone.out, breastRest) <= cfg.maxAngleDeg + 0.01f)
            val dx = s.bone.out[12] - breastRest[12]; val dy = s.bone.out[13] - breastRest[13]; val dz = s.bone.out[14] - breastRest[14]
            assertTrue(sqrt(dx * dx + dy * dy + dz * dz) <= cfg.maxTranslation + 1e-6f)
        }
    }

    @Test
    fun `intensity 0 and disabled give exactly the rest pose`() {
        for (config in listOf(SpringConfig(intensity = 0f), SpringConfig(enabled = false))) {
            val s = softRig(config)
            val m = s.sb.driver(0)
            for (i in 1..300) {
                sway(i / 60f, m)
                s.sb.advance(1f / 60f)
                assertArrayEquals(breastRest, s.bone.out, 0f)
            }
        }
    }

    @Test
    fun `leaning forward sags the tissue a little, standing at rest does nothing`() {
        val s = softRig()
        val m = s.sb.driver(0)
        chest(m, 0f, 0f, 0f)
        repeat(120) { s.sb.advance(1f / 60f) }
        assertArrayEquals(breastRest, s.bone.out, 1e-6f)
        chest(m, 0f, 0f, 0f, pitch = 30f) // bend forward
        repeat(3) { s.sb.advance(0.3f) }   // teleport, then settle
        repeat(240) { s.sb.advance(1f / 60f) }
        val sag = len(s.bone.x)
        assertTrue("sag $sag", sag in 0.001f..0.008f)
    }

    @Test
    fun `a long gap teleports to rest instead of simulating the jump`() {
        val s = softRig()
        val m = s.sb.driver(0)
        runSoft(s, 3f, stopAt = 10f, dtOf = { 1f / 60f })
        chest(m, 1f, 0f, 2f, yaw = 90f) // jumped while paused (upright: no gravity offset)
        s.sb.advance(5f)
        assertEquals(0f, len(s.bone.x), 1e-7f)
        assertEquals(0f, len(s.bone.u), 0f)
        s.sb.advance(1f / 60f)
        assertEquals(0f, len(s.bone.x), 1e-6f)
        // resuming bent forward lands on the gravity equilibrium: nothing moves afterwards
        chest(m, 0f, 0f, 0f, pitch = 30f)
        s.sb.advance(5f)
        val x0 = s.bone.x.copyOf()
        assertTrue(len(x0) > 1e-4f)
        repeat(30) { s.sb.advance(1f / 60f) }
        for (a in 0..2) assertEquals(x0[a], s.bone.x[a], 2e-5f)
    }

    /* ── hair ── */

    private val headRest = trs(0f, 1.572f, 0.026f)
    private val skullOffset = floatArrayOf(0f, 0.09f, 0.005f)
    private val skullRadius = 0.095f

    private class Hair(val sb: SpringBones, val chain: SpringBones.HairChain, val skull: SpringBones.Collider)

    private fun hairRig(cfg: HairConfig = HairConfig(), config: SpringConfig = SpringConfig(hair = cfg)): Hair {
        val sb = SpringBones(config)
        val head = sb.addDriver(headRest)
        val neck = sb.addDriver(trs(0f, 1.501f, 0.001f))
        val skull = sb.addCollider(head, skullOffset, head, skullOffset, skullRadius, sphere = true)
        sb.addCollider(neck, floatArrayOf(0f, 0f, 0f), head, floatArrayOf(0f, 0f, 0f), 0.05f, sphere = false)
        // hair.0: tie at the back of the head, pointing down and slightly back
        val dir = floatArrayOf(0f, -0.98f, -0.2f).also { norm3(it) }
        val q0 = FloatArray(4).also { fromTo(floatArrayOf(0f, 1f, 0f), dir, it) }
        val bones = listOf(
            trs(0f, 0.033f, -0.100f, q0),
            trs(0f, 0.048f, 0f, aa(1f, 0f, 0f, 10f)),
            trs(0f, 0.053f, 0f, aa(1f, 0f, 0f, 3f)),
            trs(0f, 0.054f, 0f, aa(1f, 0f, 0f, 1f)),
        )
        val chain = sb.addHairChain(head, bones, cfg)
        return Hair(sb, chain, skull)
    }

    private fun head(out: FloatArray, yaw: Float, pitch: Float, roll: Float = 0f, dx: Float = 0f) {
        val q = qmul(qmul(aa(0f, 1f, 0f, yaw), aa(1f, 0f, 0f, pitch)), aa(0f, 0f, 1f, roll))
        trs(headRest[12] + dx, headRest[13], headRest[14], q).copyInto(out)
    }

    private fun headMotion(t: Float, out: FloatArray) {
        val w = 2f * PI.toFloat()
        head(out, yaw = 60f * sin(w * 0.8f * t), pitch = -35f + 30f * sin(w * 0.55f * t), roll = 25f * sin(w * 0.4f * t), dx = 0.05f * sin(w * 1.3f * t))
    }

    private fun minSkullClearance(h: Hair): Float {
        val c = FloatArray(3)
        point4(h.sb.driver(0), FloatArray(3).also { invMul3(headRest, skullOffset, it) }, c)
        var d = Float.MAX_VALUE
        for (i in 1..h.chain.n) {
            val o = i * 3
            val dx = h.chain.pos[o] - c[0]; val dy = h.chain.pos[o + 1] - c[1]; val dz = h.chain.pos[o + 2] - c[2]
            d = minOf(d, sqrt(dx * dx + dy * dy + dz * dz) - h.skull.radius)
        }
        return d
    }

    @Test
    fun `hair never penetrates the skull, even with heavy gravity and wild head motion`() {
        // gravity 1.0 (≈ 60 m/s²) overpowers the stiffness so the ponytail falls onto the head.
        val h = hairRig(HairConfig(gravity = 1.0f, stiffnessRoot = 0.2f, stiffnessTip = 0.1f, drag = 0.2f))
        assertEquals("rest pose should not need auto-fit", skullRadius, h.skull.radius, 1e-6f)
        val m = h.sb.driver(0)
        var worst = Float.MAX_VALUE
        for (i in 1..(20 * 60)) {
            headMotion(i / 60f, m)
            h.sb.advance(1f / 60f)
            worst = minOf(worst, minSkullClearance(h))
            assertTrue(finite(h.chain.pos))
        }
        assertTrue("clearance $worst", worst >= h.chain.cfg.hairRadius - 0.001f)
    }

    @Test
    fun `hair is stable with jitter at any fps and comes back to rest`() {
        val rnd = Random(3)
        for (mode in 0..3) {
            val h = hairRig()
            val m = h.sb.driver(0)
            head(m, 0f, 0f)
            h.sb.advance(1f / 60f)
            val rest = h.chain.pos.copyOf()
            var t = 0f
            while (t < 60f) {
                val dt = when (mode) {
                    0 -> 1f / 30f
                    1 -> 1f / 60f
                    2 -> 1f / 120f
                    else -> if (rnd.nextFloat() < 0.01f) 0.15f else 0.004f + rnd.nextFloat() * 0.04f
                }
                t += dt
                if (t < 54f) headMotion(t, m) else head(m, 0f, 0f)
                h.sb.advance(dt)
                assertTrue(finite(h.chain.pos))
                for (k in 0 until h.chain.n) assertTrue(finite(h.chain.out[k]))
            }
            var dev = 0f
            for (i in rest.indices) dev = max(dev, abs(rest[i] - h.chain.pos[i]))
            assertTrue("mode $mode deviation $dev", dev < 0.002f)
        }
    }

    @Test
    fun `hair response is frame-rate independent`() {
        fun tip(fps: Int): FloatArray {
            val h = hairRig()
            val m = h.sb.driver(0)
            val out = FloatArray(90)
            var k = 0
            for (i in 1..(3 * fps)) {
                val t = i / fps.toFloat()
                // fast but finite turn (0.15 s ramp)
                val ramp = ((t - 0.5f) / 0.15f).coerceIn(0f, 1f)
                head(m, yaw = 50f * ramp, pitch = 0f)
                h.sb.advance(1f / fps)
                if (i % (fps / 30) == 0) out[k++] = h.chain.pos[h.chain.n * 3]
            }
            return out
        }
        val a = tip(30); val b = tip(60); val c = tip(120)
        for (i in a.indices) {
            assertEquals("30 vs 60 at $i", a[i], b[i], 0.006f)
            assertEquals("60 vs 120 at $i", b[i], c[i], 0.006f)
        }
    }

    @Test
    fun `hair at intensity 0 stays exactly at rest`() {
        val h = hairRig(config = SpringConfig(intensity = 0f))
        val m = h.sb.driver(0)
        for (i in 1..200) {
            headMotion(i / 60f, m)
            h.sb.advance(1f / 60f)
            for (k in 0 until h.chain.n) assertArrayEquals(h.chain.restLocal[k], h.chain.out[k], 0f)
        }
    }

    @Test
    fun `auto-fit shrinks a collider that intersects the hair at rest`() {
        val sb = SpringBones(SpringConfig())
        val head = sb.addDriver(headRest)
        val big = sb.addCollider(head, skullOffset, head, skullOffset, 0.2f, sphere = true)
        val dir = floatArrayOf(0f, -0.98f, -0.2f).also { norm3(it) }
        val q0 = FloatArray(4).also { fromTo(floatArrayOf(0f, 1f, 0f), dir, it) }
        sb.addHairChain(head, listOf(trs(0f, 0.033f, -0.1f, q0), trs(0f, 0.05f, 0f)), HairConfig())
        assertTrue("radius ${big.radius}", big.radius < 0.2f && big.radius > 0.05f)
    }

    /* ── twist ── */

    // Real v2 data: hand rest rotation in the forearm (glTF x,y,z,w) and translation.
    private val handRestQ = floatArrayOf(0.041088f, 0.508947f, -0.031334f, 0.859246f).also { normQ(it) }
    private val handRest = trs(0f, 0.2234f, 0f, handRestQ)
    private val midRest = trs(0f, 0.067f, 0f)
    private val twRest = trs(0f, 0.1385f, 0f)

    private fun twistDeg(m: FloatArray, rest: FloatArray) = angleBetween(m, rest)

    private fun rotY(m: FloatArray, rest: FloatArray): Float {
        // signed rotation of m relative to rest about Y
        val q = qmul(quatOf(m), FloatArray(4).also { conj(quatOf(rest), it) })
        return twistAngle(q, floatArrayOf(0f, 1f, 0f)) * 180f / PI.toFloat()
    }

    @Test
    fun `hand roll of 90 degrees gives 30 on mid and 60 on twist`() {
        val rig = ForearmTwist(handRest, midRest, twRest, 1f / 3f, 2f / 3f)
        val mid = FloatArray(16); val tw = FloatArray(16)
        // roll about the forearm axis (in the forearm frame), on top of the rest rotation
        val hand = trs(0f, 0.2234f, 0f, qmul(aa(0f, 1f, 0f, 90f), handRestQ))
        rig.compute(hand, mid, tw)
        assertEquals(30f, rotY(mid, midRest), 0.01f)
        assertEquals(60f, rotY(tw, twRest), 0.01f)
        assertEquals(30f, twistDeg(mid, midRest), 0.01f) // and nothing but twist
        // translations untouched
        assertEquals(midRest[13], mid[13], 1e-6f)
        assertEquals(twRest[13], tw[13], 1e-6f)
        // negative roll
        rig.compute(trs(0f, 0.2234f, 0f, qmul(aa(0f, 1f, 0f, -45f), handRestQ)), mid, tw)
        assertEquals(-15f, rotY(mid, midRest), 0.01f)
        assertEquals(-30f, rotY(tw, twRest), 0.01f)
    }

    @Test
    fun `pure flexion and deviation give no twist`() {
        val rig = ForearmTwist(handRest, midRest, twRest, 1f / 3f, 2f / 3f)
        val mid = FloatArray(16); val tw = FloatArray(16)
        // Pose in the hand's own frame (glTF local = rest · pose): +X flexion, ±Z deviation.
        for (deg in listOf(-60f, -20f, 20f, 70f)) {
            rig.compute(trs(0f, 0.2234f, 0f, qmul(handRestQ, aa(1f, 0f, 0f, deg))), mid, tw)
            // the real hand bone is ~5° off the forearm axis, so allow a sliver of twist
            assertEquals("flexion $deg", 0f, rotY(tw, twRest), 1.5f)
            rig.compute(trs(0f, 0.2234f, 0f, qmul(handRestQ, aa(0f, 0f, 1f, deg * 0.5f))), mid, tw)
            // radial/ulnar deviation leaks a little more (the hand's Z is ~6° off ⟂ to the axis)
            assertEquals("deviation $deg", 0f, rotY(tw, twRest), 2.5f)
        }
        // With a clean rest (hand exactly on the axis) it is exactly zero.
        val clean = trs(0f, 0.2234f, 0f, aa(0f, 1f, 0f, 61f))
        val rig2 = ForearmTwist(clean, midRest, twRest, 1f / 3f, 2f / 3f)
        rig2.compute(trs(0f, 0.2234f, 0f, qmul(aa(0f, 1f, 0f, 61f), aa(1f, 0f, 0f, 50f))), mid, tw)
        assertEquals(0f, rotY(tw, twRest), 1e-3f)
        assertEquals(0f, twistDeg(mid, midRest), 1e-2f)
    }

    @Test
    fun `roll is recovered when combined with flexion`() {
        val rig = ForearmTwist(handRest, midRest, twRest, 1f / 3f, 2f / 3f)
        val mid = FloatArray(16); val tw = FloatArray(16)
        val q = qmul(qmul(aa(0f, 1f, 0f, 60f), handRestQ), aa(1f, 0f, 0f, 30f))
        rig.compute(trs(0f, 0.2234f, 0f, q), mid, tw)
        assertEquals(40f, rotY(tw, twRest), 2f)
    }

    @Test
    fun `upper arm twist counter-rotates half the arm roll`() {
        val armRestQ = floatArrayOf(0.0928f, -0.2356f, -0.0704f, 0.9648f).also { normQ(it) }
        val armRest = trs(0f, 0.117f, 0f, armRestQ)
        val foreRest = trs(0f, 0.2426f, 0f, aa(0.77f, -0.03f, 0.64f, 38f))
        val utRest = trs(0f, 0f, 0f)
        val rig = UpperArmTwist(armRest, foreRest, utRest, -0.5f)
        val out = FloatArray(16)
        // roll 40° about its own bone axis (+Y local), plus a swing (raise the arm)
        rig.compute(trs(0f, 0.117f, 0f, qmul(armRestQ, qmul(aa(0f, 0f, 1f, 35f), aa(0f, 1f, 0f, 40f)))), out)
        assertEquals(-20f, rotY(out, utRest), 0.05f)
        rig.compute(armRest, out)
        assertEquals(0f, twistDeg(out, utRest), 1e-2f)
    }

    /* ── plots (only when SPRING_PLOT_DIR is set) ── */

    @Test
    fun `dump response traces for plotting`() {
        val dir = System.getenv("SPRING_PLOT_DIR") ?: return
        File(dir).mkdirs()
        // 1) acceleration step (chest & glute), 60 fps
        File(dir, "step.csv").printWriter().use { w ->
            val (c, ce) = stepTrace(60, SoftTissueConfig.CHEST)
            val (g, ge) = stepTrace(60, SoftTissueConfig.GLUTE)
            w.println("t,chest_mm,chest_eq_mm,glute_mm,glute_eq_mm")
            for (i in c.indices) w.println("${(i + 1) / 60f},${c[i] * 1000},${ce * 1000},${g[i] * 1000},${ge * 1000}")
        }
        // 2) "turn and stop": chest yaws 60° in 0.4 s then stops; hop: 3 cm dip+rise; 30 vs 60 fps
        File(dir, "turn.csv").printWriter().use { w ->
            w.println("t,fps,x_mm,y_mm,z_mm,angle_deg,trans_mm")
            for (fps in listOf(30, 60)) {
                val s = softRig()
                val m = s.sb.driver(0)
                for (i in 0..(3 * fps)) {
                    val t = i / fps.toFloat()
                    val r = ((t - 0.3f) / 0.4f).coerceIn(0f, 1f)
                    val e = r * r * (3 - 2 * r)
                    val hop = if (t in 1.6f..2.0f) -0.03f * sin(PI.toFloat() * (t - 1.6f) / 0.4f) else 0f
                    chest(m, 0f, hop, 0f, yaw = 60f * e)
                    s.sb.advance(1f / fps)
                    val o = s.bone.out
                    val tr = sqrt((o[12] - breastRest[12]).let { it * it } + (o[13] - breastRest[13]).let { it * it } + (o[14] - breastRest[14]).let { it * it })
                    w.println("$t,$fps,${s.bone.x[0] * 1000},${s.bone.x[1] * 1000},${s.bone.x[2] * 1000},${angleBetween(o, breastRest)},${tr * 1000}")
                }
            }
        }
        // 3) hair: head turn 50° in 0.15 s, then back; tip lateral offset and per-bone angle
        File(dir, "hair.csv").printWriter().use { w ->
            w.println("t,fps,tip_x_mm,tip_z_mm,a0,a1,a2,a3")
            for (fps in listOf(30, 60)) {
                val h = hairRig()
                val m = h.sb.driver(0)
                for (i in 0..(4 * fps)) {
                    val t = i / fps.toFloat()
                    fun ss(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
                    val r = ss((t - 0.3f) / 0.25f) - ss((t - 2f) / 0.35f)
                    head(m, yaw = 50f * r, pitch = 0f)
                    h.sb.advance(1f / fps)
                    val tip = h.chain.n * 3
                    w.print("$t,$fps,${h.chain.pos[tip] * 1000},${h.chain.pos[tip + 2] * 1000}")
                    for (k in 0 until h.chain.n) w.print(",${angleBetween(h.chain.out[k], h.chain.restLocal[k])}")
                    w.println()
                }
            }
        }
    }
}
