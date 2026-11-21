<script>
  // The voice sphere: a few thousand points of light on a sphere plus three orbital rings, drawn with WebGL2.
  // It breathes when idle, ripples with your voice, swirls while working and pulses with JARVIS's own voice.
  import { onMount } from 'svelte'

  let { mode = 'idle', level = () => 0 } = $props()
  let canvas
  let fallback = $state(false)

  // What each state looks like; every frame the live values ease toward these.
  const LOOK = {
    boot:         { expand: 0,    swirl: 0,   spin: 0.06, heat: 0.10, gain: 0.75, mono: 0 },
    idle:         { expand: 0,    swirl: 0,   spin: 0.07, heat: 0.12, gain: 0.85, mono: 0 },
    listening:    { expand: 0.07, swirl: 0,   spin: 0.04, heat: 0.55, gain: 1.15, mono: 0 },
    transcribing: { expand: 0.03, swirl: 0.4, spin: 0.25, heat: 0.40, gain: 1.0,  mono: 0 },
    thinking:     { expand: 0,    swirl: 1.0, spin: 0.45, heat: 0.30, gain: 1.0,  mono: 0 },
    speaking:     { expand: 0.02, swirl: 0,   spin: 0.09, heat: 0.38, gain: 1.05, mono: 0 },
    offline:      { expand: -0.04, swirl: 0,  spin: 0.02, heat: 0,    gain: 0.4,  mono: 1 },
  }

  const VERT = `#version 300 es
  in vec3 aPos; in float aSeed; in float aKind;
  uniform float uTime, uLevel, uExpand, uSwirl, uSpin, uAssemble, uDpr, uScale;
  out float vAlpha; out float vHot;
  mat3 rotY(float a) { float c = cos(a), s = sin(a); return mat3(c, 0., s, 0., 1., 0., -s, 0., c); }
  mat3 rotX(float a) { float c = cos(a), s = sin(a); return mat3(1., 0., 0., 0., c, -s, 0., s, c); }
  void main() {
    vec3 p = aPos;
    float hot = 0.;
    if (aKind < .5) {
      float n = sin(dot(p, vec3(4.1, 3.3, 5.7)) + uTime * 1.7 + aSeed * 6.283) * .5 + .5;
      float band = sin(p.y * 9. - uTime * 7. + aSeed * 2.) * .5 + .5;
      float voice = uLevel * (.08 + .24 * n) * (.55 + .45 * band);
      float r = 1. + .018 * sin(uTime * 1.3 + aSeed * 3.) + voice + uExpand;
      p = rotY(uSwirl * p.y * 2.6 * sin(uTime * .9)) * p;
      vec3 scatter = normalize(p + vec3(sin(aSeed * 91.), cos(aSeed * 47.), sin(aSeed * 13.))) * (1.15 + aSeed * 0.9); // standby: a loose cloud that assembles on start
      p = mix(scatter, p * r, uAssemble);
      p = rotX(.38) * rotY(uTime * uSpin) * p;
      hot = clamp(voice * 3., 0., 1.);
    } else {
      float dir = aKind < 1.5 ? 1. : aKind < 2.5 ? -1. : .6;
      float tilt = aKind < 1.5 ? 1.22 : aKind < 2.5 ? -1.02 : 1.5708;
      p = rotX(tilt) * rotY(uTime * .11 * dir + aSeed) * p;
      p *= mix(1.5, 1., uAssemble);
    }
    float persp = 3.4 / (3.4 - p.z);
    gl_Position = vec4(p.xy * persp * uScale, 0., 1.);
    float front = smoothstep(-1.1, 1.1, p.z);
    vAlpha = (aKind < .5 ? .28 + .72 * front : .22 + .38 * front) * (aKind < .5 ? 1. : uAssemble);
    vHot = hot;
    gl_PointSize = (aKind < .5 ? 2.3 + uLevel * 1.6 : 1.5) * uDpr * persp;
  }`

  const FRAG = `#version 300 es
  precision mediump float;
  in float vAlpha; in float vHot;
  uniform float uHeat, uGain, uMono;
  out vec4 o;
  void main() {
    vec2 c = gl_PointCoord * 2. - 1.;
    float d = dot(c, c);
    if (d > 1.) discard;
    float a = (1. - d); a *= a;
    vec3 cyan = vec3(.39, .89, 1.), white = vec3(.9, .98, 1.), slate = vec3(.45, .55, .62);
    vec3 col = mix(mix(cyan, white, clamp(uHeat + vHot, 0., 1.)), slate, uMono);
    float k = a * vAlpha * uGain;
    o = vec4(col * k, k);
  }`

  function geometry(n) {
    const pos = [], seed = [], kind = []
    const golden = Math.PI * (3 - Math.sqrt(5))
    for (let i = 0; i < n; i++) { // Fibonacci sphere: even spacing, no poles
      const y = 1 - (i / (n - 1)) * 2, r = Math.sqrt(1 - y * y), t = golden * i
      pos.push(Math.cos(t) * r, y, Math.sin(t) * r); seed.push(Math.random()); kind.push(0)
    }
    const ring = (count, radius, k, arc = 1, dashed = false) => {
      for (let i = 0; i < count; i++) {
        if (dashed && i % 6 > 1) continue
        const t = (i / count) * Math.PI * 2 * arc
        pos.push(Math.cos(t) * radius, 0, Math.sin(t) * radius); seed.push(k * 1.7); kind.push(k)
      }
    }
    ring(1500, 1.34, 1)             // fine continuous orbit
    ring(720, 1.46, 2, 1, true)     // dashed outer track
    ring(700, 1.2, 3, 0.68)         // open arc
    return { pos: new Float32Array(pos), seed: new Float32Array(seed), kind: new Float32Array(kind) }
  }

  onMount(() => {
    const gl = canvas.getContext('webgl2', { antialias: true, premultipliedAlpha: true, alpha: true })
    if (!gl) { fallback = true; return }
    const compile = (type, src) => {
      const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s)
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s))
      return s
    }
    let prog
    try {
      prog = gl.createProgram()
      gl.attachShader(prog, compile(gl.VERTEX_SHADER, VERT))
      gl.attachShader(prog, compile(gl.FRAGMENT_SHADER, FRAG))
      gl.linkProgram(prog)
      if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(prog))
    } catch { fallback = true; return }
    gl.useProgram(prog)

    const small = Math.min(innerWidth, innerHeight) < 600
    const g = geometry(small ? 1600 : 2800)
    const attr = (name, data, size) => {
      const b = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, b); gl.bufferData(gl.ARRAY_BUFFER, data, gl.STATIC_DRAW)
      const loc = gl.getAttribLocation(prog, name); gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc, size, gl.FLOAT, false, 0, 0)
    }
    attr('aPos', g.pos, 3); attr('aSeed', g.seed, 1); attr('aKind', g.kind, 1)
    const count = g.kind.length
    const u = Object.fromEntries(['uTime', 'uLevel', 'uExpand', 'uSwirl', 'uSpin', 'uAssemble', 'uDpr', 'uScale', 'uHeat', 'uGain', 'uMono']
      .map((n) => [n, gl.getUniformLocation(prog, n)]))
    gl.enable(gl.BLEND)
    gl.blendFunc(gl.ONE, gl.ONE) // light adds up, like a hologram

    const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches
    const live = { ...LOOK.boot, level: 0, assemble: reduce ? 1 : 0 }
    let t = 0, last = performance.now(), raf
    const resize = () => {
      const dpr = Math.min(devicePixelRatio || 1, 2)
      canvas.width = Math.round(canvas.clientWidth * dpr)
      canvas.height = Math.round(canvas.clientHeight * dpr)
      gl.viewport(0, 0, canvas.width, canvas.height)
      gl.uniform1f(u.uDpr, dpr)
    }
    const ro = new ResizeObserver(resize); ro.observe(canvas); resize()

    const frame = (now) => {
      const dt = Math.min(0.05, (now - last) / 1000); last = now
      const look = LOOK[mode] || LOOK.idle
      const ease = 1 - Math.exp(-dt * 3.5)
      for (const k of ['expand', 'swirl', 'spin', 'heat', 'gain', 'mono']) live[k] += (look[k] - live[k]) * ease
      const target = Math.min(1, level())
      live.level += (target - live.level) * (target > live.level ? 1 - Math.exp(-dt * 30) : 1 - Math.exp(-dt * 6))
      if (mode !== 'boot' || reduce) live.assemble += (1 - live.assemble) * (1 - Math.exp(-dt * 1.6))
      t += dt * (reduce ? 0.25 : 1)
      gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT)
      gl.uniform1f(u.uTime, t); gl.uniform1f(u.uLevel, live.level); gl.uniform1f(u.uExpand, live.expand)
      gl.uniform1f(u.uSwirl, reduce ? 0 : live.swirl); gl.uniform1f(u.uSpin, live.spin); gl.uniform1f(u.uAssemble, live.assemble)
      gl.uniform1f(u.uScale, 0.6); gl.uniform1f(u.uHeat, live.heat); gl.uniform1f(u.uGain, live.gain); gl.uniform1f(u.uMono, live.mono)
      gl.drawArrays(gl.POINTS, 0, count)
      raf = requestAnimationFrame(frame)
    }
    raf = requestAnimationFrame(frame)
    return () => { cancelAnimationFrame(raf); ro.disconnect() }
  })
</script>

<div class="sphere" data-mode={mode}>
  <canvas bind:this={canvas} class:hidden={fallback} aria-hidden="true"></canvas>
  {#if fallback}<div class="glow" aria-hidden="true"></div>{/if}
</div>

<style>
  .sphere { position: relative; width: 100%; aspect-ratio: 1; }
  canvas { width: 100%; height: 100%; display: block; mask-image: radial-gradient(circle closest-side, #000 86%, transparent 100%); }
  .hidden { display: none; }
  .glow {
    position: absolute; inset: 22%; border-radius: 50%;
    border: 2px solid var(--arc);
    box-shadow: inset 0 0 60px var(--arc-soft), 0 0 80px var(--arc-soft);
    animation: breathe 3.2s ease-in-out infinite;
  }
  [data-mode='offline'] .glow { border-color: var(--muted); animation: none; opacity: 0.5; }
  @keyframes breathe { 50% { transform: scale(1.04); opacity: 0.8; } }
</style>
