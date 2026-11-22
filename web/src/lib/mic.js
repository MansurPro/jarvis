// Microphone → 80 ms frames of 16 kHz 16-bit PCM (what both the wake-word model and Whisper expect).

const FRAME = 1280
const WORKLET = `
class Pcm extends AudioWorkletProcessor {
  constructor() { super(); this.b = new Int16Array(${FRAME}); this.n = 0 }
  process(inputs) {
    const ch = inputs[0][0]
    if (ch) for (let k = 0; k < ch.length; k++) {
      const v = ch[k] * 32768
      this.b[this.n++] = v > 32767 ? 32767 : v < -32768 ? -32768 : v
      if (this.n === ${FRAME}) { this.port.postMessage(this.b, [this.b.buffer]); this.b = new Int16Array(${FRAME}); this.n = 0 }
    }
    return true
  }
}
registerProcessor('pcm', Pcm)`

/** Inputs to try, best first: the system default, then real devices, then virtual ones (Zoom, Teams, …). */
export async function micCandidates() {
  const inputs = (await navigator.mediaDevices.enumerateDevices()).filter((d) => d.kind === 'audioinput')
  const virtual = (d) => /virtual|zoom|teams|blackhole|loopback|soundflower|d_d/i.test(d.label)
  return [...inputs].sort((a, b) => (b.deviceId === 'default') - (a.deviceId === 'default') || virtual(a) - virtual(b))
}

export async function startMic(onFrame, deviceId) {
  if (!navigator.mediaDevices?.getUserMedia) {
    throw new Error(window.isSecureContext ? 'This browser has no microphone access.' : 'The microphone needs HTTPS (or localhost).')
  }
  const stream = await navigator.mediaDevices.getUserMedia({
    audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true,
      ...(deviceId ? { deviceId: { exact: deviceId } } : {}) },
  })
  const track = stream.getAudioTracks()[0]
  const ctx = new AudioContext({ sampleRate: 16000 })
  await ctx.audioWorklet.addModule(URL.createObjectURL(new Blob([WORKLET], { type: 'text/javascript' })))
  const node = new AudioWorkletNode(ctx, 'pcm')
  node.port.onmessage = (e) => onFrame(e.data)
  const mute = ctx.createGain()
  mute.gain.value = 0
  ctx.createMediaStreamSource(stream).connect(node).connect(mute).connect(ctx.destination) // keeps the worklet pulled
  return {
    label: (track?.label || 'Microphone').replace(/^Default - /, '').replace(/ \(Built-in\)$/, ''),
    deviceId: track?.getSettings().deviceId,
    stop() {
      stream.getTracks().forEach((t) => t.stop())
      ctx.close()
    },
  }
}

export function rms(frame) {
  let s = 0
  for (let i = 0; i < frame.length; i++) s += frame[i] * frame[i]
  return Math.sqrt(s / frame.length)
}

/** Pause-based end of speech, same rules as the Mac and phone apps: adaptive noise floor, clicks ignored. */
export class Endpointer {
  constructor({ timeout = 6, skip = 0 } = {}) {
    this.frames = []
    this.floor = 300
    this.peak = 0
    this.voiced = 0
    this.started = performance.now() + skip * 1000
    this.last = 0
    this.timeout = timeout * 1000
  }

  /** Returns 'wait', 'silence' (nothing said), or 'done'. */
  push(frame, level) {
    const now = performance.now()
    this.frames.push(frame)
    if (now < this.started) return 'wait' // our own chime / tail of the wake word: keep the audio, don't count it
    if (level > Math.max(250, this.floor * 3, this.peak * 0.15)) {
      this.voiced++
      this.last = now
      this.peak = Math.max(this.peak, level)
    } else if (!this.voiced) {
      this.floor = 0.9 * this.floor + 0.1 * Math.min(level, this.floor * 2)
      if (this.frames.length > 3) this.frames.splice(0, this.frames.length - 3) // 240 ms pre-roll
    }
    if (!this.voiced && now - this.started > this.timeout) return 'silence'
    if (this.voiced > 0 && this.voiced < 4 && now - this.last > 800) { this.voiced = 0; this.peak = 0 } // a click
    else if (this.voiced >= 4 && now - this.last > 900) return 'done'
    if (now - this.started > 25000) return this.voiced >= 4 ? 'done' : 'silence'
    return 'wait'
  }

  get heard() { return this.voiced >= 4 }

  pcm() {
    const out = new Int16Array(this.frames.length * FRAME)
    this.frames.forEach((f, i) => out.set(f, i * FRAME))
    return out
  }
}
