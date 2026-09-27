package com.elyndra.launcher.ui.masha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * JVM tests of the runtime body animation: pose math, blend stack, inertialization,
 * scheduling, speech debounce, two-bone IK, procedural layers and saccades, plus a few
 * end-to-end runs of [MashaAnimator] on a small synthetic Mixamo-like skeleton.
 */
class MashaAnimatorTest {

    /* ── helpers ── */

    private fun aa(x: Float, y: Float, z: Float, deg: Float): FloatArray {
        val l = sqrt(x * x + y * y + z * z)
        return FloatArray(4).also { axisAngle(x / l, y / l, z / l, deg * DEG, it) }
    }

    /** Angle (deg) of the rotation between two quaternions, precise near 0 (atan2, not acos). */
    private fun angleDeg(a: FloatArray, ao: Int, b: FloatArray, bo: Int): Float {
        val ax = a[ao].toDouble(); val ay = a[ao + 1].toDouble(); val az = a[ao + 2].toDouble(); val aw = a[ao + 3].toDouble()
        val bx = -b[bo].toDouble(); val by = -b[bo + 1].toDouble(); val bz = -b[bo + 2].toDouble(); val bw = b[bo + 3].toDouble()
        // a·b⁻¹
        val x = aw * bx + ax * bw + ay * bz - az * by
        val y = aw * by - ax * bz + ay * bw + az * bx
        val z = aw * bz + ax * by - ay * bx + az * bw
        val w = aw * bw - ax * bx - ay * by - az * bz
        return (2.0 * kotlin.math.atan2(sqrt(x * x + y * y + z * z), abs(w)) * 180.0 / PI).toFloat()
    }

    private fun trs(x: Float, y: Float, z: Float) = FloatArray(16).also {
        it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f
        it[12] = x; it[13] = y; it[14] = z
    }

    /** A small rig with Mixamo names (identity rest rotations; +Y up, +Z front, +X her left). */
    private fun rig(): Skeleton {
        val spec = listOf(
            Triple("mixamorig:Hips", "", floatArrayOf(0f, 1f, 0f)),
            Triple("mixamorig:LeftUpLeg", "mixamorig:Hips", floatArrayOf(0.09f, -0.05f, 0f)),
            Triple("mixamorig:LeftLeg", "mixamorig:LeftUpLeg", floatArrayOf(0f, -0.44f, 0.02f)),
            Triple("mixamorig:LeftFoot", "mixamorig:LeftLeg", floatArrayOf(0f, -0.44f, -0.02f)),
            Triple("mixamorig:LeftToeBase", "mixamorig:LeftFoot", floatArrayOf(0f, -0.05f, 0.13f)),
            Triple("mixamorig:RightUpLeg", "mixamorig:Hips", floatArrayOf(-0.09f, -0.05f, 0f)),
            Triple("mixamorig:RightLeg", "mixamorig:RightUpLeg", floatArrayOf(0f, -0.44f, 0.02f)),
            Triple("mixamorig:RightFoot", "mixamorig:RightLeg", floatArrayOf(0f, -0.44f, -0.02f)),
            Triple("mixamorig:RightToeBase", "mixamorig:RightFoot", floatArrayOf(0f, -0.05f, 0.13f)),
            Triple("mixamorig:Spine", "mixamorig:Hips", floatArrayOf(0f, 0.1f, 0f)),
            Triple("mixamorig:Spine1", "mixamorig:Spine", floatArrayOf(0f, 0.12f, 0f)),
            Triple("mixamorig:Spine2", "mixamorig:Spine1", floatArrayOf(0f, 0.12f, 0f)),
            Triple("mixamorig:LeftShoulder", "mixamorig:Spine2", floatArrayOf(0.06f, 0.12f, 0f)),
            Triple("mixamorig:LeftArm", "mixamorig:LeftShoulder", floatArrayOf(0.12f, 0f, 0f)),
            Triple("mixamorig:RightShoulder", "mixamorig:Spine2", floatArrayOf(-0.06f, 0.12f, 0f)),
            Triple("mixamorig:RightArm", "mixamorig:RightShoulder", floatArrayOf(-0.12f, 0f, 0f)),
            Triple("mixamorig:Neck", "mixamorig:Spine2", floatArrayOf(0f, 0.15f, 0f)),
            Triple("mixamorig:Head", "mixamorig:Neck", floatArrayOf(0f, 0.08f, 0f)),
            Triple("masha:eye.L", "mixamorig:Head", floatArrayOf(0.03f, 0.08f, 0.09f)),
            Triple("masha:eye.R", "mixamorig:Head", floatArrayOf(-0.03f, 0.08f, 0.09f)),
        )
        val names = spec.map { it.first }
        return Skeleton(
            names.toTypedArray(),
            IntArray(spec.size) { names.indexOf(spec[it].second) },
            Array(spec.size) { val p = spec[it].third; trs(p[0], p[1], p[2]) },
            arrayOfNulls(spec.size),
        )
    }

    /**
     * Synthetic clips: each clip bends the left arm by its own angle (± a slow wobble), so
     * the arm rotation tells which clips are blended in and by how much.
     */
    private class Synth(val sk: Skeleton, val names: List<String>) : PoseSampler {
        val arm = sk.index("mixamorig:LeftArm")
        val q = FloatArray(4)
        override fun sample(clip: Int, time: Float, out: Pose) {
            out.copyFrom(sk.rest)
            val deg = angleOf(names[clip]) + 3f * sin(time * 2f)
            axisAngle(0f, 0f, 1f, deg * DEG, q)
            q.copyInto(out.q, 4 * arm)
        }

        fun angleOf(name: String): Float = when {
            name.startsWith("Var_") -> 60f
            name.startsWith("Talk_") -> 35f
            name == "Wave" -> 90f
            name == "Think" -> -30f
            name == "Listen" -> -15f
            name == "Talk" -> 20f
            else -> 0f
        }
    }

    private val contractNames = listOf(
        "Base", "Idle", "Var_LookAround", "Var_WeightShift", "Var_Stretch", "Var_Hair", "Var_GlanceSmile",
        "Talk_1", "Talk_2", "Talk_3", "Talk_4", "Talk_5", "Wave", "Point", "Think", "Listen", "Nod", "Shrug",
        "React_Happy", "React_Surprised", "RingSpin0",
    )
    private val legacyNames = listOf("Idle", "Talk", "Listen", "Think", "Explain", "Wave", "RingSpin0", "RingSpin1")

    private fun durations(names: List<String>) = FloatArray(names.size) {
        when {
            names[it] == "Base" -> 0.5f
            names[it].startsWith("Var_") -> 4f
            names[it].startsWith("Talk_") -> 3f
            names[it] == "Idle" -> 7.5f
            else -> 3.3f
        }
    }

    /* ── pose math ── */

    @Test
    fun blendPoseEndpointsMidpointAndSign() {
        val a = Pose(1); val b = Pose(1); val out = Pose(1)
        aa(0f, 1f, 0f, 0f).copyInto(a.q)
        aa(0f, 1f, 0f, 90f).copyInto(b.q)
        a.t[0] = 1f; b.t[0] = 3f
        blendPose(a, b, 0f, out); assertEquals(0f, angleDeg(out.q, 0, a.q, 0), 1e-3f)
        blendPose(a, b, 1f, out); assertEquals(0f, angleDeg(out.q, 0, b.q, 0), 1e-3f)
        blendPose(a, b, 0.5f, out)
        assertEquals(45f, angleDeg(out.q, 0, a.q, 0), 0.05f)
        assertEquals(2f, out.t[0], 1e-6f)
        // −q is the same rotation: the blend must take the short way anyway.
        for (i in 0..3) b.q[i] = -b.q[i]
        blendPose(a, b, 0.5f, out)
        assertEquals(45f, angleDeg(out.q, 0, a.q, 0), 0.05f)
    }

    @Test
    fun quatLogExpRoundTrip() {
        val rnd = Random(3)
        val v = FloatArray(3); val back = FloatArray(4)
        repeat(200) {
            val q = aa(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() * 170f)
            quatLog(q, 0, v, 0)
            quatExp(v[0], v[1], v[2], back)
            assertTrue(angleDeg(q, 0, back, 0) < 0.05f)
        }
    }

    @Test
    fun rotateInModelMatchesParentFrame() {
        // Parent rotated 90° about Y: a model-space rotation r applied at the child's pivot
        // must become local = P⁻¹·r·P·local.
        val pose = Pose(1)
        val parent = aa(0f, 1f, 0f, 90f)
        val r = aa(1f, 0f, 0f, 30f)
        rotateInModel(pose, 0, r, parent, FloatArray(4), FloatArray(4))
        // Model rotation of the child = parent · local must equal r · parent.
        val model = FloatArray(4); quatMul(parent, pose.q, model)
        val expect = FloatArray(4); quatMul(r, parent, expect)
        assertTrue(angleDeg(model, 0, expect, 0) < 1e-3f)
    }

    /* ── blend stack ── */

    private class Const(val deg: FloatArray) : PoseSampler {
        override fun sample(clip: Int, time: Float, out: Pose) {
            axisAngle(0f, 0f, 1f, (deg[clip] + 10f * sin(time)) * DEG, out.q)
            out.t[0] = deg[clip] / 100f
        }
    }

    /** Worst per-frame change of the output while pushing new clips in the middle of fades. */
    private fun stackRun(fps: Int, stack: BlendStack = BlendStack()): Float {
        val s = Const(floatArrayOf(0f, 80f, -60f, 30f))
        val out = Pose(1); val tmp = Pose(1); val prev = Pose(1)
        stack.push(0, 5f, 0f, loop = true, fade = 0f, base = true)
        val dt = 1f / fps
        var worst = 0f
        var t = 0f
        var first = true
        // New bases every 0.25 s (inside a 0.6 s fade), an overlay, another base, the overlay out.
        val events = floatArrayOf(0.5f, 0.75f, 1f, 1.17f, 1.42f, 1.5f)
        var next = 0
        while (t < 6f) {
            t += dt
            while (next < events.size && t >= events[next]) {
                when (next) {
                    0 -> stack.push(1, 5f, 0f, loop = true, fade = 0.6f, base = true)
                    1 -> stack.push(2, 5f, 0f, loop = true, fade = 0.6f, base = true)
                    2 -> stack.push(0, 5f, 0f, loop = true, fade = 0.6f, base = true)
                    3 -> stack.push(3, 2f, 0f, loop = false, fade = 0.3f, base = false, amp = 0.7f)
                    4 -> stack.push(1, 5f, 0f, loop = true, fade = 0.6f, base = true)
                    5 -> stack.fadeOut(stack[stack.size - 1].id, 0.5f)
                }
                next++
            }
            stack.update(dt)
            stack.evaluate(s, out, tmp)
            if (!first) worst = maxOf(worst, angleDeg(out.q, 0, prev.q, 0))
            prev.copyFrom(out)
            first = false
        }
        return worst
    }

    @Test
    fun blendStackNeverPopsOnMidFadeSwitches() {
        // Clip values differ by up to 140°. A pop is a jump that does not shrink with the
        // frame rate; a continuous blend's per-frame step scales with dt.
        val w60 = stackRun(60)
        val stack = BlendStack()
        val w240 = stackRun(240, stack)
        assertTrue("60 fps worst $w60°", w60 < 10f)
        assertTrue("240 fps worst $w240° (60 fps $w60°)", w240 < 2.5f && w240 < w60 / 3f)
        // Everything settled on the last base, the rest was dropped.
        assertEquals(1, stack.size)
        assertEquals(1, stack[0].clip)
    }

    @Test
    fun visibleWeightsSumToOne() {
        val stack = BlendStack()
        stack.push(0, 5f, 0f, true, 0f, base = true)
        stack.push(1, 5f, 0f, true, 0.6f, base = true)
        stack.push(2, 2f, 0f, false, 0.3f, base = false, amp = 0.6f)
        stack.update(0.2f)
        var sum = 0f
        for (i in 0 until stack.size) sum += stack.visibleWeight(i)
        assertEquals(1f, sum, 1e-5f)
    }

    /* ── inertialization ── */

    @Test
    fun inertializationIsContinuousAndDecaysToZero() {
        val prev = Pose(1); val prev2 = Pose(1); val target = Pose(1)
        aa(0f, 0f, 1f, 30f).copyInto(prev.q)
        aa(0f, 0f, 1f, 29f).copyInto(prev2.q)
        prev.t[1] = 0.05f
        val inert = Inertializer(1)
        inert.begin(prev, prev2, 1f / 60f, target, 0.15f, 6f)
        val p = Pose(1)
        p.copyFrom(target)
        inert.apply(p, 1f / 60f)
        // One frame after the cut the output is still next to the old pose (no pop)…
        assertTrue(angleDeg(p.q, 0, prev.q, 0) < 2f)
        // …and the offset is gone after ~1 s.
        repeat(59) { p.copyFrom(target); inert.apply(p, 1f / 60f) }
        assertTrue(inert.remaining() < 0.01f * DEG * 30f || !inert.active)
        repeat(120) { p.copyFrom(target); inert.apply(p, 1f / 60f) }
        assertTrue(angleDeg(p.q, 0, target.q, 0) < 0.05f)
        assertEquals(0f, p.t[1], 1e-4f)
    }

    /* ── scheduler, bags, debounce ── */

    @Test
    fun shuffleBagNeverRepeatsRecentAndIsFair() {
        val items = intArrayOf(10, 11, 12, 13, 14)
        val bag = ShuffleBag(items, 2, Random(1))
        val seen = ArrayList<Int>()
        repeat(5000) { seen += bag.next() }
        for (i in 2 until seen.size) {
            assertTrue(seen[i] != seen[i - 1] && seen[i] != seen[i - 2])
        }
        val counts = items.map { c -> seen.count { it == c } }
        assertTrue("counts $counts", counts.all { it in 900..1100 })
        // Beats: never any of the last 3.
        val beats = ShuffleBag(intArrayOf(1, 2, 3, 4, 5), 3, Random(9))
        val b = List(3000) { beats.next() }
        for (i in 3 until b.size) assertTrue(b[i] != b[i - 1] && b[i] != b[i - 2] && b[i] != b[i - 3])
        // Too few items: avoid as many as possible, never stall.
        val two = ShuffleBag(intArrayOf(7, 8), 2, Random(2))
        val t2 = List(100) { two.next() }
        for (i in 1 until t2.size) assertTrue(t2[i] != t2[i - 1])
    }

    @Test
    fun idleVariationsEvery8To20sOfFreeIdleNeverRepeatingLast2() {
        val cfg = MashaAnimConfig()
        val clips = ClipSet(contractNames, durations(contractNames), cfg)
        val director = MotionDirector(clips, cfg, Random(5))
        val stack = BlendStack()
        val input = MotionInput()
        val dt = 1f / 30f
        var t = 0f
        director.start(stack, 0f)
        var lastEnd = 0f
        var hadVar = false
        val gaps = ArrayList<Float>()
        val played = ArrayList<Int>()
        director.onPlay = { clip, kind ->
            if (kind == MotionDirector.KIND_VARIATION) { gaps += t - lastEnd; played += clip }
        }
        while (t < 1200f) {
            t += dt
            stack.update(dt)
            director.update(t, dt, input, stack)
            var has = false
            for (i in 0 until stack.size) if (stack[i].tag == MotionDirector.KIND_VARIATION) has = true
            if (hadVar && !has) lastEnd = t
            hadVar = has
        }
        assertTrue("played ${played.size}", played.size > 30)
        for (g in gaps) assertTrue("gap $g", g >= 8f - 2 * dt && g <= 20f + 2 * dt)
        for (i in 2 until played.size) assertTrue(played[i] != played[i - 1] && played[i] != played[i - 2])
    }

    @Test
    fun noVariationWhileSpeakingListeningOrThinking() {
        val cfg = MashaAnimConfig()
        val clips = ClipSet(contractNames, durations(contractNames), cfg)
        val director = MotionDirector(clips, cfg, Random(5))
        val stack = BlendStack()
        val input = MotionInput().apply { thinking = true }
        var vars = 0
        director.onPlay = { _, kind -> if (kind == MotionDirector.KIND_VARIATION) vars++ }
        director.start(stack, 0f)
        var t = 0f
        while (t < 100f) { t += 0.05f; stack.update(0.05f); director.update(t, 0.05f, input, stack) }
        input.thinking = false; input.listening = true
        while (t < 200f) { t += 0.05f; stack.update(0.05f); director.update(t, 0.05f, input, stack) }
        assertEquals(0, vars)
    }

    @Test
    fun speakingDebounceBridgesSentenceGaps() {
        val d = SpeechDebounce(0.8f)
        var t = 0f
        val dt = 1f / 60f
        fun run(until: Float, raw: Boolean, check: (Boolean) -> Unit) {
            while (t < until) { t += dt; check(d.update(t, raw)) }
        }
        run(2f, true) { assertTrue(it) }
        run(2.5f, false) { assertTrue("gap of 0.5 s must not stop talking", it) }
        run(4f, true) { assertTrue(it) }
        var off = -1f
        run(6f, false) { if (!it && off < 0f) off = t }
        assertEquals(4f + 0.8f, off, 2 * dt)
    }

    @Test
    fun directorStaysInTalkAcrossTtsGapsAndThinkDoesNotBlockTalk() {
        val cfg = MashaAnimConfig()
        val clips = ClipSet(legacyNames, durations(legacyNames), cfg)
        val director = MotionDirector(clips, cfg, Random(1))
        val stack = BlendStack()
        val input = MotionInput()
        director.start(stack, 0f)
        var t = 0f
        val dt = 1f / 60f
        fun step(n: Int) = repeat(n) { t += dt; stack.update(dt); director.update(t, dt, input, stack) }
        input.thinking = true
        step(30)
        assertEquals(MotionDirector.State.Think, director.state)
        // Legacy: Think is a one-shot overlay on idle.
        assertTrue((0 until stack.size).any { stack[it].tag == MotionDirector.KIND_THINK })
        // Speech starts while still thinking: talking wins immediately.
        input.speaking = true
        step(1)
        assertEquals(MotionDirector.State.Talk, director.state)
        step(60)
        assertFalse((0 until stack.size).any { stack[it].tag == MotionDirector.KIND_THINK })
        input.thinking = false
        // TTS gaps between sentences (0.4 s) keep Talk.
        repeat(5) {
            input.speaking = false; step(24)
            assertEquals(MotionDirector.State.Talk, director.state)
            input.speaking = true; step(60)
        }
        input.speaking = false
        step(60)
        assertEquals(MotionDirector.State.Idle, director.state)
    }

    @Test
    fun clipDiscoveryAndLegacyFallback() {
        val legacy = ClipSet(legacyNames, durations(legacyNames))
        assertFalse(legacy.contract)
        assertEquals(legacyNames.indexOf("Talk"), legacy.talkLoop)
        assertEquals(legacyNames.indexOf("Explain"), legacy.point)
        assertEquals(legacyNames.indexOf("Explain"), legacy.explain)
        assertEquals(0, legacy.variations.size)
        assertEquals(0, legacy.beats.size)
        assertFalse(legacy.thinkLoops)
        assertEquals(6, legacy.body.size)

        val c = ClipSet(contractNames, durations(contractNames))
        assertTrue(c.contract)
        assertTrue(c.thinkLoops)
        assertEquals(-1, c.talkLoop)
        assertEquals(5, c.beats.size)
        assertEquals(5, c.variations.size)
        assertEquals(contractNames.indexOf("Point"), c.explain)
        assertTrue(c.stepping[contractNames.indexOf("Var_WeightShift")])
        assertEquals(0.2f, c.lookAt[contractNames.indexOf("Var_LookAround")], 0f)
        assertEquals(0.7f, c.smile[contractNames.indexOf("React_Happy")], 0f)
    }

    @Test
    fun talkBeatsFollowTheVoiceAndNeverRepeatLast3() {
        val cfg = MashaAnimConfig()
        val clips = ClipSet(contractNames, durations(contractNames), cfg)
        val director = MotionDirector(clips, cfg, Random(11))
        val stack = BlendStack()
        val input = MotionInput().apply { speaking = true }
        val beats = ArrayList<Pair<Float, Int>>()
        var t = 0f
        director.onPlay = { clip, kind -> if (kind == MotionDirector.KIND_BEAT) beats += t to clip }
        director.start(stack, 0f)
        val dt = 1f / 60f
        val rnd = Random(4)
        var syll = 0f
        while (t < 120f) {
            t += dt
            // Syllables with accents: open 0.1–0.8 at ~5 Hz.
            syll += dt
            if (syll > 0.2f) syll = 0f
            input.voice = if (syll < 0.1f) 0.1f + 0.7f * rnd.nextFloat() else 0.02f
            stack.update(dt)
            director.update(t, dt, input, stack)
        }
        assertTrue("beats ${beats.size}", beats.size > 20)
        for (i in 1 until beats.size) assertTrue(beats[i].first - beats[i - 1].first >= cfg.talk.minGap - dt)
        for (i in 3 until beats.size) {
            val c = beats[i].second
            assertTrue(c != beats[i - 1].second && c != beats[i - 2].second && c != beats[i - 3].second)
        }
        // Amplitude within 0.5–1 and time scale within ±10 %.
        for (i in 0 until stack.size) if (stack[i].tag == MotionDirector.KIND_BEAT) {
            assertTrue(stack[i].amp in 0.5f..1f)
            assertTrue(stack[i].speed in 0.9f..1.1f)
        }
    }

    /* ── IK ── */

    @Test
    fun twoBoneIkReachesReachableTargetsAndClampsOthers() {
        val ik = TwoBoneIk()
        val a = floatArrayOf(0.09f, 0.95f, 0f)
        val b = floatArrayOf(0.09f, 0.51f, 0.1f)
        val c = floatArrayOf(0.09f, 0.07f, 0f)
        val l = 2f * sqrt(0.44f * 0.44f + 0.1f * 0.1f)
        val ra = FloatArray(4); val rb = FloatArray(4); val got = FloatArray(3)
        val hint = floatArrayOf(0f, 0f, 1f)
        val rnd = Random(8)
        repeat(300) {
            // Reachable: within 0.5–0.95 of the leg below the hip.
            val dir = floatArrayOf(rnd.nextFloat() * 0.6f - 0.3f, -1f, rnd.nextFloat() * 0.6f - 0.3f).also { norm3(it) }
            val d = l * (0.5f + 0.45f * rnd.nextFloat())
            val target = FloatArray(3) { a[it] + dir[it] * d }
            ik.solve(a, b, c, target, hint, 0.99f, 0.005f, ra, rb)
            ik.ankleAfter(a, b, c, ra, rb, got)
            val err = sqrt((0..2).sumOf { ((got[it] - target[it]) * (got[it] - target[it])).toDouble() }).toFloat()
            assertTrue("error $err", err < 1e-3f)
        }
        // Unreachable: the ankle stops short (≤ 0.99 of the leg, along the target direction).
        val far = floatArrayOf(0.09f, -0.5f, 0.2f)
        ik.solve(a, b, c, far, hint, 0.99f, 0.005f, ra, rb)
        ik.ankleAfter(a, b, c, ra, rb, got)
        val reach = sqrt((0..2).sumOf { ((got[it] - a[it]) * (got[it] - a[it])).toDouble() }).toFloat()
        assertTrue("reach $reach", reach <= 0.99f * l + 1e-4f && reach > 0.97f * l)
        val want = FloatArray(3) { far[it] - a[it] }.also { norm3(it) }
        val have = FloatArray(3) { got[it] - a[it] }.also { norm3(it) }
        assertTrue(dot3(want, have) > 0.9999f)
        // The knee keeps bending forward (pole).
        val knee = FloatArray(3); quatRotate(ra, floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2]), knee)
        assertTrue(knee[2] > 0f)
        // A clip that already stretches the leg beyond maxReach (Masha's rest legs are at
        // 0.997): targeting its own ankle must not lift the foot.
        val bs = floatArrayOf(0.09f, 0.51f, 0.02f)
        ik.solve(a, bs, c, c, hint, 0.99f, 0.005f, ra, rb)
        ik.ankleAfter(a, bs, c, ra, rb, got)
        val lift = sqrt((0..2).sumOf { ((got[it] - c[it]) * (got[it] - c[it])).toDouble() }).toFloat()
        assertTrue("lift $lift", lift < 1e-4f)
    }

    /* ── procedural layers & saccades: frame-rate independence ── */

    @Test
    fun proceduralLayerIsFrameRateIndependent() {
        val cfg = MashaAnimConfig()
        val a = ProceduralLayer(cfg, 42)
        val b = ProceduralLayer(cfg, 42)
        var ta = 0.0
        var tb = 0.0
        for (i in 1..30 * 60) {
            ta = i / 30.0
            a.update(ta.toFloat(), 1f / 30f, gesture = false, amount = 1f)
            // Two 60 fps frames for each 30 fps frame.
            tb = (2 * i - 1) / 60.0
            b.update(tb.toFloat(), 1f / 60f, gesture = false, amount = 1f)
            tb = (2 * i) / 60.0
            b.update(tb.toFloat(), 1f / 60f, gesture = false, amount = 1f)
            assertEquals(a.hipsRoll, b.hipsRoll, 1e-6f)
            assertEquals(a.spine1Pitch, b.spine1Pitch, 1e-6f)
            assertEquals(a.headYaw, b.headYaw, 1e-6f)
            assertEquals(a.shiftX, b.shiftX, 1e-6f)
            assertEquals(a.shoulderRaise, b.shoulderRaise, 1e-6f)
        }
    }

    @Test
    fun breathIsAsymmetricAndNoiseIsBoundedAndNotPeriodic() {
        // Inhale peaks at 40 % of the cycle.
        var best = 0f; var at = 0f
        for (i in 0..1000) { val p = i / 1000f; val v = breathCurve(p, 0.4f); if (v > best) { best = v; at = p } }
        assertEquals(0.4f, at, 0.002f)
        assertEquals(0f, breathCurve(0f, 0.4f), 1e-6f)
        // 2-octave noise stays in −1..1 and does not repeat with a fixed period like a sine.
        var lo = 1f; var hi = -1f
        for (i in 0..20000) { val v = noise2(i * 0.01f, 3); lo = minOf(lo, v); hi = maxOf(hi, v) }
        assertTrue(lo >= -1f && hi <= 1f && hi - lo > 0.8f)
        assertTrue(abs(noise2(1.3f, 3) - noise2(11.3f, 3)) + abs(noise2(2.7f, 3) - noise2(12.7f, 3)) > 1e-3f)
    }

    @Test
    fun saccadesFrameRateIndependentAndTimedByTheMainSequence() {
        val cfg = LookConfig()
        val a = Saccades(cfg, 5)
        val b = Saccades(cfg, 5)
        for (i in 1..30 * 120) {
            // Thinking starts at 60.02 s: both rates first see it at the same frame time.
            val ta = i / 30f
            val tb1 = (2 * i - 1) / 60f
            a.update(ta, ta > 60.02f)
            b.update(tb1, tb1 > 60.02f)
            b.update(ta, ta > 60.02f)
            assertEquals(a.yaw, b.yaw, 1e-4f)
            assertEquals(a.pitch, b.pitch, 1e-4f)
        }

        val s = Saccades(cfg, 9)
        val events = ArrayList<FloatArray>()
        s.onSaccade = { kind, at, amp, dur -> events += floatArrayOf(kind.toFloat(), at, amp, dur) }
        var t = 0f
        while (t < 300f) { t += 1f / 60f; s.update(t, thinking = t > 150f) }
        for (e in events) assertEquals(cfg.saccadeBase + cfg.saccadePerDeg * e[2], e[3], 1e-6f)
        fun times(kind: Int) = events.filter { it[0].toInt() == kind }.map { it[1] }
        val fix = times(Saccades.KIND_FIX)
        for (i in 1 until fix.size) assertTrue(fix[i] - fix[i - 1] in 0.8f - 1e-4f..2.5f + 1e-4f)
        events.filter { it[0].toInt() == Saccades.KIND_FIX }.forEach { assertTrue(it[2] in 1f - 1e-3f..3f + 1e-3f) }
        val micro = times(Saccades.KIND_MICRO)
        for (i in 1 until micro.size) assertTrue(micro[i] - micro[i - 1] in 0.6f - 1e-4f..1.4f + 1e-4f)
        events.filter { it[0].toInt() == Saccades.KIND_MICRO }.forEach { assertTrue(it[2] <= 0.5f + 1e-4f) }
        val rate = micro.size / 300f
        assertTrue("micro rate $rate/s", rate in 0.7f..1.5f)
        // Glance-aways only while thinking, every 4–8 s (the outgoing ones: amplitude ≥ 5°).
        val away = events.filter { it[0].toInt() == Saccades.KIND_AWAY && it[2] >= 5f && it[1] > 150f }
        assertTrue(events.none { it[0].toInt() == Saccades.KIND_AWAY && it[1] < 150f })
        assertTrue(away.size >= 15)
        // Out and back alternate; take every out (first of each pair).
        val gaps = ArrayList<Float>()
        val outgoing = events.filter { it[0].toInt() == Saccades.KIND_AWAY && it[1] > 150f }.chunked(2).map { it[0][1] }
        for (i in 1 until outgoing.size) gaps += outgoing[i] - outgoing[i - 1]
        gaps.forEach { assertTrue("away gap $it", it in 4f - 1e-3f..8f + 1e-3f) }
    }

    /* ── end to end on the synthetic rig ── */

    private class Run(names: List<String>, cfg: MashaAnimConfig = MashaAnimConfig()) {
        val sk = MashaAnimatorTest().rig()
        val clips = ClipSet(names, MashaAnimatorTest().durations(names), cfg)
        val anim = MashaAnimator(sk, clips, cfg)
        val sampler = Synth(sk, names)
        val input = MotionInput()
        val camera = floatArrayOf(0f, 1.3f, 3.4f)
        var t = 0f
        val model = FloatArray(16 * sk.n)

        init { anim.captureFeet(sampler) }

        fun step(dt: Float = 1f / 60f) {
            t += dt
            anim.frame(t, dt, input, camera, sampler)
        }
    }

    @Test
    fun speechCancelsAVariationMidFadeWithoutPopping() {
        val r = Run(contractNames)
        val arm = r.sk.index("mixamorig:LeftArm")
        var varStart = -1f
        r.anim.director.onPlay = { _, kind -> if (kind == MotionDirector.KIND_VARIATION && varStart < 0f) varStart = r.t }
        while (varStart < 0f) r.step()
        // 0.2 s into the 0.5 s fade-in, speech starts: the variation is cut with inertialization.
        while (r.t < varStart + 0.2f) r.step()
        val prev = FloatArray(4)
        r.anim.output.q.copyInto(prev, 0, 4 * arm, 4 * arm + 4)
        r.input.speaking = true
        r.input.voice = 0.3f
        var worst = 0f
        repeat(90) {
            r.step()
            worst = maxOf(worst, angleDeg(r.anim.output.q, 4 * arm, prev, 0))
            r.anim.output.q.copyInto(prev, 0, 4 * arm, 4 * arm + 4)
        }
        assertEquals(MotionDirector.State.Talk, r.anim.director.state)
        assertFalse((0 until r.anim.stack.size).any { r.anim.stack[it].tag == MotionDirector.KIND_VARIATION })
        assertTrue("worst per-frame arm step $worst°", worst < 6f)
    }

    @Test
    fun feetStayPlantedWhileTheHipsSway() {
        val r = Run(legacyNames)
        val footL = r.sk.index("mixamorig:LeftFoot")
        val hips = r.sk.index("mixamorig:Hips")
        r.sk.fk(r.sk.rest, r.model)
        val rest = FloatArray(3).also { r.sk.position(r.model, footL, it) }
        val hipsRest = FloatArray(3).also { r.sk.position(r.model, hips, it) }
        var worstFoot = 0f
        var worstHips = 0f
        val p = FloatArray(3)
        while (r.t < 40f) {
            r.step()
            r.sk.fk(r.anim.output, r.model)
            r.sk.position(r.model, footL, p)
            worstFoot = maxOf(worstFoot, sqrt((0..2).sumOf { ((p[it] - rest[it]) * (p[it] - rest[it])).toDouble() }).toFloat())
            r.sk.position(r.model, hips, p)
            worstHips = maxOf(worstHips, sqrt((0..2).sumOf { ((p[it] - hipsRest[it]) * (p[it] - hipsRest[it])).toDouble() }).toFloat())
        }
        assertTrue("hips should move (weight shift): $worstHips", worstHips > 0.005f)
        assertTrue("foot skated $worstFoot m", worstFoot < 0.002f)
    }

    @Test
    fun eyesAndHeadTurnTowardTheCamera() {
        val r = Run(legacyNames)
        r.camera[0] = 1.2f; r.camera[1] = 1.6f; r.camera[2] = 2.2f
        val head = r.sk.index("mixamorig:Head")
        val eye = r.sk.index("masha:eye.L")
        while (r.t < 3f) r.step()
        r.sk.fk(r.anim.output, r.model)
        val q = FloatArray(4); val f = FloatArray(3)
        // Head turned toward her left (+X), within the head yaw limit.
        r.sk.modelQuat(r.model, head, q)
        quatRotate(q, floatArrayOf(0f, 0f, 1f), f)
        val headYaw = kotlin.math.atan2(f[0], f[2]) / DEG
        assertTrue("head yaw $headYaw", headYaw > 5f && headYaw <= GazeConfig.HEAD_YAW_MAX + 3f)
        // Eye looks at the camera up to the saccade offsets (fixation ≤ ~4.5°, micro ≤ 0.5°).
        r.sk.modelQuat(r.model, eye, q)
        quatRotate(q, floatArrayOf(0f, 0f, 1f), f)
        val to = FloatArray(3) { r.camera[it] - r.model[16 * eye + 12 + it] }.also { norm3(it) }
        val err = (acos(dot3(f, to).coerceIn(-1f, 1f).toDouble()) * 180 / PI).toFloat()
        assertTrue("eye error $err°", err < 5.5f)
        // Camera behind her: she stops tracking.
        r.camera[0] = 0f; r.camera[2] = -3f
        while (r.t < 8f) r.step()
        assertTrue(r.anim.lookWeight < 0.05f)
    }

    @Test
    fun waveCueIsPlayedAndReturnsToIdle() {
        val r = Run(legacyNames)
        val arm = r.sk.index("mixamorig:LeftArm")
        r.step()
        r.anim.cue(Action.Wave)
        var peak = 0f
        while (r.t < 6f) {
            r.step()
            peak = maxOf(peak, angleDeg(r.anim.output.q, 4 * arm, r.sk.rest.q, 4 * arm))
        }
        assertTrue("wave peak $peak", peak > 80f)
        assertTrue(angleDeg(r.anim.output.q, 4 * arm, r.sk.rest.q, 4 * arm) < 10f)
        assertEquals(1, r.anim.stack.size)
    }
}
