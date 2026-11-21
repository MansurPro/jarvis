package ai.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** The voice sphere from the web HUD (see DESIGN.md): points of light on a sphere plus three orbital rings.
 *  Breathes when idle, ripples with [level] while listening/speaking, swirls while working, greys out offline. */
class SphereView(ctx: Context) : View(ctx) {
    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING, OFFLINE }

    var mode = Mode.IDLE
    /** 0..1 loudness of whoever is talking; read every frame. */
    var level: () -> Float = { 0f }

    private val n = 900
    private val pts = FloatArray(n * 3)
    private val seed = FloatArray(n) { Math.random().toFloat() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private var last = 0L
    private var lvl = 0f
    private var swirl = 0f
    private var spin = 0.07f
    private var heat = 0.12f
    private var gain = 0.85f
    private var mono = 0f

    init {
        val golden = PI * (3 - sqrt(5.0))
        for (i in 0 until n) { // Fibonacci sphere: even spacing
            val y = 1 - (i / (n - 1f)) * 2
            val r = sqrt(1 - y * y)
            val a = golden * i
            pts[i * 3] = (cos(a) * r).toFloat(); pts[i * 3 + 1] = y; pts[i * 3 + 2] = (sin(a) * r).toFloat()
        }
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun ease(v: Float, target: Float, k: Float) = v + (target - v) * k

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.05f)
        last = now
        t += dt
        val k = 1 - exp(-dt * 3.5f)
        val (tSwirl, tSpin, tHeat, tGain) = when (mode) {
            Mode.IDLE -> listOf(0f, 0.07f, 0.12f, 0.85f)
            Mode.LISTENING -> listOf(0f, 0.04f, 0.55f, 1.15f)
            Mode.THINKING -> listOf(1f, 0.45f, 0.3f, 1f)
            Mode.SPEAKING -> listOf(0f, 0.09f, 0.38f, 1.05f)
            Mode.OFFLINE -> listOf(0f, 0.02f, 0f, 0.4f)
        }
        swirl = ease(swirl, tSwirl, k); spin = ease(spin, tSpin, k); heat = ease(heat, tHeat, k); gain = ease(gain, tGain, k)
        mono = ease(mono, if (mode == Mode.OFFLINE) 1f else 0f, k)
        val target = level().coerceIn(0f, 1f)
        lvl = ease(lvl, target, if (target > lvl) 1 - exp(-dt * 30f) else 1 - exp(-dt * 6f))

        val cx = width / 2f; val cy = height / 2f
        val scale = minOf(width, height) * 0.3f
        val rot = t * spin
        val cr = cos(rot); val sr = sin(rot)
        val tilt = 0.38f; val ct = cos(tilt); val st = sin(tilt)

        // rings first (behind), as faint dotted orbits
        for ((radius, tiltR, dir) in listOf(Triple(1.34f, 1.22f, 1f), Triple(1.46f, -1.02f, -1f), Triple(1.2f, 1.5708f, 0.6f))) {
            val steps = 220
            val ra = t * 0.11f * dir
            for (i in 0 until steps) {
                if (radius > 1.4f && i % 6 > 1) continue
                val a = i / steps.toFloat() * 2 * PI.toFloat() + ra
                val x = cos(a) * radius; val z0 = sin(a) * radius
                val y = -z0 * sin(tiltR); val z = z0 * cos(tiltR)
                val p = 3.4f / (3.4f - z)
                paint.color = color(0f, (0.22f + 0.38f * ((z + 1.1f) / 2.2f).coerceIn(0f, 1f)) * gain)
                c.drawCircle(cx + x * p * scale, cy - y * p * scale, 1.1f * resources.displayMetrics.density * 0.7f, paint)
            }
        }

        val dot = resources.displayMetrics.density
        for (i in 0 until n) {
            var x = pts[i * 3]; var y = pts[i * 3 + 1]; var z = pts[i * 3 + 2]
            val s = seed[i]
            val noise = sin(x * 4.1f + y * 3.3f + z * 5.7f + t * 1.7f + s * 6.283f) * 0.5f + 0.5f
            val band = sin(y * 9f - t * 7f + s * 2f) * 0.5f + 0.5f
            val voice = lvl * (0.08f + 0.24f * noise) * (0.55f + 0.45f * band)
            val r = 1f + 0.018f * sin(t * 1.3f + s * 3f) + voice
            val sw = swirl * y * 2.6f * sin(t * 0.9f) // swirl: twist by latitude
            val cs = cos(sw); val ss = sin(sw)
            val x1 = x * cs + z * ss; val z1 = -x * ss + z * cs
            x = x1 * r; y *= r; z = z1 * r
            val x2 = x * cr + z * sr; val z2 = -x * sr + z * cr // spin
            val y3 = y * ct - z2 * st; val z3 = y * st + z2 * ct // tilt
            val p = 3.4f / (3.4f - z3)
            val front = ((z3 + 1.1f) / 2.2f).coerceIn(0f, 1f)
            paint.color = color((heat + voice * 3).coerceIn(0f, 1f), (0.28f + 0.72f * front) * gain)
            c.drawCircle(cx + x2 * p * scale, cy - y3 * p * scale, (1.15f + lvl * 0.8f) * dot * p * 0.75f, paint)
        }
        postInvalidateOnAnimation()
    }

    /** cyan → white-hot by [hot]; slate when offline. */
    private fun color(hot: Float, alpha: Float): Int {
        val r = lerp(lerp(99f, 230f, hot), 115f, mono); val g = lerp(lerp(227f, 250f, hot), 140f, mono); val b = lerp(lerp(255f, 255f, hot), 158f, mono)
        return Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), r.toInt(), g.toInt(), b.toInt())
    }

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
}
