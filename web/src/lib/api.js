// Talks to `jarvis.py --serve`. The token arrives once in the URL fragment (#token=…) and is kept locally.

const KEY = 'jarvis-token'

export function initToken() {
  const m = location.hash.match(/token=([^&]+)/)
  if (m) {
    try { localStorage.setItem(KEY, decodeURIComponent(m[1])) } catch {}
    history.replaceState(null, '', location.pathname + location.search)
  }
  return token()
}

export function token() {
  try { return localStorage.getItem(KEY) || '' } catch { return '' }
}

export function saveToken(t) {
  try { localStorage.setItem(KEY, t.trim()) } catch {}
}

const auth = () => ({ Authorization: `Bearer ${token()}` })

/** Round-trip time to the Mac in ms, or throws ("token" when the token is wrong). */
export async function health() {
  const t0 = performance.now()
  const r = await fetch('/health', { headers: auth(), cache: 'no-store' })
  if (r.status === 401) throw new Error('token')
  if (!r.ok) throw new Error(`status ${r.status}`)
  return Math.round(performance.now() - t0)
}

function device() {
  const ua = navigator.userAgentData
  const platform = ua?.platform || (/(iPhone|iPad)/.test(navigator.userAgent) ? 'iOS' : /Android/.test(navigator.userAgent) ? 'Android' : 'desktop')
  const browser = ua?.brands?.find((b) => !/Not|Chromium/.test(b.brand))?.brand || 'browser'
  const now = new Date()
  const off = -now.getTimezoneOffset()
  const tz = `${off >= 0 ? '+' : '-'}${String(Math.floor(Math.abs(off) / 60)).padStart(2, '0')}:${String(Math.abs(off) % 60).padStart(2, '0')}`
  const time = now.toLocaleString('en-GB', { weekday: 'short', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
  return { platform: `Web HUD (${browser} on ${platform})`, model: '', time: `${time} ${tz}`, actions: [] }
}

/** Streams the reply: yields {type:"text"|"activity"|"activity_end"|"tool"|"done", …}. */
export async function* ask(text, signal) {
  const r = await fetch('/ask', {
    method: 'POST',
    headers: { ...auth(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ text, device: device() }),
    signal,
  })
  if (r.status === 401) throw new Error('token')
  if (!r.ok) throw new Error(`The Mac answered ${r.status}.`)
  const reader = r.body.pipeThrough(new TextDecoderStream()).getReader()
  let buf = ''
  for (;;) {
    const { value, done } = await reader.read()
    if (done) return
    buf += value
    let i
    while ((i = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, i).trim()
      buf = buf.slice(i + 1)
      if (line) yield JSON.parse(line)
    }
  }
}

export function toolResult(id, result, error) {
  return fetch('/tool_result', {
    method: 'POST',
    headers: { ...auth(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ id, result, error }),
  })
}

/** Whisper on the Mac: 16 kHz mono 16-bit PCM in, text out. */
export async function transcribe(pcm) {
  const r = await fetch('/transcribe', {
    method: 'POST',
    headers: { ...auth(), 'Content-Type': 'application/octet-stream' },
    body: pcm,
  })
  if (r.status === 401) throw new Error('token')
  return ((await r.json()).text || '').trim()
}

/** JARVIS's neural voice as WAV bytes, or null when the browser should speak it itself. */
export async function speak(text) {
  const r = await fetch('/speak', {
    method: 'POST',
    headers: { ...auth(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ text }),
  })
  return r.status === 200 ? r.arrayBuffer() : null
}
