package io.github.revenge.xposed.modules

import android.media.AudioRecord
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils.Log
import io.github.revenge.xposed.modules.bridge.BridgeModule
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * KadonMod mic gain module.
 *
 * Hooks AudioRecord.read(ByteBuffer,int) — the method Discord's WebRTC uses
 * to capture the mic (confirmed via static analysis of com.discord 347.12).
 *
 * Chain: gate → gain (0..+40 dB) → compressor → look-ahead limiter → tanh clip.
 * Fail-open: any error passes audio through unchanged. Circuit breaker after
 * 20 consecutive errors disables processing for the session.
 */
object MicGainModule : Module() {

    private const val TAG = "KadonMod"
    private const val CONFIG_FILE = "kadon_config.json"
    private const val MAX_ERRORS = 20

    // Mic AudioSource values we process (from android.media.MediaRecorder.AudioSource)
    // 0=DEFAULT, 1=MIC, 6=VOICE_RECOGNITION, 7=VOICE_COMMUNICATION
    private val MIC_SOURCES = setOf(0, 1, 6, 7)

    // --- config (atomic, loaded async, never on audio thread) ---
    data class Config(val gainDb: Float, val preset: String)

    private val configRef = AtomicReference(Config(16f, "Jhakaas"))
    private val errorCount = AtomicInteger(0)
    private val activeRef = AtomicReference(false)
    private val lastRmsRef = AtomicReference(-120f)
    private val lastPeakRef = AtomicReference(-120f)
    private val sampleRateRef = AtomicReference(0)
    private val channelsRef = AtomicReference(0)

    private var configFile: File? = null

    // --- DSP state (one processor per sample-rate/channel combo, lazy init) ---
    private var processor: MicGainProcessor? = null
    private var procSampleRate = 0
    private var procChannels = 0

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) {
        Log.i("KadonMod: loading, hooking AudioRecord.read")

        val cl = packageParam.classLoader
        val audioRecordClass = cl.safeLoadClass("android.media.AudioRecord")
            ?: run { Log.e("KadonMod: AudioRecord class not found"); return }

        // Hook the class-level method so it survives reconnect/region change
        audioRecordClass.hookMethod("read", ByteBuffer::class.java, Int::class.javaPrimitiveType) {
            after {
                try {
                    val ar = thisObject as? AudioRecord ?: return@after
                    val buffer = args[0] as? ByteBuffer ?: return@after
                    val bytesRead = result as? Int ?: return@after
                    if (bytesRead <= 0) return@after

                    // Circuit breaker check
                    if (errorCount.get() >= MAX_ERRORS) return@after

                    // Only process mic sources
                    val source = try { ar.audioSource } catch (_: Throwable) { -1 }
                    if (source !in MIC_SOURCES) return@after

                    // Read format info from AudioRecord
                    val sr = try { ar.sampleRate } catch (_: Throwable) { return@after }
                    val ch = try { ar.channelCount } catch (_: Throwable) { return@after }
                    val fmt = try { ar.audioFormat } catch (_: Throwable) { return@after }

                    // Only 16-bit PCM
                    if (fmt != android.media.AudioFormat.ENCODING_PCM_16BIT) return@after
                    if (ch != 1 && ch != 2) return@after

                    // Lazily create/reset processor if format changed
                    if (processor == null || procSampleRate != sr || procChannels != ch) {
                        processor = MicGainProcessor(sr, ch)
                        procSampleRate = sr
                        procChannels = ch
                        sampleRateRef.set(sr)
                        channelsRef.set(ch)
                        activeRef.set(true)
                        Log.i("KadonMod: processor created ${sr}Hz ${ch}ch")
                    }

                    val proc = processor ?: return@after
                    val cfg = configRef.get()
                    proc.setGainDb(cfg.gainDb)

                    // Process in place on the direct ByteBuffer
                    val frames = bytesRead / (2 * ch)  // 16-bit = 2 bytes/sample
                    if (frames <= 0) return@after

                    // Read bytes from direct buffer, process as shorts, write back
                    val pos = buffer.position()
                    val byteArr = ByteArray(bytesRead)
                    // Duplicate to avoid changing original position
                    val dup = buffer.duplicate()
                    dup.position(pos)
                    dup.get(byteArr)

                    // Convert bytes to shorts (little-endian)
                    val shorts = ShortArray(frames * ch)
                    for (i in shorts.indices) {
                        shorts[i] = ((byteArr[i * 2 + 1].toInt() shl 8) or (byteArr[i * 2].toInt() and 0xFF)).toShort()
                    }

                    proc.process(shorts, 0, frames)

                    // Convert shorts back to bytes and write to buffer
                    for (i in shorts.indices) {
                        byteArr[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
                        byteArr[i * 2 + 1] = (shorts[i].toInt() shr 8).toByte()
                    }
                    dup.position(pos)
                    dup.put(byteArr)

                    // Update meters (cheap, no alloc)
                    lastRmsRef.set(proc.lastOutRmsDb)
                    lastPeakRef.set(proc.lastOutPeakDb)

                    // Reset error count on success
                    errorCount.set(0)

                } catch (t: Throwable) {
                    val count = errorCount.incrementAndGet()
                    if (count == MAX_ERRORS) {
                        Log.e("KadonMod: $MAX_ERRORS consecutive errors, disabling for session", t)
                        activeRef.set(false)
                    } else if (count <= 3) {
                        Log.e("KadonMod: error $count", t)
                    }
                    // Fail-open: audio already passed through unchanged
                }
            }
        }

        // Also hook the short[] overload as fallback (some paths may use it)
        audioRecordClass.hookMethod("read", ShortArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType) {
            after {
                try {
                    if (errorCount.get() >= MAX_ERRORS) return@after
                    val ar = thisObject as? AudioRecord ?: return@after
                    val buf = args[0] as? ShortArray ?: return@after
                    val offset = args[1] as? Int ?: return@after
                    val size = args[2] as? Int ?: return@after
                    val resultVal = result as? Int ?: return@after
                    if (resultVal <= 0) return@after

                    val source = try { ar.audioSource } catch (_: Throwable) { -1 }
                    if (source !in MIC_SOURCES) return@after

                    val sr = try { ar.sampleRate } catch (_: Throwable) { return@after }
                    val ch = try { ar.channelCount } catch (_: Throwable) { return@after }

                    if (ch != 1 && ch != 2) return@after

                    if (processor == null || procSampleRate != sr || procChannels != ch) {
                        processor = MicGainProcessor(sr, ch)
                        procSampleRate = sr
                        procChannels = ch
                        sampleRateRef.set(sr)
                        channelsRef.set(ch)
                        activeRef.set(true)
                    }

                    val proc = processor ?: return@after
                    val cfg = configRef.get()
                    proc.setGainDb(cfg.gainDb)

                    val frames = resultVal  // short[] read returns frames
                    proc.process(buf, offset, frames)

                    lastRmsRef.set(proc.lastOutRmsDb)
                    lastPeakRef.set(proc.lastOutPeakDb)
                    errorCount.set(0)

                } catch (t: Throwable) {
                    val count = errorCount.incrementAndGet()
                    if (count == MAX_ERRORS) {
                        Log.e("KadonMod: $MAX_ERRORS consecutive errors (short[]), disabling")
                        activeRef.set(false)
                    }
                }
            }
        }

        Log.i("KadonMod: hooks installed")
    }

    override fun onContext(context: android.content.Context) {
        // Load config async (never on audio thread)
        val dir = File(context.filesDir, "kadon").apply { if (!isDirectory) mkdirs() }
        configFile = File(dir, CONFIG_FILE)

        Thread {
            try {
                loadConfig()
            } catch (t: Throwable) {
                Log.e("KadonMod: config load failed", t)
            }
        }.start()

        // Register bridge methods
        BridgeModule.registerMethod("kadon.getConfig") {
            val cfg = configRef.get()
            mapOf(
                "gainDb" to cfg.gainDb.toDouble(),
                "preset" to cfg.preset,
                "active" to activeRef.get(),
                "sampleRate" to sampleRateRef.get(),
                "channels" to channelsRef.get(),
                "lastRms" to lastRmsRef.get().toDouble(),
                "lastPeak" to lastPeakRef.get().toDouble(),
                "errors" to errorCount.get(),
            )
        }

        BridgeModule.registerMethod("kadon.setGain") { args ->
            val gain = (args.getOrNull(0) as? Number)?.toFloat() ?: return@registerMethod null
            val clamped = gain.coerceIn(0f, 40f)
            val preset = when {
                clamped == 0f -> "Saaf"
                clamped <= 8f -> "Bindass"
                clamped <= 16f -> "Jhakaas"
                clamped <= 24f -> "Tagda"
                clamped <= 32f -> "Bawaal"
                else -> "Dhamaaka"
            }
            configRef.set(Config(clamped, preset))
            saveConfig()
            mapOf("gainDb" to clamped.toDouble(), "preset" to preset)
        }

        BridgeModule.registerMethod("kadon.setPreset") { args ->
            val name = args.getOrNull(0) as? String ?: return@registerMethod null
            val gain = when (name) {
                "Saaf" -> 0f; "Bindass" -> 8f; "Jhakaas" -> 16f
                "Tagda" -> 24f; "Bawaal" -> 32f; "Dhamaaka" -> 40f
                else -> return@registerMethod null
            }
            configRef.set(Config(gain, name))
            saveConfig()
            mapOf("gainDb" to gain.toDouble(), "preset" to name)
        }

        BridgeModule.registerMethod("kadon.getMeters") {
            mapOf(
                "sampleRate" to sampleRateRef.get(),
                "channels" to channelsRef.get(),
                "active" to activeRef.get(),
                "lastRms" to lastRmsRef.get().toDouble(),
                "lastPeak" to lastPeakRef.get().toDouble(),
                "errors" to errorCount.get(),
                "circuitBroken" to (errorCount.get() >= MAX_ERRORS),
            )
        }

        Log.i("KadonMod: bridge methods registered")
    }

    private fun loadConfig() {
        val f = configFile ?: return
        if (!f.exists()) return
        val text = f.readText()
        // Simple JSON parse (no external dep needed)
        val gainMatch = Regex("\"gainDb\"\\s*:\\s*([\\d.]+)").find(text)
        val presetMatch = Regex("\"preset\"\\s*:\\s*\"([^\"]+)\"").find(text)
        val gain = gainMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 16f
        val preset = presetMatch?.groupValues?.get(1) ?: "Jhakaas"
        configRef.set(Config(gain.coerceIn(0f, 40f), preset))
        Log.i("KadonMod: config loaded gain=${gain}dB preset=$preset")
    }

    private fun saveConfig() {
        try {
            val f = configFile ?: return
            val cfg = configRef.get()
            f.writeText("""{"gainDb":${cfg.gainDb},"preset":"${cfg.preset}"}""")
        } catch (t: Throwable) {
            Log.e("KadonMod: config save failed", t)
        }
    }
}

/**
 * Pure Kotlin mic DSP. Allocation-free after construction.
 * Chain: gate → gain → compressor → look-ahead limiter → tanh clip.
 */
internal class MicGainProcessor(
    val sampleRate: Int,
    val channels: Int
) {
    companion object {
        const val MIN_GAIN_DB = 0f
        const val MAX_GAIN_DB = 40f
        const val DEFAULT_GAIN_DB = 16f
        private const val LIMITER_CEIL_DB = -1.25f
        private const val CLIP_CEIL_DB = -1.05f
        private const val CLIP_KNEE = 0.70f
        private const val LOOKAHEAD_MS = 4f
        private const val COMP_THR_DB = -18f
        private const val COMP_RATIO = 4f
        private const val COMP_ATTACK_MS = 2f
        private const val COMP_RELEASE_MS = 120f
        private const val GATE_BASE_THR_DB = -52f
        private const val GATE_THR_PER_GAIN_DB = 0.5f
        private const val GATE_SLOPE = 2f
        private const val GATE_ATTACK_MS = 1f
        private const val GATE_RELEASE_MS = 60f
        private const val GATE_MIN_GAIN_DB = -60f
        private const val GAIN_SMOOTH_MS = 30f
        private const val LIMITER_RELEASE_MS = 80f
        private const val MIN_AMP = 1e-6f
        const val DB_FLOOR = -120f
    }

    private val lookahead = max(1, (sampleRate * LOOKAHEAD_MS / 1000f).roundToInt())
    private val winLen = lookahead + 1
    private val limiterAmp = dbToAmp(LIMITER_CEIL_DB)
    private val clipAmp = dbToAmp(CLIP_CEIL_DB)
    private val kGain = step(GAIN_SMOOTH_MS)
    private val kGateAtk = step(GATE_ATTACK_MS)
    private val kGateRel = step(GATE_RELEASE_MS)
    private val kCompAtk = step(COMP_ATTACK_MS)
    private val kCompRel = step(COMP_RELEASE_MS)
    private val kLimRel = step(LIMITER_RELEASE_MS)

    private var gainDbTarget = DEFAULT_GAIN_DB.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
    private var gainDbSmooth = gainDbTarget
    private var gateEnv = 0f
    private var gateGain = 1f
    private var compEnv = 0f
    private var limGain = 1f
    private val delay0 = FloatArray(lookahead)
    private val delay1 = if (channels == 2) FloatArray(lookahead) else delay0
    private var sampleIdx = 0L
    private val ampRing = FloatArray(winLen)
    private val dqPos = LongArray(winLen)
    private var dqHead = 0
    private var dqLen = 0

    var lastInRmsDb = DB_FLOOR; private set
    var lastOutRmsDb = DB_FLOOR; private set
    var lastOutPeakDb = DB_FLOOR; private set

    fun setGainDb(db: Float) {
        val v = if (db.isNaN() || db.isInfinite()) DEFAULT_GAIN_DB else db
        gainDbTarget = v.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
    }

    val gainDb: Float get() = gainDbTarget

    fun stateOk(): Boolean =
        gainDbSmooth.isFinite() && gateEnv.isFinite() && gateGain.isFinite() &&
            compEnv.isFinite() && limGain.isFinite()

    fun process(buf: ShortArray, offset: Int, frames: Int) {
        if (frames <= 0) return
        var outPeak = 0f
        val stereo = channels == 2
        val inv = 1f / 32768f
        var i = offset
        repeat(frames) {
            val s0 = buf[i] * inv
            var det = abs(s0)
            var s1 = 0f
            if (stereo) { s1 = buf[i + 1] * inv; val d1 = abs(s1); if (d1 > det) det = d1 }

            gainDbSmooth += (gainDbTarget - gainDbSmooth) * kGain
            val userAmp = dbToAmp(gainDbSmooth)
            gateEnv = if (det > gateEnv) det + (gateEnv - det) * kGateAtk else det + (gateEnv - det) * kGateRel

            var gateTarget = 1f
            if (gateEnv > MIN_AMP) {
                val envDb = ampToDb(gateEnv)
                val thr = GATE_BASE_THR_DB + GATE_THR_PER_GAIN_DB * gainDbSmooth
                if (envDb < thr) {
                    var red = (envDb - thr) * GATE_SLOPE
                    if (red < GATE_MIN_GAIN_DB) red = GATE_MIN_GAIN_DB
                    gateTarget = dbToAmp(red)
                }
            }
            gateGain = if (gateTarget > gateGain) gateGain + (gateTarget - gateGain) * kGateAtk else gateGain + (gateTarget - gateGain) * kGateRel

            val stage1 = gateGain * userAmp
            val detP = det * stage1
            compEnv = if (detP > compEnv) detP + (compEnv - detP) * kCompAtk else detP + (compEnv - detP) * kCompRel
            var compAmp = 1f
            if (compEnv > MIN_AMP) {
                val eDb = ampToDb(compEnv)
                if (eDb > COMP_THR_DB) compAmp = dbToAmp((eDb - COMP_THR_DB) * (1f / COMP_RATIO - 1f))
            }

            val g = stage1 * compAmp
            val v0 = s0 * g
            val v1 = if (stereo) s1 * g else 0f

            val slot = (sampleIdx % lookahead).toInt()
            val d0 = delay0[slot]; delay0[slot] = v0
            var dd1 = 0f
            if (stereo) { dd1 = delay1[slot]; delay1[slot] = v1 }
            val idx = sampleIdx; sampleIdx++

            var a = abs(v0)
            if (stereo) { val a1 = abs(v1); if (a1 > a) a = a1 }
            pushWindow(idx, a)
            val wMax = ampRing[(dqPos[dqHead] % winLen).toInt()]
            val lrTarget = if (wMax > MIN_AMP) { val r = limiterAmp / wMax; if (r > 1f) 1f else r } else 1f
            limGain = if (lrTarget < limGain) lrTarget else { val rise = limGain + (1f - limGain) * kLimRel; if (rise < lrTarget) rise else lrTarget }

            val y0 = softClip(d0 * limGain)
            buf[i] = snToShort(y0)
            val p0 = abs(buf[i] * inv); if (p0 > outPeak) outPeak = p0
            if (stereo) {
                val y1 = softClip(dd1 * limGain)
                buf[i + 1] = snToShort(y1)
                val p1 = abs(buf[i + 1] * inv); if (p1 > outPeak) outPeak = p1
            }
            i += channels
        }
        lastOutPeakDb = if (outPeak > 0f) ampToDb(outPeak) else DB_FLOOR
    }

    private fun pushWindow(idx: Long, a: Float) {
        ampRing[(idx % winLen).toInt()] = a
        val minKeep = idx - (winLen - 1)
        while (dqLen > 0 && dqPos[dqHead] < minKeep) { dqHead = (dqHead + 1) % winLen; dqLen-- }
        while (dqLen > 0) { val back = (dqHead + dqLen - 1) % winLen; if (ampRing[(dqPos[back] % winLen).toInt()] <= a) dqLen-- else break }
        val tail = (dqHead + dqLen) % winLen; dqPos[tail] = idx; dqLen++
    }

    private fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= CLIP_KNEE) return x
        if (a >= clipAmp) return if (x >= 0f) clipAmp else -clipAmp
        val t = (a - CLIP_KNEE) / (clipAmp - CLIP_KNEE)
        val v = CLIP_KNEE + (clipAmp - CLIP_KNEE) * tanh(t)
        return if (x >= 0f) v else -v
    }

    private fun snToShort(y: Float): Short {
        val s = y * 32767f
        return when { s >= 32767f -> 32767.toShort(); s <= -32768f -> (-32768).toShort(); else -> s.roundToInt().toShort() }
    }

    private fun step(timeMs: Float): Float { val tau = timeMs * 0.001f * sampleRate; return if (tau <= 1f) 1f else (1f - exp(-1f / tau)) }
    private fun dbToAmp(db: Float): Float = if (db <= DB_FLOOR) 0f else exp(db * 0.11512925f)
    private fun ampToDb(a: Float): Float = if (a <= 1e-6f) DB_FLOOR else 20f * log10(a)
}
