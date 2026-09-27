package com.elyndra.launcher.ui.masha

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Secondary motion for Masha: soft tissue springs, a VRM-like hair chain and
 * forearm/upper-arm twist distribution.
 *
 * This file is the pure-Kotlin core (no Filament imports) so that it can be unit
 * tested on the JVM. `SpringBonesFilament` feeds it model-space matrices read from
 * Filament and writes its local transforms back.
 *
 * Conventions
 * - Matrices are 4x4, column-major FloatArray(16) (Filament layout), affine.
 * - Quaternions are FloatArray(4) = (x, y, z, w) (glTF layout).
 * - "Model space" = the asset root space: glTF, Y-up, metres, +Z = character front,
 *   +X = character's left. The core never sees the camera/world or the node transform,
 *   so moving/rotating the ModelNode in the scene does not shake the tissue.
 * - Bone-local frames are the ones Blender's glTF exporter writes: the bone points
 *   along its local +Y (child joints sit on +Y). This was verified on the v2 rig
 *   export (child translations are (0, len, 0); Hand rest quaternion in glTF equals the
 *   Blender bone-space one). Everything else is derived at runtime from rest transforms.
 * - No allocation in the per-frame path (all scratch arrays are preallocated).
 */

/* ───────────────────────────── configuration ───────────────────────────── */

/**
 * Damped spring for a soft-tissue bone (breast, glute).
 *
 * The tissue is a point ("tip") at [tailLength] along the bone's local +Y. It is
 * simulated as a displacement x (model space, metres) relative to where the animated
 * skeleton puts it, driven by the inertial acceleration of that point (linear and
 * angular motion of the parent bone are both included, because the tip is a point on
 * the moving parent frame) and by the change of gravity relative to the rest pose.
 *
 * @property frequencyHz natural frequency f (ω = 2πf). Higher = firmer, faster. Range 2–6.
 * @property dampingRatio ζ. 1 = no overshoot, 0.5 ≈ 16 % overshoot. Range 0.35–1.
 * @property gravityFactor how much the change of gravity direction (leaning, bending)
 *   displaces the tissue. The rest pose already contains the standing sag, so only the
 *   difference to rest is applied (0 at rest). 1 = a real mass on this spring. Range 0–1.
 * @property inertiaFactor coupling to the body's acceleration (1 = physical). Range 0–1.5.
 * @property tailLength metres from the bone head to the tissue point (breast bone 5 cm,
 *   glute bone 6 cm in the v2 rig; glTF has no bone tails, so it lives here).
 * @property maxDisplacement hard clamp of |x| (metres).
 * @property maxAngleDeg clamp of the output swing rotation (degrees).
 * @property translationFactor fraction of x applied as translation (0 = rotation only).
 * @property maxTranslation clamp of that translation (metres).
 */
data class SoftTissueConfig(
    val frequencyHz: Float,
    val dampingRatio: Float,
    val gravityFactor: Float,
    val inertiaFactor: Float = 1f,
    val tailLength: Float,
    val maxDisplacement: Float,
    val maxAngleDeg: Float,
    val translationFactor: Float,
    val maxTranslation: Float,
) {
    companion object {
        /** Chest: f ≈ 3 Hz, ζ ≈ 0.5, ≤ 8° / ≤ 1 cm. */
        val CHEST = SoftTissueConfig(
            frequencyHz = 3.0f, dampingRatio = 0.5f, gravityFactor = 0.3f, inertiaFactor = 1f,
            tailLength = 0.05f, maxDisplacement = 0.010f, maxAngleDeg = 8f,
            translationFactor = 0.35f, maxTranslation = 0.005f,
        )

        /** Glutes: firmer and more damped: f ≈ 4 Hz, ζ ≈ 0.6, ≤ 5° / ≤ 0.8 cm. */
        val GLUTE = SoftTissueConfig(
            frequencyHz = 4.0f, dampingRatio = 0.6f, gravityFactor = 0.2f, inertiaFactor = 1f,
            tailLength = 0.06f, maxDisplacement = 0.008f, maxAngleDeg = 5f,
            translationFactor = 0.3f, maxTranslation = 0.004f,
        )
    }
}

/**
 * A sphere (bone2 == null) or capsule collider for the hair.
 *
 * Offsets are in MODEL space at REST (glTF Y-up, +Z front, +X character's left),
 * relative to the bone's origin (joint position). They are converted to bone-local
 * offsets at runtime from the rest transforms, so they don't depend on bone rolls.
 * A capsule goes from (bone + offset) to (bone2 + offset2).
 */
data class ColliderConfig(
    val bone: String,
    val radius: Float,
    val ox: Float = 0f,
    val oy: Float = 0f,
    val oz: Float = 0f,
    val bone2: String? = null,
    val ox2: Float = 0f,
    val oy2: Float = 0f,
    val oz2: Float = 0f,
)

/**
 * Hair chain (ponytail), VRM springbone semantics.
 *
 * Stiffness, drag and gravity use VRM 0.x/1.0 units *as tuned at 60 fps* and are
 * converted to physical, frame-rate independent quantities (see [SpringBones]):
 * stiffness s → a pull of 60·s m/s² toward the rest direction, gravity g → 60·g m/s²,
 * drag d → velocity retention (1-d)^(60·h) per substep of h seconds.
 *
 * @property stiffnessRoot / [stiffnessTip] linearly interpolated along the chain (0.3–1.2).
 *   Defaults 0.9 → 0.45 are a touch firmer at the tip than the usual VRM 0.8 → 0.3: with
 *   5 cm segments the softer tip whipped to ~70° on quick head turns.
 * @property drag 0.4–0.65 (higher = heavier, calmer hair).
 * @property gravity 0.05–0.15 (VRM gravityPower).
 * @property gravityRelativeToRest true (default): only the change of gravity relative to
 *   the head's rest orientation is applied, g·(dir − R_head·R_head,rest⁻¹·dir). The sculpted
 *   ponytail already hangs, so this keeps the idle pose exactly as modelled and still
 *   makes the hair fall when the head tilts. false = absolute gravity like VRM (the chain
 *   sags from its sculpted shape by roughly gravity/stiffness, ~2 cm at the tip here).
 * @property gravityX/Y/Z gravity direction in model space.
 * @property hairRadius collision radius of each hair particle (m).
 * @property tipLength length of the last bone (glTF has no tails). 0 = same as the
 *   previous segment.
 * @property maxAngleDeg safety clamp of each bone's deviation from its rest direction.
 * @property autoFitColliders shrink any collider that intersects the hair at rest, so
 *   the idle pose is never pushed (the collider defaults are approximate).
 */
data class HairConfig(
    val prefix: String = "masha:hair.",
    val stiffnessRoot: Float = 0.9f,
    val stiffnessTip: Float = 0.45f,
    val drag: Float = 0.5f,
    val gravity: Float = 0.1f,
    val gravityX: Float = 0f,
    val gravityY: Float = -1f,
    val gravityZ: Float = 0f,
    val gravityRelativeToRest: Boolean = true,
    val hairRadius: Float = 0.012f,
    val tipLength: Float = 0f,
    val maxAngleDeg: Float = 60f,
    val autoFitColliders: Boolean = true,
    val colliders: List<ColliderConfig> = DEFAULT_COLLIDERS,
) {
    companion object {
        val DEFAULT_COLLIDERS = listOf(
            // Skull: ~9 cm above the Head joint (base of the skull), radius 9.5 cm.
            ColliderConfig("mixamorig:Head", 0.095f, oy = 0.09f, oz = 0.005f),
            // Neck: Neck joint → Head joint.
            ColliderConfig("mixamorig:Neck", 0.05f, bone2 = "mixamorig:Head"),
            // Upper back / chest volume behind the Spine2 joint.
            ColliderConfig("mixamorig:Spine2", 0.10f, oy = 0.07f, oz = -0.01f),
            // Shoulders: clavicle joint → arm joint.
            ColliderConfig("mixamorig:LeftShoulder", 0.045f, bone2 = "mixamorig:LeftArm"),
            ColliderConfig("mixamorig:RightShoulder", 0.045f, bone2 = "mixamorig:RightArm"),
        )
    }
}

/**
 * Twist distribution (the rule of hands.build / hand_poses.json):
 * forearm twist bones take [forearmMid] and [forearmTwist] of the hand's roll about the
 * forearm axis; the upper-arm twist bone takes [upperArm] of the Arm's own roll.
 */
data class TwistConfig(
    val enabled: Boolean = true,
    val forearmMid: Float = 1f / 3f,
    val forearmTwist: Float = 2f / 3f,
    val upperArm: Float = -0.5f,
)

/**
 * @property enabled master switch (false = every driven bone at its rest local transform).
 * @property intensity global amount 0..1+ (0 = exact rest; 1 = the physical result). It scales
 *   the output deviation, not the physics, so changing it at runtime never pops.
 * @property substepHz fixed simulation rate (default 120 Hz).
 * @property maxSubsteps per rendered frame (8 at 120 Hz = 66 ms of simulation; beyond that
 *   time is dropped — a hitch plays slightly slower instead of exploding).
 * @property teleportDt a frame gap longer than this (app resume, first frame, stall)
 *   resets the simulation to the current pose instead of simulating the jump.
 * @property gravity m/s² used by the soft tissue.
 */
data class SpringConfig(
    val enabled: Boolean = true,
    val intensity: Float = 1f,
    val substepHz: Float = 120f,
    val maxSubsteps: Int = 8,
    val teleportDt: Float = 0.25f,
    val gravity: Float = 9.81f,
    val chest: SoftTissueConfig = SoftTissueConfig.CHEST,
    val glute: SoftTissueConfig = SoftTissueConfig.GLUTE,
    val hair: HairConfig = HairConfig(),
    val twist: TwistConfig = TwistConfig(),
    val breastBones: List<String> = listOf("masha:breast.L", "masha:breast.R"),
    val gluteBones: List<String> = listOf("masha:glute.L", "masha:glute.R"),
)

/* ───────────────────────────── the solver ───────────────────────────── */

/**
 * Fixed-timestep secondary-motion solver.
 *
 * Usage (per frame): write the current model-space matrix of each driver into
 * [driver] (i), call [advance] with the frame dt, then read [SoftBone.out] /
 * [HairChain.out] (local transforms to write on the bones).
 *
 * Timestep: an accumulator runs fixed substeps of h = 1/[SpringConfig.substepHz] and carries
 * the remainder to the next frame. At 30/60/120 fps that is exactly 4/2/1 substeps, so the
 * response is frame-rate independent. Driver motion inside a frame is linearly interpolated
 * at each substep's exact time. The state is NOT interpolated for output: the simulation lags
 * the render by the remainder (< h = 8.3 ms), i.e. < 0.03 of a 3–4 Hz cycle, which is not
 * visible on a signal of a few millimetres, and it avoids keeping two states per bone.
 */
internal class SpringBones(val config: SpringConfig) {

    /* ── drivers: animated bones whose model matrix is read each frame ── */

    private class Driver(rest: FloatArray) {
        val rest = rest.copyOf()
        val now = rest.copyOf()
        val prev = rest.copyOf()
    }

    private val drivers = ArrayList<Driver>()

    fun addDriver(restModel: FloatArray): Int {
        drivers += Driver(restModel)
        return drivers.size - 1
    }

    /** The current model-space matrix of driver [i]; the adapter writes into it every frame. */
    fun driver(i: Int): FloatArray = drivers[i].now

    val softBones = ArrayList<SoftBone>()
    val hairChains = ArrayList<HairChain>()
    val colliders = ArrayList<Collider>()

    private val h get() = 1f / config.substepHz
    private var accumulator = 0f
    private var needsTeleport = true

    /** Next [advance] snaps everything to the current pose (use after a teleport/resume). */
    fun reset() {
        needsTeleport = true
    }

    /* ── building ── */

    fun addSoftTissue(driver: Int, restLocal: FloatArray, cfg: SoftTissueConfig): SoftBone {
        val b = SoftBone(driver, restLocal, cfg)
        // Gravity direction at rest, in the parent's rest frame.
        val r = drivers[driver].rest
        b.downRest[0] = 0f; b.downRest[1] = -1f; b.downRest[2] = 0f
        invMul3(r, b.downRest, b.downRest)
        norm3(b.downRest)
        softBones += b
        return b
    }

    fun addCollider(driverA: Int, offA: FloatArray, driverB: Int, offB: FloatArray, radius: Float, sphere: Boolean): Collider {
        val c = Collider(driverA, driverB, radius, sphere)
        invMul3(drivers[driverA].rest, offA, c.offA)
        invMul3(drivers[driverB].rest, offB, c.offB)
        colliders += c
        return c
    }

    fun addHairChain(rootDriver: Int, restLocals: List<FloatArray>, cfg: HairConfig): HairChain {
        val chain = HairChain(rootDriver, restLocals, cfg)
        hairChains += chain
        if (cfg.autoFitColliders) fitColliders(chain)
        return chain
    }

    /* ── per frame ── */

    fun advance(dt: Float) {
        if (!config.enabled || config.intensity <= 0f) {
            for (b in softBones) b.rest.copyInto(b.out)
            for (c in hairChains) for (k in 0 until c.n) c.restLocal[k].copyInto(c.out[k])
            needsTeleport = true
            savePrev()
            return
        }
        if (needsTeleport || dt < 0f || dt > config.teleportDt) {
            teleport()
            writeOutputs()
            savePrev()
            return
        }
        if (dt == 0f) {
            writeOutputs()
            return
        }
        val h = h
        accumulator += dt
        var n = (accumulator / h + 1e-3f).toInt()
        var dropped = false
        if (n > config.maxSubsteps) {
            n = config.maxSubsteps
            accumulator = 0f
            dropped = true
        } else {
            accumulator = max(0f, accumulator - n * h)
        }

        for (b in softBones) stepSoft(b, dt, n, h)

        if (hairChains.isNotEmpty()) {
            for (c in colliders) evalCollider(c, now = true)
            for (chain in hairChains) {
                rootPose(chain)
                if (dropped) {
                    // The frame was longer than we simulate: carry the unsimulated part of the
                    // root motion kinematically, so the hair keeps its true velocity.
                    val f = 1f - n * h / dt
                    for (i in 1..chain.n) for (a in 0..2) {
                        val d = (chain.p0Now[a] - chain.p0Prev[a]) * f
                        chain.pos[i * 3 + a] += d
                        chain.prev[i * 3 + a] += d
                    }
                }
            }
            for (i in 0 until n) {
                // Exact time of this substep's end inside the frame (see class doc).
                val f = (1f - (accumulator + (n - 1 - i) * h) / dt).coerceIn(0f, 1f)
                for (c in colliders) lerpCollider(c, f)
                for (chain in hairChains) stepHair(chain, f, h)
            }
        }
        writeOutputs()
        savePrev()
    }

    private fun savePrev() {
        for (d in drivers) d.now.copyInto(d.prev)
        for (c in colliders) {
            c.aNow.copyInto(c.aPrev); c.bNow.copyInto(c.bPrev)
        }
        for (chain in hairChains) {
            chain.p0Now.copyInto(chain.p0Prev); chain.qNow.copyInto(chain.qPrev)
        }
    }

    private fun teleport() {
        accumulator = 0f
        needsTeleport = false
        for (b in softBones) {
            b.u.fill(0f); b.vTPrev.fill(0f)
            softEquilibrium(b)
            softTarget(b, b.tPrev)
            b.primed = false
        }
        for (c in colliders) {
            evalCollider(c, now = true)
            c.aNow.copyInto(c.aPrev); c.bNow.copyInto(c.bPrev)
            c.aNow.copyInto(c.a); c.bNow.copyInto(c.b)
        }
        for (chain in hairChains) {
            rootPose(chain)
            chain.p0Now.copyInto(chain.p0Prev); chain.qNow.copyInto(chain.qPrev)
            restChain(chain, chain.p0Now, chain.qNow, chain.pos)
            chain.pos.copyInto(chain.prev)
        }
    }

    /* ── soft tissue ── */

    /**
     * One damped point spring per bone, in relative coordinates:
     *   x'' = -ω² x - 2ζω x' - inertia·T'' + g_eff
     * T is the tissue point on the animated skeleton. T is linear within a frame, so T''
     * is an impulse at frame boundaries: x' -= inertia·Δv_T (exact for piecewise-linear
     * motion). Integrated at h with symplectic Euler for the spring and implicit damping
     * (ωh = 0.16 at 3 Hz / 120 Hz: stable and accurate; damping is the only dissipation).
     */
    inner class SoftBone(val driver: Int, restLocal: FloatArray, val cfg: SoftTissueConfig) {
        val rest = restLocal.copyOf()
        /** Local transform to write on the bone this frame. */
        val out = restLocal.copyOf()
        /** Simulated displacement (model space, m) and its velocity. */
        val x = FloatArray(3)
        val u = FloatArray(3)
        internal val tPrev = FloatArray(3)
        internal val tNow = FloatArray(3)
        internal val vTPrev = FloatArray(3)
        internal val downRest = FloatArray(3)
        internal val gEff = FloatArray(3)
        internal var primed = false
        internal val w = FloatArray(16)
    }

    private val tv = FloatArray(3)
    private val tv2 = FloatArray(3)
    private val tv3 = FloatArray(3)

    private fun softTarget(b: SoftBone, out: FloatArray) {
        mul4(drivers[b.driver].now, b.rest, b.w)
        tv[0] = 0f; tv[1] = b.cfg.tailLength; tv[2] = 0f
        point4(b.w, tv, out)
    }

    /** Gravity relative to rest: g·(down − R_parent·down_rest). Zero in the rest posture. */
    private fun softGravity(b: SoftBone) {
        val g = config.gravity * b.cfg.gravityFactor
        mul3(drivers[b.driver].now, b.downRest, tv2)
        norm3(tv2)
        b.gEff[0] = -g * tv2[0]
        b.gEff[1] = g * (-1f - tv2[1])
        b.gEff[2] = -g * tv2[2]
    }

    /** Static equilibrium under the current gravity (where a teleport lands: no settling). */
    private fun softEquilibrium(b: SoftBone) {
        softGravity(b)
        val omega = 2f * PI_F * b.cfg.frequencyHz
        val k = omega * omega
        for (a in 0..2) b.x[a] = b.gEff[a] / k
        val l = sqrt(dot3(b.x, b.x))
        if (l > b.cfg.maxDisplacement) for (a in 0..2) b.x[a] *= b.cfg.maxDisplacement / l
    }

    private fun stepSoft(b: SoftBone, dt: Float, n: Int, h: Float) {
        val cfg = b.cfg
        softTarget(b, b.tNow)
        // Velocity of the tissue point on the skeleton; impulse = its change since last frame.
        for (a in 0..2) tv[a] = (b.tNow[a] - b.tPrev[a]) / dt
        if (b.primed) {
            for (a in 0..2) b.u[a] -= cfg.inertiaFactor * (tv[a] - b.vTPrev[a])
        } else {
            b.primed = true
        }
        tv.copyInto(b.vTPrev)
        b.tNow.copyInto(b.tPrev)

        softGravity(b)

        val omega = 2f * PI_F * cfg.frequencyHz
        val k = omega * omega
        val c = 2f * cfg.dampingRatio * omega
        val maxD = cfg.maxDisplacement
        val x = b.x
        val u = b.u
        val damp = 1f / (1f + h * c)
        // Soft wall: beyond 60 % of the clamp the tissue stiffens (extra 8·k), so large
        // motions decelerate smoothly instead of hitting the hard clamp flat.
        val knee = SOFT_KNEE * maxD
        val kWall = 8f * k
        for (s in 0 until n) {
            val l0 = sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2])
            if (l0 > knee) {
                val f = -kWall * (l0 - knee) / l0 * h
                u[0] += f * x[0]; u[1] += f * x[1]; u[2] += f * x[2]
            }
            for (a in 0..2) {
                // Spring explicit (symplectic Euler), damping implicit: c·u' ≡ c·Δx/h, so the
                // damping integral matches the position change exactly. With an explicit
                // c·u the velocity jump of the frame impulse would be damped for a whole
                // substep, biasing the response low by ≈ c·h (16 % at 3 Hz, ζ 0.5).
                u[a] = (u[a] + h * (-k * x[a] + b.gEff[a])) * damp
                x[a] += h * u[a]
            }
            // Hard clamp: project back and remove the outward velocity.
            val len = sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2])
            if (len > maxD) {
                val s2 = maxD / len
                x[0] *= s2; x[1] *= s2; x[2] *= s2
                val nx = x[0] / maxD; val ny = x[1] / maxD; val nz = x[2] / maxD
                val vn = u[0] * nx + u[1] * ny + u[2] * nz
                if (vn > 0f) {
                    u[0] -= vn * nx; u[1] -= vn * ny; u[2] -= vn * nz
                }
            }
        }
        // Guard against anything non-finite (bad input matrices): back to rest.
        if (!(x[0].isFinite() && x[1].isFinite() && x[2].isFinite() && u[0].isFinite() && u[1].isFinite() && u[2].isFinite())) {
            x.fill(0f); u.fill(0f)
        }
    }

    private val q = FloatArray(4)
    private val m3 = FloatArray(16)

    private fun writeSoft(b: SoftBone) {
        val cfg = b.cfg
        val s = config.intensity
        // Displacement in the bone frame (current animated frame W = parent · rest).
        mul4(drivers[b.driver].now, b.rest, b.w)
        tv[0] = b.x[0] * s; tv[1] = b.x[1] * s; tv[2] = b.x[2] * s
        invMul3(b.w, tv, tv2) // tv2 = bone-local displacement
        // Swing: rotate +Y so the tip follows the perpendicular part of the displacement.
        val along = tv2[1]
        val px = tv2[0]; val pz = tv2[2]
        val p = sqrt(px * px + pz * pz)
        b.rest.copyInto(b.out)
        if (p > 1e-7f) {
            var angle = atan2(p, cfg.tailLength + along)
            val maxA = cfg.maxAngleDeg * DEG
            if (angle > maxA) angle = maxA
            // axis = Y × perp = (pz, 0, -px)/p
            axisAngle(pz / p, 0f, -px / p, angle, q)
            quatToMat(q, m3)
            mul4(b.rest, m3, b.out)
        }
        // Translation (in the parent frame): rest3x3 · (factor · local displacement), clamped.
        tv3[0] = tv2[0] * cfg.translationFactor
        tv3[1] = tv2[1] * cfg.translationFactor
        tv3[2] = tv2[2] * cfg.translationFactor
        val tl = sqrt(tv3[0] * tv3[0] + tv3[1] * tv3[1] + tv3[2] * tv3[2])
        if (tl > cfg.maxTranslation) {
            val k = cfg.maxTranslation / tl
            tv3[0] *= k; tv3[1] *= k; tv3[2] *= k
        }
        mul3(b.rest, tv3, tv)
        b.out[12] = b.rest[12] + tv[0]
        b.out[13] = b.rest[13] + tv[1]
        b.out[14] = b.rest[14] + tv[2]
    }

    /* ── colliders ── */

    inner class Collider(val driverA: Int, val driverB: Int, radius: Float, val sphere: Boolean) {
        /** Effective radius (possibly shrunk by the rest-pose auto-fit). */
        var radius = radius
            internal set
        internal val offA = FloatArray(3)
        internal val offB = FloatArray(3)
        internal val aPrev = FloatArray(3)
        internal val aNow = FloatArray(3)
        internal val bPrev = FloatArray(3)
        internal val bNow = FloatArray(3)
        internal val a = FloatArray(3)
        internal val b = FloatArray(3)
    }

    private fun evalCollider(c: Collider, now: Boolean) {
        val ma = if (now) drivers[c.driverA].now else drivers[c.driverA].rest
        val mb = if (now) drivers[c.driverB].now else drivers[c.driverB].rest
        point4(ma, c.offA, if (now) c.aNow else c.a)
        if (c.sphere) {
            (if (now) c.aNow else c.a).copyInto(if (now) c.bNow else c.b)
        } else {
            point4(mb, c.offB, if (now) c.bNow else c.b)
        }
    }

    private fun lerpCollider(c: Collider, f: Float) {
        for (i in 0..2) {
            c.a[i] = c.aPrev[i] + (c.aNow[i] - c.aPrev[i]) * f
            c.b[i] = c.bPrev[i] + (c.bNow[i] - c.bPrev[i]) * f
        }
    }

    /** Pushes the point p (offset [o] in [arr]) out of collider c; true if it moved. */
    private fun collide(c: Collider, arr: FloatArray, o: Int, pr: Float): Boolean {
        val r = c.radius + pr
        if (c.radius <= 0f) return false
        val ax = c.a[0]; val ay = c.a[1]; val az = c.a[2]
        val abx = c.b[0] - ax; val aby = c.b[1] - ay; val abz = c.b[2] - az
        val l2 = abx * abx + aby * aby + abz * abz
        var t = 0f
        if (l2 > 1e-10f) t = (((arr[o] - ax) * abx + (arr[o + 1] - ay) * aby + (arr[o + 2] - az) * abz) / l2).coerceIn(0f, 1f)
        val cx = ax + abx * t; val cy = ay + aby * t; val cz = az + abz * t
        val dx = arr[o] - cx; val dy = arr[o + 1] - cy; val dz = arr[o + 2] - cz
        val d2 = dx * dx + dy * dy + dz * dz
        if (d2 >= r * r || d2 < 1e-12f) return false
        val k = r / sqrt(d2)
        arr[o] = cx + dx * k; arr[o + 1] = cy + dy * k; arr[o + 2] = cz + dz * k
        return true
    }

    /** Distance from a point to collider c's core (segment), in its current a/b. */
    private fun coreDistance(c: Collider, arr: FloatArray, o: Int): Float {
        val ax = c.a[0]; val ay = c.a[1]; val az = c.a[2]
        val abx = c.b[0] - ax; val aby = c.b[1] - ay; val abz = c.b[2] - az
        val l2 = abx * abx + aby * aby + abz * abz
        var t = 0f
        if (l2 > 1e-10f) t = (((arr[o] - ax) * abx + (arr[o + 1] - ay) * aby + (arr[o + 2] - az) * abz) / l2).coerceIn(0f, 1f)
        val dx = arr[o] - (ax + abx * t); val dy = arr[o + 1] - (ay + aby * t); val dz = arr[o + 2] - (az + abz * t)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun fitColliders(chain: HairChain) {
        val pts = FloatArray((chain.n + 1) * 3)
        val p0 = FloatArray(3)
        val qr = FloatArray(4)
        val rootRest = drivers[chain.rootDriver].rest
        point4(rootRest, chain.tRest0, p0)
        matToQuat(rootRest, qr)
        restChain(chain, p0, qr, pts)
        for (c in colliders) {
            evalCollider(c, now = false)
            var d = Float.MAX_VALUE
            for (i in 1..chain.n) d = min(d, coreDistance(c, pts, i * 3))
            val allowed = d - chain.cfg.hairRadius - FIT_MARGIN
            if (c.radius > allowed) c.radius = max(0f, allowed)
        }
    }

    /* ── hair ── */

    /**
     * Position-based chain (VRM springbone): particle 0 is pinned to the animated root
     * joint; particle k+1 is the tail of bone k. Per substep, for each bone in order:
     * Verlet with drag, a pull along the bone's rest direction (relative to its already
     * simulated parent), gravity, length constraint, colliders, angle clamp; then the bone
     * is rotated onto its particle so the next bone's rest direction follows it.
     */
    inner class HairChain(val rootDriver: Int, restLocals: List<FloatArray>, val cfg: HairConfig) {
        val n = restLocals.size
        val restLocal: Array<FloatArray> = Array(n) { restLocals[it].copyOf() }
        /** Local transforms to write on hair.0 … hair.n-1. */
        val out: Array<FloatArray> = Array(n) { restLocals[it].copyOf() }
        internal val qRest: Array<FloatArray> = Array(n) { FloatArray(4).also { q -> matToQuat(restLocal[it], q) } }
        internal val axis: Array<FloatArray> = Array(n) { FloatArray(3) }
        internal val len = FloatArray(n)
        internal val stiff = FloatArray(n)
        internal val tRest0 = floatArrayOf(restLocal[0][12], restLocal[0][13], restLocal[0][14])
        /** Particle positions (model space), (n+1)×3. */
        val pos = FloatArray((n + 1) * 3)
        internal val prev = FloatArray((n + 1) * 3)
        internal val p0Prev = FloatArray(3)
        internal val p0Now = FloatArray(3)
        internal val qPrev = floatArrayOf(0f, 0f, 0f, 1f)
        internal val qNow = floatArrayOf(0f, 0f, 0f, 1f)
        internal val qRootRestInv = FloatArray(4).also { q ->
            matToQuat(drivers[rootDriver].rest, q); conj(q, q)
        }
        /** Gravity acceleration applied this frame (m/s², model space). */
        internal val gEff = FloatArray(3)
        internal val gDir = floatArrayOf(cfg.gravityX, cfg.gravityY, cfg.gravityZ).also { norm3(it) }

        init {
            for (k in 0 until n) {
                if (k + 1 < n) {
                    // Direction/length to the child joint, in this bone's local frame
                    // (rotation part of the child's rest local transform ⇒ its translation).
                    val t = restLocal[k + 1]
                    val l = sqrt(t[12] * t[12] + t[13] * t[13] + t[14] * t[14])
                    len[k] = l
                    if (l > 1e-6f) {
                        axis[k][0] = t[12] / l; axis[k][1] = t[13] / l; axis[k][2] = t[14] / l
                    } else {
                        axis[k][1] = 1f
                    }
                } else {
                    // Last bone: no child joint in glTF; bone axis is local +Y.
                    axis[k][1] = 1f
                    len[k] = if (cfg.tipLength > 0f) cfg.tipLength else if (n > 1) len[k - 1] else 0.05f
                }
                val f = if (n > 1) k / (n - 1f) else 0f
                stiff[k] = cfg.stiffnessRoot + (cfg.stiffnessTip - cfg.stiffnessRoot) * f
            }
        }
    }

    private fun rootPose(chain: HairChain) {
        val m = drivers[chain.rootDriver].now
        point4(m, chain.tRest0, chain.p0Now)
        matToQuat(m, chain.qNow)
        // Keep the hemisphere consistent with last frame for nlerp.
        if (dot4(chain.qNow, chain.qPrev) < 0f) for (i in 0..3) chain.qNow[i] = -chain.qNow[i]
        val gA = chain.cfg.gravity * VRM_FPS
        if (chain.cfg.gravityRelativeToRest) {
            quatMul(chain.qNow, chain.qRootRestInv, qt)
            quatRotate(qt, chain.gDir, tv3)
            for (a in 0..2) chain.gEff[a] = gA * (chain.gDir[a] - tv3[a])
        } else {
            for (a in 0..2) chain.gEff[a] = gA * chain.gDir[a]
        }
    }

    private val qp = FloatArray(4)
    private val qw = FloatArray(4)
    private val qd = FloatArray(4)
    private val qt = FloatArray(4)
    private val d0 = FloatArray(3)
    private val d1 = FloatArray(3)

    /** Rest configuration of a chain given the root position/rotation. */
    private fun restChain(chain: HairChain, p0: FloatArray, qRoot: FloatArray, out: FloatArray) {
        qRoot.copyInto(qp)
        out[0] = p0[0]; out[1] = p0[1]; out[2] = p0[2]
        for (k in 0 until chain.n) {
            quatMul(qp, chain.qRest[k], qw)
            quatRotate(qw, chain.axis[k], d0)
            for (a in 0..2) out[(k + 1) * 3 + a] = out[k * 3 + a] + d0[a] * chain.len[k]
            qw.copyInto(qp)
        }
    }

    private fun stepHair(chain: HairChain, f: Float, h: Float) {
        val cfg = chain.cfg
        val pos = chain.pos
        val prev = chain.prev
        // Pinned root at this substep's time.
        for (a in 0..2) pos[a] = chain.p0Prev[a] + (chain.p0Now[a] - chain.p0Prev[a]) * f
        for (i in 0..3) qp[i] = chain.qPrev[i] + (chain.qNow[i] - chain.qPrev[i]) * f
        normQ(qp)

        val retain = (1f - cfg.drag).coerceIn(0f, 1f).pow(h * VRM_FPS)
        val h2 = h * h
        val gEff = chain.gEff
        val cosMax = cos(cfg.maxAngleDeg * DEG)
        val sinMax = sin(cfg.maxAngleDeg * DEG)
        for (k in 0 until chain.n) {
            quatMul(qp, chain.qRest[k], qw)
            quatRotate(qw, chain.axis[k], d0)
            val sA = chain.stiff[k] * VRM_FPS
            val hi = k * 3
            val ti = (k + 1) * 3
            for (a in 0..2) {
                val cur = pos[ti + a]
                val nx = cur + (cur - prev[ti + a]) * retain + (d0[a] * sA + gEff[a]) * h2
                prev[ti + a] = cur
                pos[ti + a] = nx
            }
            constrainLength(pos, hi, ti, chain.len[k])
            // Angle clamp against the rest direction (safety net; before collisions so it
            // can never push a particle back inside a collider).
            for (a in 0..2) d1[a] = pos[ti + a] - pos[hi + a]
            norm3(d1)
            val cs = dot3(d0, d1)
            if (cs < cosMax) {
                for (a in 0..2) tv[a] = d1[a] - d0[a] * cs
                if (norm3(tv) > 1e-6f) {
                    for (a in 0..2) pos[ti + a] = pos[hi + a] + (d0[a] * cosMax + tv[a] * sinMax) * chain.len[k]
                }
            }
            for (iter in 0 until 3) {
                var hit = false
                for (c in colliders) if (collide(c, pos, ti, cfg.hairRadius)) hit = true
                if (!hit) break
                constrainLength(pos, hi, ti, chain.len[k])
            }
            for (a in 0..2) d1[a] = pos[ti + a] - pos[hi + a]
            norm3(d1)
            if (!(pos[ti].isFinite() && pos[ti + 1].isFinite() && pos[ti + 2].isFinite())) {
                needsTeleport = true
                return
            }
            fromTo(d0, d1, qd)
            quatMul(qd, qw, qp)
        }
    }

    private fun constrainLength(pos: FloatArray, hi: Int, ti: Int, l: Float) {
        val dx = pos[ti] - pos[hi]; val dy = pos[ti + 1] - pos[hi + 1]; val dz = pos[ti + 2] - pos[hi + 2]
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        if (d < 1e-9f) return
        val k = l / d
        pos[ti] = pos[hi] + dx * k; pos[ti + 1] = pos[hi + 1] + dy * k; pos[ti + 2] = pos[hi + 2] + dz * k
    }

    private fun writeHair(chain: HairChain) {
        val s = config.intensity
        chain.qNow.copyInto(qp)
        val pos = chain.pos
        for (k in 0 until chain.n) {
            quatMul(qp, chain.qRest[k], qw)
            quatRotate(qw, chain.axis[k], d0)
            for (a in 0..2) d1[a] = pos[(k + 1) * 3 + a] - pos[k * 3 + a]
            norm3(d1)
            fromTo(d0, d1, qd)
            if (s < 1f) nlerpIdentity(qd, s)
            quatMul(qd, qw, qt) // simulated model rotation of bone k
            // local = parent⁻¹ · model
            conj(qp, qw)
            quatMul(qw, qt, q)
            composeTRS(chain.restLocal[k], q, chain.out[k])
            qt.copyInto(qp)
        }
    }

    private fun writeOutputs() {
        for (b in softBones) writeSoft(b)
        for (c in hairChains) writeHair(c)
    }

    companion object {
        const val PI_F = 3.1415927f
        const val DEG = PI_F / 180f
        /** VRM parameters are defined per frame at this rate. */
        const val VRM_FPS = 60f
        private const val FIT_MARGIN = 0.003f
        private const val SOFT_KNEE = 0.6f
    }
}

/* ───────────────────────────── twist distribution ───────────────────────────── */

/**
 * Forearm twist: the hand's roll about the forearm axis, split over two helper bones.
 *
 * Blender rule (hand_poses.json): qf = R0·q_hand·R0⁻¹, angle = 2·atan2(qf.y, qf.w), where R0
 * is the hand's rest rotation in the forearm and q_hand its pose rotation (bone space).
 * At runtime the glTF local rotation of the hand is L = R0·q_hand, so qf = L·R0⁻¹ — the
 * hand's rotation delta expressed in the FOREARM frame — and needs no Blender constants:
 * R0 is the hand's rest local rotation read from the asset. The twist axis (forearm "Y")
 * is taken as the direction of the hand joint's rest translation in the forearm frame
 * (= the forearm→hand bone axis), so it holds even if the exporter changed axis
 * conventions. The twist angle is the swing–twist projection 2·atan2(qf·axis, qf.w)
 * (valid for both swing·twist and twist·swing orders), wrapped to (−180°, 180°].
 * Each helper bone gets R = Q(axis, factor·angle)·R_rest (a rotation about the axis in the
 * forearm frame; its origin lies on the axis, so its translation is unchanged).
 */
internal class ForearmTwist(
    handRestLocal: FloatArray,
    midRestLocal: FloatArray?,
    twistRestLocal: FloatArray?,
    private val midFactor: Float,
    private val twistFactor: Float,
) {
    private val qHandRestInv = FloatArray(4)
    val axis = FloatArray(3)
    private val midRest = midRestLocal?.copyOf()
    private val twistRest = twistRestLocal?.copyOf()
    private val qMidRest = FloatArray(4)
    private val qTwistRest = FloatArray(4)
    private val qh = FloatArray(4)
    private val qf = FloatArray(4)
    private val qa = FloatArray(4)
    private val qo = FloatArray(4)

    /** Last measured roll (radians), for debugging/tests. */
    var angle = 0f
        private set

    init {
        matToQuat(handRestLocal, qh)
        conj(qh, qHandRestInv)
        axis[0] = handRestLocal[12]; axis[1] = handRestLocal[13]; axis[2] = handRestLocal[14]
        if (norm3(axis) < 1e-6f) { axis[0] = 0f; axis[1] = 1f; axis[2] = 0f }
        midRest?.let { matToQuat(it, qMidRest) }
        twistRest?.let { matToQuat(it, qTwistRest) }
    }

    /** Reads the hand's current local matrix; writes the helper bones' local matrices. */
    fun compute(handLocal: FloatArray, outMid: FloatArray?, outTwist: FloatArray?) {
        matToQuat(handLocal, qh)
        quatMul(qh, qHandRestInv, qf)
        angle = twistAngle(qf, axis)
        if (midRest != null && outMid != null) {
            axisAngle(axis[0], axis[1], axis[2], angle * midFactor, qa)
            quatMul(qa, qMidRest, qo)
            composeTRS(midRest, qo, outMid)
        }
        if (twistRest != null && outTwist != null) {
            axisAngle(axis[0], axis[1], axis[2], angle * twistFactor, qa)
            quatMul(qa, qTwistRest, qo)
            composeTRS(twistRest, qo, outTwist)
        }
    }
}

/**
 * Upper-arm twist: counter-rotates [factor] (−0.5) of the Arm's own roll about its bone
 * axis. The roll is measured on the Arm's pose delta in its own frame, q = R_rest⁻¹·L,
 * about the Arm→ForeArm axis (direction of the forearm joint's rest translation). The
 * helper bone is a child of the Arm, so that axis is expressed in its parent frame.
 */
internal class UpperArmTwist(
    armRestLocal: FloatArray,
    foreArmRestLocal: FloatArray,
    twistRestLocal: FloatArray,
    private val factor: Float,
) {
    private val qArmRestInv = FloatArray(4)
    val axis = FloatArray(3)
    private val twistRest = twistRestLocal.copyOf()
    private val qTwistRest = FloatArray(4)
    private val qa = FloatArray(4)
    private val qd = FloatArray(4)
    private val qo = FloatArray(4)

    var angle = 0f
        private set

    init {
        matToQuat(armRestLocal, qa)
        conj(qa, qArmRestInv)
        axis[0] = foreArmRestLocal[12]; axis[1] = foreArmRestLocal[13]; axis[2] = foreArmRestLocal[14]
        if (norm3(axis) < 1e-6f) { axis[0] = 0f; axis[1] = 1f; axis[2] = 0f }
        matToQuat(twistRest, qTwistRest)
    }

    fun compute(armLocal: FloatArray, out: FloatArray) {
        matToQuat(armLocal, qa)
        quatMul(qArmRestInv, qa, qd)
        angle = twistAngle(qd, axis)
        axisAngle(axis[0], axis[1], axis[2], angle * factor, qa)
        quatMul(qa, qTwistRest, qo)
        composeTRS(twistRest, qo, out)
    }
}

/* ───────────────────────────── small math (no allocation) ───────────────────────────── */

/** Twist angle (radians, (−π, π]) of q about the unit axis a. */
internal fun twistAngle(q: FloatArray, a: FloatArray): Float {
    var p = q[0] * a[0] + q[1] * a[1] + q[2] * a[2]
    var w = q[3]
    if (w < 0f) { p = -p; w = -w }
    if (abs(p) < 1e-9f && w < 1e-9f) return 0f
    return 2f * atan2(p, w)
}

internal fun dot3(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
internal fun dot4(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]

/** Normalises in place; returns the previous length. */
internal fun norm3(v: FloatArray): Float {
    val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    if (l > 1e-12f) { v[0] /= l; v[1] /= l; v[2] /= l }
    return l
}

internal fun normQ(q: FloatArray) {
    val l = sqrt(dot4(q, q))
    if (l > 1e-12f) for (i in 0..3) q[i] /= l else { q[0] = 0f; q[1] = 0f; q[2] = 0f; q[3] = 1f }
}

internal fun conj(q: FloatArray, out: FloatArray) {
    out[0] = -q[0]; out[1] = -q[1]; out[2] = -q[2]; out[3] = q[3]
}

/** out = a·b (out may not alias a or b). */
internal fun quatMul(a: FloatArray, b: FloatArray, out: FloatArray) {
    val ax = a[0]; val ay = a[1]; val az = a[2]; val aw = a[3]
    val bx = b[0]; val by = b[1]; val bz = b[2]; val bw = b[3]
    out[0] = aw * bx + ax * bw + ay * bz - az * by
    out[1] = aw * by - ax * bz + ay * bw + az * bx
    out[2] = aw * bz + ax * by - ay * bx + az * bw
    out[3] = aw * bw - ax * bx - ay * by - az * bz
}

/** out = q·v·q⁻¹ (unit q). out may alias v. */
internal fun quatRotate(q: FloatArray, v: FloatArray, out: FloatArray) {
    val qx = q[0]; val qy = q[1]; val qz = q[2]; val qw = q[3]
    val vx = v[0]; val vy = v[1]; val vz = v[2]
    val tx = 2f * (qy * vz - qz * vy)
    val ty = 2f * (qz * vx - qx * vz)
    val tz = 2f * (qx * vy - qy * vx)
    out[0] = vx + qw * tx + (qy * tz - qz * ty)
    out[1] = vy + qw * ty + (qz * tx - qx * tz)
    out[2] = vz + qw * tz + (qx * ty - qy * tx)
}

internal fun axisAngle(x: Float, y: Float, z: Float, angle: Float, out: FloatArray) {
    val s = sin(angle * 0.5f)
    out[0] = x * s; out[1] = y * s; out[2] = z * s; out[3] = cos(angle * 0.5f)
}

/** Shortest rotation taking unit a onto unit b. */
internal fun fromTo(a: FloatArray, b: FloatArray, out: FloatArray) {
    val d = dot3(a, b)
    if (d < -0.999999f) {
        // 180°: any axis perpendicular to a.
        var x = 0f; var y = -a[2]; var z = a[1]
        if (y * y + z * z < 1e-6f) { x = a[2]; y = 0f; z = -a[0] }
        val l = sqrt(x * x + y * y + z * z)
        out[0] = x / l; out[1] = y / l; out[2] = z / l; out[3] = 0f
        return
    }
    out[0] = a[1] * b[2] - a[2] * b[1]
    out[1] = a[2] * b[0] - a[0] * b[2]
    out[2] = a[0] * b[1] - a[1] * b[0]
    out[3] = 1f + d
    normQ(out)
}

/** q ← nlerp(identity, q, s). */
internal fun nlerpIdentity(q: FloatArray, s: Float) {
    if (q[3] < 0f) for (i in 0..3) q[i] = -q[i]
    q[0] *= s; q[1] *= s; q[2] *= s
    q[3] = 1f - s + q[3] * s
    normQ(q)
}

/** Rotation of the upper 3x3 of a column-major matrix (scale removed). */
internal fun matToQuat(m: FloatArray, out: FloatArray) {
    val sx = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]).let { if (it < 1e-12f) 1f else it }
    val sy = sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6]).let { if (it < 1e-12f) 1f else it }
    val sz = sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10]).let { if (it < 1e-12f) 1f else it }
    // r{row}{col}
    val r00 = m[0] / sx; val r10 = m[1] / sx; val r20 = m[2] / sx
    val r01 = m[4] / sy; val r11 = m[5] / sy; val r21 = m[6] / sy
    val r02 = m[8] / sz; val r12 = m[9] / sz; val r22 = m[10] / sz
    val tr = r00 + r11 + r22
    if (tr > 0f) {
        val s = sqrt(tr + 1f) * 2f
        out[3] = 0.25f * s
        out[0] = (r21 - r12) / s
        out[1] = (r02 - r20) / s
        out[2] = (r10 - r01) / s
    } else if (r00 > r11 && r00 > r22) {
        val s = sqrt(1f + r00 - r11 - r22) * 2f
        out[3] = (r21 - r12) / s
        out[0] = 0.25f * s
        out[1] = (r01 + r10) / s
        out[2] = (r02 + r20) / s
    } else if (r11 > r22) {
        val s = sqrt(1f + r11 - r00 - r22) * 2f
        out[3] = (r02 - r20) / s
        out[0] = (r01 + r10) / s
        out[1] = 0.25f * s
        out[2] = (r12 + r21) / s
    } else {
        val s = sqrt(1f + r22 - r00 - r11) * 2f
        out[3] = (r10 - r01) / s
        out[0] = (r02 + r20) / s
        out[1] = (r12 + r21) / s
        out[2] = 0.25f * s
    }
    normQ(out)
}

/** Pure rotation matrix (column-major 4x4). */
internal fun quatToMat(q: FloatArray, out: FloatArray) {
    val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
    out[0] = 1f - 2f * (y * y + z * z); out[1] = 2f * (x * y + z * w); out[2] = 2f * (x * z - y * w); out[3] = 0f
    out[4] = 2f * (x * y - z * w); out[5] = 1f - 2f * (x * x + z * z); out[6] = 2f * (y * z + x * w); out[7] = 0f
    out[8] = 2f * (x * z + y * w); out[9] = 2f * (y * z - x * w); out[10] = 1f - 2f * (x * x + y * y); out[11] = 0f
    out[12] = 0f; out[13] = 0f; out[14] = 0f; out[15] = 1f
}

/** out = T(src)·R(q)·S(src): keeps translation and per-axis scale of [src], replaces its rotation. */
internal fun composeTRS(src: FloatArray, q: FloatArray, out: FloatArray) {
    val sx = sqrt(src[0] * src[0] + src[1] * src[1] + src[2] * src[2])
    val sy = sqrt(src[4] * src[4] + src[5] * src[5] + src[6] * src[6])
    val sz = sqrt(src[8] * src[8] + src[9] * src[9] + src[10] * src[10])
    val tx = src[12]; val ty = src[13]; val tz = src[14]
    quatToMat(q, out)
    for (i in 0..2) { out[i] *= sx; out[4 + i] *= sy; out[8 + i] *= sz }
    out[12] = tx; out[13] = ty; out[14] = tz
}

/** out = a·b (affine column-major; out may not alias). */
internal fun mul4(a: FloatArray, b: FloatArray, out: FloatArray) {
    for (c in 0..3) {
        val b0 = b[c * 4]; val b1 = b[c * 4 + 1]; val b2 = b[c * 4 + 2]; val b3 = b[c * 4 + 3]
        for (r in 0..3) out[c * 4 + r] = a[r] * b0 + a[4 + r] * b1 + a[8 + r] * b2 + a[12 + r] * b3
    }
}

/** out = M·p (point). out may alias p. */
internal fun point4(m: FloatArray, p: FloatArray, out: FloatArray) {
    val x = p[0]; val y = p[1]; val z = p[2]
    out[0] = m[0] * x + m[4] * y + m[8] * z + m[12]
    out[1] = m[1] * x + m[5] * y + m[9] * z + m[13]
    out[2] = m[2] * x + m[6] * y + m[10] * z + m[14]
}

/** out = M3·v (direction). out may alias v. */
internal fun mul3(m: FloatArray, v: FloatArray, out: FloatArray) {
    val x = v[0]; val y = v[1]; val z = v[2]
    out[0] = m[0] * x + m[4] * y + m[8] * z
    out[1] = m[1] * x + m[5] * y + m[9] * z
    out[2] = m[2] * x + m[6] * y + m[10] * z
}

/** out = M3⁻¹·v (general 3x3 inverse, handles scale). out may alias v. */
internal fun invMul3(m: FloatArray, v: FloatArray, out: FloatArray) {
    val a = m[0]; val b = m[4]; val c = m[8]
    val d = m[1]; val e = m[5]; val f = m[9]
    val g = m[2]; val h = m[6]; val i = m[10]
    val A = e * i - f * h; val B = -(d * i - f * g); val C = d * h - e * g
    val det = a * A + b * B + c * C
    if (abs(det) < 1e-20f) { v.copyInto(out); return }
    val inv = 1f / det
    val x = v[0]; val y = v[1]; val z = v[2]
    out[0] = (A * x + (c * h - b * i) * y + (b * f - c * e) * z) * inv
    out[1] = (B * x + (a * i - c * g) * y + (c * d - a * f) * z) * inv
    out[2] = (C * x + (b * g - a * h) * y + (a * e - b * d) * z) * inv
}

/** out = M⁻¹ for an affine matrix (general 3x3 part). out may not alias m. */
internal fun invertAffine(m: FloatArray, out: FloatArray) {
    val a = m[0]; val b = m[4]; val c = m[8]
    val d = m[1]; val e = m[5]; val f = m[9]
    val g = m[2]; val h = m[6]; val i = m[10]
    val A = e * i - f * h; val B = -(d * i - f * g); val C = d * h - e * g
    val det = a * A + b * B + c * C
    val inv = if (abs(det) < 1e-20f) 0f else 1f / det
    // rows of the inverse 3x3
    val i00 = A * inv; val i01 = (c * h - b * i) * inv; val i02 = (b * f - c * e) * inv
    val i10 = B * inv; val i11 = (a * i - c * g) * inv; val i12 = (c * d - a * f) * inv
    val i20 = C * inv; val i21 = (b * g - a * h) * inv; val i22 = (a * e - b * d) * inv
    out[0] = i00; out[1] = i10; out[2] = i20; out[3] = 0f
    out[4] = i01; out[5] = i11; out[6] = i21; out[7] = 0f
    out[8] = i02; out[9] = i12; out[10] = i22; out[11] = 0f
    val tx = m[12]; val ty = m[13]; val tz = m[14]
    out[12] = -(i00 * tx + i01 * ty + i02 * tz)
    out[13] = -(i10 * tx + i11 * ty + i12 * tz)
    out[14] = -(i20 * tx + i21 * ty + i22 * tz)
    out[15] = 1f
}
