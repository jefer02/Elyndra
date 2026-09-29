package com.elyndra.launcher.ui.masha

import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.TransformManager
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.FilamentInstance

/**
 * Filament adapter for [SpringBones], [ForearmTwist] and [UpperArmTwist].
 *
 * Construct it right after the model is loaded and BEFORE the first clip is applied
 * (e.g. in HoloRig's init): it caches the rest local transforms of the procedural bones
 * and the rest model-space matrices of the drivers from the current transforms. Every
 * bone it drives is rewritten each frame from that cached rest (clips may key them —
 * the clip value is overridden).
 *
 * Per frame, on the main thread, in this order:
 *  1. clips (`animator.applyAnimation` / `applyCrossFade`)
 *  2. [twist] (reads Hand/Arm locals written by the clip)
 *  3. other procedural writes that move parents (gaze/head look by the orchestrator)
 *  4. [springs] (reads the parents' world transforms, writes breast/glute/hair locals)
 *  5. `animator.updateBoneMatrices()` — MUST be called after 2–4, or the skin won't
 *     see the procedural bones.
 * [frame] does 2 + 4 when nothing sits in between.
 *
 * Missing bones are skipped silently (the current v1 model has none of them), so this is
 * safe to construct on any Masha model; [active] tells whether anything was bound.
 *
 * Model space: drivers are read with getWorldTransform and brought into the asset root
 * space (root⁻¹·world), so the ModelNode's placement/scale/rotation in the scene never
 * excites the springs; the simulation is in the glTF's metres.
 */
internal class SpringBonesFilament(
    engine: Engine,
    private val asset: FilamentAsset,
    private val instance: FilamentInstance,
    val config: SpringConfig = SpringConfig(),
) {
    private val tm: TransformManager = engine.transformManager
    private val rootInst = tm.getInstance(instance.root)

    private val names = HashMap<String, Int>()

    private val core = SpringBones(config)

    /* drivers: entity → driver index, and the TM instance to read */
    private val driverOf = HashMap<Int, Int>()
    private var driverInst = IntArray(0)

    private class Out(val inst: Int, val m: FloatArray)
    private val softOut = ArrayList<Out>()
    private val hairOut = ArrayList<Out>()

    private class Forearm(val hand: Int, val mid: Int, val twist: Int, val rig: ForearmTwist) {
        val mMid = FloatArray(16)
        val mTwist = FloatArray(16)
    }
    private class UpperArm(val arm: Int, val twist: Int, val rig: UpperArmTwist) {
        val m = FloatArray(16)
    }
    private val forearms = ArrayList<Forearm>()
    private val upperArms = ArrayList<UpperArm>()

    private val tmpW = FloatArray(16)
    private val tmpL = FloatArray(16)
    private val rootWorld = FloatArray(16)
    private val rootInv = FloatArray(16)

    private var last = -1L

    /** True if any spring or twist bone was found on this model. */
    val active: Boolean
        get() = softOut.isNotEmpty() || hairOut.isNotEmpty() || forearms.isNotEmpty() || upperArms.isNotEmpty()

    /** Debug/tuning access to the solver (state, fitted collider radii). */
    internal val solver: SpringBones get() = core

    init {
        for (e in instance.entities) asset.getName(e)?.let { names.putIfAbsent(it, e) }
        updateRootInverse()
        bindSoft(config.breastBones, config.chest)
        bindSoft(config.gluteBones, config.glute)
        bindHair()
        bindTwist()
        driverInst = IntArray(driverOf.size)
        for ((entity, index) in driverOf) driverInst[index] = tm.getInstance(entity)
        Log.i(TAG, "bound soft=${softOut.size} hair=${hairOut.size} forearm=${forearms.size} upperArm=${upperArms.size}")
    }

    private fun entity(name: String): Int {
        names[name]?.let { return it }
        // Fallback for assets whose instance entities are not in the name map.
        val found = asset.getEntitiesByName(name)
        return if (found.isNotEmpty()) found[0] else 0
    }

    private fun localOf(entity: Int): FloatArray = FloatArray(16).also { tm.getTransform(tm.getInstance(entity), it) }

    private fun modelOf(inst: Int, out: FloatArray) {
        tm.getWorldTransform(inst, tmpW)
        mul4(rootInv, tmpW, out)
    }

    private fun updateRootInverse() {
        tm.getWorldTransform(rootInst, rootWorld)
        invertAffine(rootWorld, rootInv)
    }

    private fun driverFor(entity: Int): Int {
        driverOf[entity]?.let { return it }
        val rest = FloatArray(16)
        modelOf(tm.getInstance(entity), rest)
        val i = core.addDriver(rest)
        driverOf[entity] = i
        return i
    }

    private fun bindSoft(boneNames: List<String>, cfg: SoftTissueConfig) {
        for (name in boneNames) {
            val e = entity(name)
            if (e == 0) continue
            val inst = tm.getInstance(e)
            val parent = tm.getParent(inst)
            if (parent == 0) continue
            val bone = core.addSoftTissue(driverFor(parent), localOf(e), cfg)
            softOut += Out(inst, bone.out)
        }
    }

    private fun bindHair() {
        val cfg = config.hair
        // hair.0 … hair.N, discovered by prefix; must form a parent→child chain.
        val chain = ArrayList<Int>()
        var k = 0
        while (true) {
            val e = entity(cfg.prefix + k)
            if (e == 0) break
            if (k > 0 && tm.getParent(tm.getInstance(e)) != chain.last()) {
                Log.w(TAG, "${cfg.prefix}$k is not a child of ${cfg.prefix}${k - 1}; chain stops at $k bones")
                break
            }
            chain += e
            k++
        }
        if (chain.isEmpty()) return
        val rootParent = tm.getParent(tm.getInstance(chain[0]))
        if (rootParent == 0) return
        val rootDriver = driverFor(rootParent)
        // Colliders first (the chain's auto-fit looks at them).
        val a = FloatArray(3)
        val b = FloatArray(3)
        for (c in cfg.colliders) {
            val ea = entity(c.bone)
            if (ea == 0) continue
            val eb = c.bone2?.let { entity(it) } ?: ea
            if (eb == 0) continue
            a[0] = c.ox; a[1] = c.oy; a[2] = c.oz
            b[0] = c.ox2; b[1] = c.oy2; b[2] = c.oz2
            core.addCollider(driverFor(ea), a, driverFor(eb), b, c.radius, sphere = c.bone2 == null)
        }
        val h = core.addHairChain(rootDriver, chain.map { localOf(it) }, cfg)
        for (i in chain.indices) hairOut += Out(tm.getInstance(chain[i]), h.out[i])
        core.colliders.forEachIndexed { i, c -> Log.i(TAG, "hair collider $i radius=${"%.3f".format(c.radius)}") }
    }

    private fun bindTwist() {
        val t = config.twist
        if (!t.enabled) return
        for ((side, s) in listOf("Left" to "L", "Right" to "R")) {
            val hand = entity("mixamorig:${side}Hand")
            val fore = entity("mixamorig:${side}ForeArm")
            val arm = entity("mixamorig:${side}Arm")
            val mid = entity("masha:forearm_twist_mid.$s")
            val tw = entity("masha:forearm_twist.$s")
            val ut = entity("masha:upperarm_twist.$s")
            if (hand != 0 && (mid != 0 || tw != 0)) {
                val rig = ForearmTwist(
                    localOf(hand),
                    if (mid != 0) localOf(mid) else null,
                    if (tw != 0) localOf(tw) else null,
                    t.forearmMid, t.forearmTwist,
                )
                forearms += Forearm(
                    tm.getInstance(hand),
                    if (mid != 0) tm.getInstance(mid) else 0,
                    if (tw != 0) tm.getInstance(tw) else 0,
                    rig,
                )
            }
            if (arm != 0 && fore != 0 && ut != 0) {
                upperArms += UpperArm(
                    tm.getInstance(arm), tm.getInstance(ut),
                    UpperArmTwist(localOf(arm), localOf(fore), localOf(ut), t.upperArm),
                )
            }
        }
    }

    /** Twist bones from the Hand/Arm local rotations the clip just wrote. */
    fun twist() {
        if (!config.twist.enabled) return
        for (f in forearms) {
            tm.getTransform(f.hand, tmpL)
            f.rig.compute(tmpL, if (f.mid != 0) f.mMid else null, if (f.twist != 0) f.mTwist else null)
            if (f.mid != 0) tm.setTransform(f.mid, f.mMid)
            if (f.twist != 0) tm.setTransform(f.twist, f.mTwist)
        }
        for (u in upperArms) {
            tm.getTransform(u.arm, tmpL)
            u.rig.compute(tmpL, u.m)
            tm.setTransform(u.twist, u.m)
        }
    }

    /** Runs the springs for this frame and writes the soft/hair bones. */
    fun springs(frameTimeNanos: Long) {
        if (softOut.isEmpty() && hairOut.isEmpty()) return
        val dt = if (last < 0) 0f else (frameTimeNanos - last) / 1e9f
        last = frameTimeNanos
        updateRootInverse()
        for (i in driverInst.indices) modelOf(driverInst[i], core.driver(i))
        core.advance(dt)
        for (o in softOut) tm.setTransform(o.inst, o.m)
        // Parent before child (chain order), although world updates are immediate anyway.
        for (o in hairOut) tm.setTransform(o.inst, o.m)
    }

    /** [twist] then [springs]. Call `animator.updateBoneMatrices()` afterwards. */
    fun frame(frameTimeNanos: Long) {
        twist()
        springs(frameTimeNanos)
    }

    /** Snap the springs to the current pose on the next frame (teleport, resume, clip cut). */
    fun reset() {
        core.reset()
        last = -1L
    }

    private companion object {
        const val TAG = "SpringBones"
    }
}
