// JARVIS's voice: each sentence is fetched from the Mac (Kokoro, the same voice as Mac voice mode) as soon as it
// arrives, so the next one is ready while the current one plays. Russian, or a failed fetch, uses the browser's voice.

import { speak } from './api.js'

export class Voice {
  queue = []
  playing = null
  gen = 0
  onchange = () => {}

  init() {
    this.ctx = new AudioContext()
    this.analyser = this.ctx.createAnalyser()
    this.analyser.fftSize = 512
    this.analyser.connect(this.ctx.destination)
    this.buf = new Float32Array(this.analyser.fftSize)
  }

  get busy() { return !!this.playing || this.queue.length > 0 }

  say(text) {
    this.queue.push({ text, gen: this.gen, audio: speak(text).catch(() => null) })
    if (!this.playing) this.next()
    this.onchange()
  }

  async next() {
    const item = this.queue.shift()
    this.playing = item || null
    this.onchange()
    if (!item) return
    const wav = await item.audio
    if (item.gen !== this.gen) return
    const done = () => { if (item.gen === this.gen) this.next() }
    if (wav && this.ctx) {
      try {
        const audio = await this.ctx.decodeAudioData(wav)
        if (item.gen !== this.gen) return
        const src = this.ctx.createBufferSource()
        src.buffer = audio
        src.connect(this.analyser)
        src.onended = done
        this.src = src
        src.start()
        return
      } catch {}
    }
    this.browserSpeak(item.text, done)
  }

  browserSpeak(text, done) {
    if (!('speechSynthesis' in window)) return done()
    const u = new SpeechSynthesisUtterance(text)
    const ru = /[а-яё]/i.test(text)
    u.lang = ru ? 'ru-RU' : 'en-GB'
    const voices = speechSynthesis.getVoices().filter((v) => v.lang.replace('_', '-').startsWith(u.lang))
    u.voice = voices.find((v) => /Daniel|Google UK English Male|Yuri|Milena/.test(v.name)) || voices[0] || null
    u.onend = u.onerror = () => { this.synth = false; done() }
    this.synth = true
    speechSynthesis.speak(u)
  }

  stop() {
    this.gen++
    this.queue = []
    this.playing = null
    try { this.src?.stop() } catch {}
    this.src = null
    if (this.synth) speechSynthesis.cancel()
    this.synth = false
    this.onchange()
  }

  /** 0..1 loudness of what JARVIS is saying right now, for the sphere. */
  level() {
    if (!this.playing) return 0
    if (this.synth) return 0.28 + 0.18 * Math.sin(performance.now() / 85) // browser voice gives no samples
    this.analyser.getFloatTimeDomainData(this.buf)
    let s = 0
    for (const v of this.buf) s += v * v
    return Math.min(1, Math.sqrt(s / this.buf.length) * 5)
  }

  /** The short tone that says "I'm listening". */
  chime() {
    if (!this.ctx) return
    const t = this.ctx.currentTime
    const o = this.ctx.createOscillator()
    const g = this.ctx.createGain()
    o.frequency.setValueAtTime(880, t)
    o.frequency.exponentialRampToValueAtTime(1320, t + 0.09)
    g.gain.setValueAtTime(0.0001, t)
    g.gain.exponentialRampToValueAtTime(0.12, t + 0.015)
    g.gain.exponentialRampToValueAtTime(0.0001, t + 0.22)
    o.connect(g).connect(this.ctx.destination)
    o.start(t)
    o.stop(t + 0.25)
  }
}
