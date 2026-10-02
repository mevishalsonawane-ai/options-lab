package com.optionslab.app.ira

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Ira's orb: a folded grid of about 18,000 dots drawn on the graphics chip (OpenGL ES 2.0), from the approved concept.
 * Volatility folds it, the trend colours it (blue-violet flat, cyan-green up, magenta down), each tick ripples across
 * it, and it listens, thinks and answers with its own motion. Set [vol], [trend] and [mode]; it eases to them.
 *
 * Thinking (the owner's wish, 2026-10-02: "like Age of Ultron"): the surface splinters into shards, three dashed
 * orbital rings spin up around it, data streams out from the core, the core pulses, scan bands sweep it and the whole
 * globe drifts and breathes as if weighing something. Pinch to zoom, drag to turn it, double-tap to put it back.
 */
class OrbView(context: Context) : GLSurfaceView(context) {
    /** 0 calm .. 1 wild. */
    @Volatile var vol = 0.1f
    /** -1 down .. +1 up. */
    @Volatile var trend = 0f
    /** 0 idle, 1 listening, 2 thinking, 3 answering. */
    @Volatile var mode = 0

    /** A single tap on the globe (the chat hides). */
    @Volatile var onTap: (() -> Unit)? = null

    /** The owner's zoom (pinch) and turn (drag), eased to in the renderer. */
    @Volatile private var zoom = 1f
    @Volatile private var yaw = 0f
    @Volatile private var pitch = 0f

    private val scaler = android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: android.view.ScaleGestureDetector): Boolean { zoom = (zoom * d.scaleFactor).coerceIn(0.6f, 2.8f); return true }
    })
    private val gestures = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: android.view.MotionEvent) = true
        override fun onScroll(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaler.isInProgress) return false
            yaw -= dx / max(1, width) * 3.2f
            pitch = (pitch - dy / max(1, height) * 2.4f).coerceIn(-1.2f, 1.2f)
            return true
        }
        override fun onDoubleTap(e: android.view.MotionEvent): Boolean { zoom = 1f; yaw = 0f; pitch = 0f; return true }
        override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean { onTap?.invoke(); return onTap != null }
    })

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(e); gestures.onTouchEvent(e)
        return true
    }

    private val renderer = R()

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private inner class R : Renderer {
        private var prog = 0
        private var count = 0
        private lateinit var buf: FloatBuffer
        private val u = HashMap<String, Int>()
        private var w = 1; private var h = 1
        private val t0 = System.nanoTime()
        private var last = t0
        private var rot = 0f; private var speak = 0f; private var voice = 0f; private var listen = 0f; private var think = 0f
        private var v = 0.1f; private var tr = 0f
        private var answer = 0f; private var z = 1f; private var yw = 0f; private var pt = 0f
        private var ringProg = 0; private var ringCount = 0
        private lateinit var ringBuf: FloatBuffer
        private val ru2 = HashMap<String, Int>()
        private val rips = ArrayDeque<FloatArray>()     // x, y, z, start time, up(1/0)
        private var nextTick = 0f
        private val rnd = java.util.Random(7)

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            val pts = ArrayList<Float>()
            val rows = 110
            for (r in 1 until rows) {
                val lat = r.toDouble() / rows * PI
                val y = cos(lat).toFloat(); val rr = sin(lat)
                val n = max(6, Math.round(rr * 220).toInt())
                for (j in 0 until n) { val a = j.toDouble() / n * 2 * PI; pts += (cos(a) * rr).toFloat(); pts += y; pts += (sin(a) * rr).toFloat() }
            }
            count = pts.size / 3
            buf = ByteBuffer.allocateDirect(pts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(pts.toFloatArray()); position(0) }
            prog = link(VS, FS)
            listOf("uTime", "uAmp", "uSpeed", "uRot", "uTilt", "uVoice", "uListen", "uThink", "uSize", "uAspect", "uTop", "uBot", "uRip", "uRipUp", "uZoom", "uOff")
                .forEach { u[it] = GLES20.glGetUniformLocation(prog, it) }
            // The halo: three orbital rings, the data streaming from the core, and the core itself (one point).
            val hp = ArrayList<Float>()
            for (ring in 0 until 3) for (j in 0 until 520) { hp += (j / 520f * 6.2832f); hp += ring.toFloat(); hp += rnd.nextFloat(); hp += rnd.nextFloat() }
            for (j in 0 until 420) { hp += 0f; hp += -1f; hp += rnd.nextFloat(); hp += rnd.nextFloat() }
            hp += 0f; hp += -2f; hp += 0f; hp += 0f
            ringCount = hp.size / 4
            ringBuf = ByteBuffer.allocateDirect(hp.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(hp.toFloatArray()); position(0) }
            ringProg = link(RING_VS, RING_FS)
            listOf("uTime", "uThink", "uAns", "uRot", "uTilt", "uZoom", "uAspect", "uSize", "uOff", "uTop")
                .forEach { ru2[it] = GLES20.glGetUniformLocation(ringProg, it) }
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { w = width; h = max(1, height); GLES20.glViewport(0, 0, width, height) }

        override fun onDrawFrame(gl: GL10?) {
            val now = System.nanoTime()
            val dt = min(0.05f, (now - last) / 1e9f); last = now
            val t = (now - t0) / 1e9f
            v += (vol - v) * 0.05f; tr += (trend - tr) * 0.05f
            rot += dt * (if (mode == 2) 0.45f else 0.12f)
            speak += dt
            val target = if (mode == 3) (0.5f + 0.5f * sin(speak * 10) * sin(speak * 2.6f)) * 0.05f else 0f
            voice += (target - voice) * 0.3f
            listen += ((if (mode == 1) 1f else 0f) - listen) * 0.08f
            think += ((if (mode == 2) 1f else 0f) - think) * 0.08f
            answer += ((if (mode == 3) 1f else 0f) - answer) * 0.08f
            z += (zoom - z) * 0.2f; yw += (yaw - yw) * 0.2f; pt += (pitch - pt) * 0.2f
            // Thinking: the globe drifts and breathes, as if weighing something.
            val offX = think * (sin(t * 0.9f) * 0.07f + sin(t * 2.3f) * 0.02f)
            val offY = think * (cos(t * 1.3f) * 0.05f)
            val zoomNow = z * (1f + think * 0.05f * sin(t * 3.1f) + answer * 0.02f * sin(t * 7f))
            val tiltNow = 0.28f + sin(t * 0.2f) * 0.06f + think * 0.12f * sin(t * 0.7f) + pt
            val rotNow = rot + yw
            if (t > nextTick) {
                val up = rnd.nextFloat() < 0.5f + tr * 0.4f
                val lat = if (up) rnd.nextFloat() * 1.1f else (PI.toFloat() - rnd.nextFloat() * 1.1f)
                val lon = rnd.nextFloat() * 6.283f
                rips.addLast(floatArrayOf(sin(lat) * cos(lon), cos(lat), sin(lat) * sin(lon), t, if (up) 1f else 0f))
                if (rips.size > 6) rips.removeFirst()
                nextTick = t + (1.1f - v * 0.7f) * (0.5f + rnd.nextFloat())
            }
            val rv = FloatArray(24); val ru = FloatArray(6)
            for (i in 0 until 6) {
                val r = rips.elementAtOrNull(i)
                if (r != null) { rv[i * 4] = r[0]; rv[i * 4 + 1] = r[1]; rv[i * 4 + 2] = r[2]; rv[i * 4 + 3] = t - r[3]; ru[i] = r[4] } else rv[i * 4 + 3] = -1f
            }
            val top = if (tr >= 0) mix(REST_TOP, UP_TOP, tr) else mix(REST_TOP, DOWN_TOP, -tr)
            val bot = if (tr >= 0) mix(REST_BOT, UP_BOT, tr) else mix(REST_BOT, DOWN_BOT, -tr)

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(prog)
            GLES20.glUniform1f(u["uTime"]!!, t)
            GLES20.glUniform1f(u["uAmp"]!!, 0.13f + v * 0.2f)
            GLES20.glUniform1f(u["uSpeed"]!!, 0.6f + v * 1.4f + think * 0.8f)
            GLES20.glUniform1f(u["uRot"]!!, rotNow)
            GLES20.glUniform1f(u["uTilt"]!!, tiltNow)
            GLES20.glUniform1f(u["uZoom"]!!, zoomNow)
            GLES20.glUniform2f(u["uOff"]!!, offX, offY)
            GLES20.glUniform1f(u["uVoice"]!!, voice)
            GLES20.glUniform1f(u["uListen"]!!, listen)
            GLES20.glUniform1f(u["uThink"]!!, think)
            GLES20.glUniform1f(u["uSize"]!!, max(2f, w / 260f))
            GLES20.glUniform1f(u["uAspect"]!!, w.toFloat() / h)
            GLES20.glUniform3fv(u["uTop"]!!, 1, top, 0)
            GLES20.glUniform3fv(u["uBot"]!!, 1, bot, 0)
            GLES20.glUniform4fv(u["uRip"]!!, 6, rv, 0)
            GLES20.glUniform1fv(u["uRipUp"]!!, 6, ru, 0)
            val a = GLES20.glGetAttribLocation(prog, "aPos")
            GLES20.glEnableVertexAttribArray(a)
            GLES20.glVertexAttribPointer(a, 3, GLES20.GL_FLOAT, false, 0, buf)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)
            GLES20.glDisableVertexAttribArray(a)

            // The halo, faint at rest, alive when thinking or answering.
            GLES20.glUseProgram(ringProg)
            GLES20.glUniform1f(ru2["uTime"]!!, t)
            GLES20.glUniform1f(ru2["uThink"]!!, think)
            GLES20.glUniform1f(ru2["uAns"]!!, answer)
            GLES20.glUniform1f(ru2["uRot"]!!, rotNow)
            GLES20.glUniform1f(ru2["uTilt"]!!, tiltNow)
            GLES20.glUniform1f(ru2["uZoom"]!!, zoomNow)
            GLES20.glUniform1f(ru2["uAspect"]!!, w.toFloat() / h)
            GLES20.glUniform1f(ru2["uSize"]!!, max(2f, w / 260f))
            GLES20.glUniform2f(ru2["uOff"]!!, offX, offY)
            GLES20.glUniform3fv(ru2["uTop"]!!, 1, top, 0)
            val b = GLES20.glGetAttribLocation(ringProg, "aP")
            GLES20.glEnableVertexAttribArray(b)
            GLES20.glVertexAttribPointer(b, 4, GLES20.GL_FLOAT, false, 0, ringBuf)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, ringCount)
            GLES20.glDisableVertexAttribArray(b)
        }

        private fun mix(a: FloatArray, b: FloatArray, k: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * k }

        private fun link(vs: String, fs: String): Int {
            fun sh(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
            return GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, sh(GLES20.GL_VERTEX_SHADER, vs))
                GLES20.glAttachShader(it, sh(GLES20.GL_FRAGMENT_SHADER, fs))
                GLES20.glLinkProgram(it)
            }
        }
    }

    companion object {
        private val REST_TOP = floatArrayOf(0.29f, 0.66f, 1.0f); private val REST_BOT = floatArrayOf(0.73f, 0.50f, 1.0f)
        private val UP_TOP = floatArrayOf(0.30f, 0.95f, 1.0f); private val UP_BOT = floatArrayOf(0.25f, 0.95f, 0.70f)
        private val DOWN_TOP = floatArrayOf(0.62f, 0.42f, 1.0f); private val DOWN_BOT = floatArrayOf(1.0f, 0.36f, 0.66f)

        /** 3D simplex noise: Ashima Arts / Stefan Gustavson (MIT licence). */
        private const val NOISE = """
vec3 mod289(vec3 x){return x-floor(x*(1.0/289.0))*289.0;}
vec4 mod289(vec4 x){return x-floor(x*(1.0/289.0))*289.0;}
vec4 permute(vec4 x){return mod289(((x*34.0)+1.0)*x);}
vec4 taylorInvSqrt(vec4 r){return 1.79284291400159-0.85373472095314*r;}
float snoise(vec3 v){
  const vec2 C=vec2(1.0/6.0,1.0/3.0); const vec4 D=vec4(0.0,0.5,1.0,2.0);
  vec3 i=floor(v+dot(v,C.yyy)); vec3 x0=v-i+dot(i,C.xxx);
  vec3 g=step(x0.yzx,x0.xyz); vec3 l=1.0-g; vec3 i1=min(g.xyz,l.zxy); vec3 i2=max(g.xyz,l.zxy);
  vec3 x1=x0-i1+C.xxx; vec3 x2=x0-i2+C.yyy; vec3 x3=x0-D.yyy;
  i=mod289(i);
  vec4 p=permute(permute(permute(i.z+vec4(0.0,i1.z,i2.z,1.0))+i.y+vec4(0.0,i1.y,i2.y,1.0))+i.x+vec4(0.0,i1.x,i2.x,1.0));
  float n_=0.142857142857; vec3 ns=n_*D.wyz-D.xzx;
  vec4 j=p-49.0*floor(p*ns.z*ns.z); vec4 x_=floor(j*ns.z); vec4 y_=floor(j-7.0*x_);
  vec4 x=x_*ns.x+ns.yyyy; vec4 y=y_*ns.x+ns.yyyy; vec4 h=1.0-abs(x)-abs(y);
  vec4 b0=vec4(x.xy,y.xy); vec4 b1=vec4(x.zw,y.zw);
  vec4 s0=floor(b0)*2.0+1.0; vec4 s1=floor(b1)*2.0+1.0; vec4 sh=-step(h,vec4(0.0));
  vec4 a0=b0.xzyw+s0.xzyw*sh.xxyy; vec4 a1=b1.xzyw+s1.xzyw*sh.zzww;
  vec3 p0=vec3(a0.xy,h.x); vec3 p1=vec3(a0.zw,h.y); vec3 p2=vec3(a1.xy,h.z); vec3 p3=vec3(a1.zw,h.w);
  vec4 norm=taylorInvSqrt(vec4(dot(p0,p0),dot(p1,p1),dot(p2,p2),dot(p3,p3)));
  p0*=norm.x; p1*=norm.y; p2*=norm.z; p3*=norm.w;
  vec4 m=max(0.6-vec4(dot(x0,x0),dot(x1,x1),dot(x2,x2),dot(x3,x3)),0.0); m=m*m;
  return 42.0*dot(m*m,vec4(dot(p0,x0),dot(p1,x1),dot(p2,x2),dot(p3,x3)));
}
"""

        private val VS = """
precision highp float;
attribute vec3 aPos;
uniform float uTime, uAmp, uSpeed, uRot, uTilt, uVoice, uListen, uThink, uSize, uAspect, uZoom;
uniform vec2 uOff;
uniform vec4 uRip[6];
uniform float uRipUp[6];
varying float vLight, vFace, vLat, vHit, vHitUp, vBand;
$NOISE
float sq(float x){ return x * x; }
float hitAt(vec3 p, out float up){
  float best = 0.0; up = 1.0;
  for (int i = 0; i < 6; i++) {
    float age = uRip[i].w;
    if (age < 0.0 || age > 2.6) continue;
    float ang = acos(clamp(dot(p, uRip[i].xyz), -1.0, 1.0));
    float w = exp(-sq((ang - age * 1.5) * 11.0)) * (1.0 - age / 2.6);
    if (w > best) { best = w; up = uRipUp[i]; }
  }
  return best;
}
vec3 shape(vec3 p){
  float t = uTime * uSpeed;
  float d = 1.0 + uAmp * snoise(p * 1.15 + vec3(0.0, t * 0.22, t * 0.13)) + uAmp * 0.45 * snoise(p * 2.3 - vec3(t * 0.17, 0.0, t * 0.11));
  d += uVoice * (0.6 + 0.4 * snoise(p * 3.0 + t));
  float up; d += 0.06 * hitAt(p, up);
  float lat = acos(clamp(p.y, -1.0, 1.0));
  d += uListen * 0.05 * max(0.0, sin(lat * 9.0 - uTime * 6.0)) * exp(-lat * 1.3);
  // Thinking: the surface splinters into shards that rise and fall, and a heartbeat runs through it.
  float shard = max(0.0, snoise(floor(p * 7.0) * 0.37 + vec3(uTime * 0.9, 0.0, uTime * 0.6)));
  d += uThink * (0.22 * shard * shard + 0.025 * sin(uTime * 5.0));
  return p * d;
}
vec3 rotate(vec3 v){
  float cr = cos(uRot), sr = sin(uRot), ct = cos(uTilt), st = sin(uTilt);
  vec3 a = vec3(v.x * cr + v.z * sr, v.y, -v.x * sr + v.z * cr);
  return vec3(a.x, a.y * ct - a.z * st, a.y * st + a.z * ct);
}
void main(){
  vec3 p = normalize(aPos);
  vec3 q = shape(p);
  vec3 t1 = normalize(cross(p, abs(p.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
  vec3 t2 = cross(p, t1);
  vec3 qa = shape(normalize(p + t1 * 0.025)); vec3 qb = shape(normalize(p + t2 * 0.025));
  vec3 n = normalize(cross(qa - q, qb - q));
  if (dot(n, q) < 0.0) n = -n;
  vec3 w = rotate(q); vec3 nw = rotate(n);
  vec3 L = normalize(vec3(-0.55, 0.65, 0.55));
  vLight = 0.22 + 0.78 * max(0.0, dot(nw, L));
  vFace = nw.z;
  vLat = p.y;
  float up; vHit = hitAt(p, up); vHitUp = up;
  vBand = uThink * max(exp(-sq((p.y - sin(uTime * 1.6)) * 6.0)), 0.8 * exp(-sq((p.x * cos(uTime) + p.z * sin(uTime) - sin(uTime * 2.3)) * 9.0)));
  float persp = 1.0 / (2.4 - w.z * 0.6);
  gl_Position = vec4(w.x * persp * 1.3 * uZoom / uAspect + uOff.x, w.y * persp * 1.3 * uZoom + uOff.y, 0.0, 1.0);
  gl_PointSize = uSize * (0.75 + 0.5 * (w.z * 0.5 + 0.5)) * (1.0 + uVoice * 2.0) * (0.7 + 0.3 * uZoom);
}
"""

        /** The halo: rings (y = ring 0..2), streaming particles (y = -1) and the core (y = -2). */
        private const val RING_VS = """
precision highp float;
attribute vec4 aP;
uniform float uTime, uThink, uAns, uRot, uTilt, uZoom, uAspect, uSize;
uniform vec2 uOff;
uniform vec3 uTop;
varying float vA;
varying vec3 vC;
vec3 rotate(vec3 v){
  float cr = cos(uRot), sr = sin(uRot), ct = cos(uTilt), st = sin(uTilt);
  vec3 a = vec3(v.x * cr + v.z * sr, v.y, -v.x * sr + v.z * cr);
  return vec3(a.x, a.y * ct - a.z * st, a.y * st + a.z * ct);
}
vec3 tiltRing(vec3 v, float ax, float az){
  vec3 a = vec3(v.x, v.y * cos(ax) - v.z * sin(ax), v.y * sin(ax) + v.z * cos(ax));
  return vec3(a.x * cos(az) - a.y * sin(az), a.x * sin(az) + a.y * cos(az), a.z);
}
void main(){
  float live = clamp(uThink + uAns * 0.5, 0.0, 1.0);
  vec3 q; float size = 1.0;
  if (aP.y > -0.5) {
    float i = aP.y;
    float spin = uTime * (0.15 + 0.25 * i + live * (1.2 + 0.6 * i)) * (mod(i, 2.0) < 0.5 ? 1.0 : -1.0);
    float a = aP.x + spin;
    float r = 1.38 + 0.2 * i + live * 0.06 * sin(uTime * 2.0 + i);
    q = tiltRing(vec3(cos(a) * r, 0.0, sin(a) * r), 1.1 + 0.55 * i + 0.3 * sin(uTime * 0.3 + i), 0.5 * i + 0.2 * sin(uTime * 0.21));
    float dash = step(0.38, fract(aP.x * 9.0 / 6.2832 + i * 0.3));
    float node = step(0.985, fract(aP.x * 3.0 / 6.2832 + uTime * 0.05));
    vA = (0.06 + 0.55 * live) * dash + node * (0.25 + 0.75 * live);
    size = 0.8 + node * 2.2;
    vC = mix(uTop, vec3(0.85, 0.95, 1.0), 0.5 + 0.3 * node);
  } else if (aP.y > -1.5) {
    float th = aP.z * 6.2832, ph = acos(2.0 * aP.w - 1.0);
    vec3 dir = vec3(sin(ph) * cos(th), cos(ph), sin(ph) * sin(th));
    float f = fract(uTime * (0.25 + 0.35 * live) + aP.z * 7.0 + aP.w * 3.0);
    q = dir * (0.35 + f * 1.9);
    vA = live * (1.0 - f) * 0.9;
    size = 0.7 + 1.3 * (1.0 - f);
    vC = vec3(0.75, 0.9, 1.0);
  } else {
    q = vec3(0.0);
    float beat = 0.5 + 0.5 * sin(uTime * (3.0 + 4.0 * live));
    vA = 0.08 + live * (0.35 + 0.35 * beat);
    size = 26.0 + live * 30.0 * beat;
    vC = mix(uTop, vec3(1.0), 0.6);
  }
  vec3 w = rotate(q);
  float persp = 1.0 / (2.4 - w.z * 0.6);
  gl_Position = vec4(w.x * persp * 1.3 * uZoom / uAspect + uOff.x, w.y * persp * 1.3 * uZoom + uOff.y, 0.0, 1.0);
  gl_PointSize = uSize * size * (0.75 + 0.5 * (w.z * 0.5 + 0.5)) * (0.7 + 0.3 * uZoom);
}
"""

        private const val RING_FS = """
precision mediump float;
varying float vA;
varying vec3 vC;
void main(){
  vec2 c = gl_PointCoord - 0.5;
  float r = length(c);
  if (r > 0.5) discard;
  float a = smoothstep(0.5, 0.0, r) * vA;
  gl_FragColor = vec4(vC * a, a);
}
"""

        private const val FS = """
precision mediump float;
uniform vec3 uTop, uBot;
varying float vLight, vFace, vLat, vHit, vHitUp, vBand;
void main(){
  vec2 c = gl_PointCoord - 0.5;
  float r = length(c);
  if (r > 0.5) discard;
  float soft = smoothstep(0.5, 0.18, r);
  vec3 col = mix(uBot, uTop, smoothstep(-0.75, 0.55, vLat));
  col *= 0.35 + 0.9 * vLight;
  col = mix(col, vHitUp > 0.5 ? vec3(0.25, 0.95, 0.70) : vec3(1.0, 0.38, 0.6), clamp(vHit * 0.8, 0.0, 0.7));
  col = mix(col, vec3(0.92, 0.96, 1.0), vBand * 0.75);
  float face = smoothstep(-0.35, 0.25, vFace);
  float a = soft * (0.12 + 0.88 * face) * (0.55 + 0.45 * vLight) + vHit * 0.15 + vBand * 0.3;
  gl_FragColor = vec4(col * a, a);
}
"""
    }
}
