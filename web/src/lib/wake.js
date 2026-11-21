// "Hey Jarvis" in the browser: openWakeWord's streaming pipeline ported to onnxruntime-web,
// using the same three models the Mac voice mode uses (served by jarvis.py at /models/).
//   80 ms of audio → mel spectrogram (/10 + 2) → last 76 mel frames → 96-d embedding → last 16 embeddings → score

import * as ort from 'onnxruntime-web/wasm'

ort.env.wasm.wasmPaths = '/ort/'
ort.env.wasm.numThreads = 1 // threads need cross-origin isolation; one thread is plenty for 80 ms frames

const FRAME = 1280
const MEL_CONTEXT = 160 * 3

export class WakeWord {
  async load(base = '/models/') {
    const open = (f) => ort.InferenceSession.create(base + f, { executionProviders: ['wasm'] })
    ;[this.mel, this.embed, this.model] = await Promise.all([
      open('melspectrogram.onnx'), open('embedding_model.onnx'), open('hey_jarvis_v0.1.onnx'),
    ])
    this.raw = new Float32Array(FRAME + MEL_CONTEXT)
    this.melRows = Array.from({ length: 76 }, () => new Float32Array(32).fill(1))
    // Start the embedding history from silence, as openWakeWord does.
    const silent = await this.melFrames(new Float32Array(FRAME + MEL_CONTEXT))
    const rows = Array.from({ length: 76 }, (_, i) => silent[i % silent.length])
    const e = await this.embedding(rows)
    this.feats = Array.from({ length: 16 }, () => e)
    this.warmup = 5 // openWakeWord ignores its first few predictions
    this.busy = Promise.resolve()
  }

  async melFrames(samples) {
    const out = await this.mel.run({ input: new ort.Tensor('float32', samples, [1, samples.length]) })
    const data = out[this.mel.outputNames[0]].data
    const rows = []
    for (let i = 0; i + 32 <= data.length; i += 32) rows.push(data.slice(i, i + 32).map((v) => v / 10 + 2))
    return rows
  }

  async embedding(rows) {
    const flat = new Float32Array(76 * 32)
    rows.forEach((r, i) => flat.set(r, i * 32))
    const out = await this.embed.run({ input_1: new ort.Tensor('float32', flat, [1, 76, 32, 1]) })
    return Float32Array.from(out[this.embed.outputNames[0]].data)
  }

  /** Feed one 1280-sample Int16 frame; resolves to the wake score (0..1). Frames are processed in order. */
  process(frame) {
    this.busy = this.busy.then(() => this.step(frame)).catch(() => 0)
    return this.busy
  }

  async step(frame) {
    this.raw.copyWithin(0, FRAME) // keep 3 hops of context, append the new frame
    for (let i = 0; i < FRAME; i++) this.raw[MEL_CONTEXT + i] = frame[i]
    this.melRows.push(...(await this.melFrames(this.raw)))
    this.melRows.splice(0, this.melRows.length - 76)
    this.feats.push(await this.embedding(this.melRows))
    this.feats.shift()
    const flat = new Float32Array(16 * 96)
    this.feats.forEach((f, i) => flat.set(f, i * 96))
    const out = await this.model.run({ [this.model.inputNames[0]]: new ort.Tensor('float32', flat, [1, 16, 96]) })
    const score = out[this.model.outputNames[0]].data[0]
    if (this.warmup > 0) { this.warmup--; return 0 }
    return score
  }

  reset() { this.warmup = 5 }
}
