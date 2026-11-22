<script>
  import { onMount, tick } from 'svelte'
  import Sphere from './lib/Sphere.svelte'
  import * as api from './lib/api.js'
  import { startMic, micCandidates, rms, Endpointer } from './lib/mic.js'
  import { WakeWord } from './lib/wake.js'
  import { Voice } from './lib/voice.js'

  const CANCEL = new Set(['stop', 'cancel', 'never mind', 'nevermind', 'shut up', 'стоп', 'отмена', 'хватит'])
  const NOISE = new Set(['', 'you', 'thank you', 'thanks for watching', 'bye', 'продолжение следует'])
  const voice = new Voice()
  const wake = new WakeWord()

  let paired = $state(!!api.initToken())
  let engaged = $state(false)
  let phase = $state('boot') // boot · idle · listening · transcribing · thinking · speaking
  let lines = $state([]) // {id, who: you|jarvis|system, text, at}
  let acts = $state([]) // {id, name, detail, state: run|ok|err, at}
  let link = $state({ ok: null, ms: 0 })
  let wakeState = $state('off') // off · asking (mic prompt open) · loading · armed · error
  let micError = $state('')
  let meter = $state(0)
  let now = $state(new Date())
  let typed = $state('')
  let tokenInput = $state('')
  let activityText = $state('')
  let leader = $state(null) // SVG path from the sphere to the running action
  let coreEl, actsEl

  let micName = $state('')
  let mic = null, micTried = new Set(), silentFrames = 0, heardSound = false
  let micLevel = 0, endpointer = null, conversing = false, turnDone = true, controller = null, lastWake = 0, uid = 0

  const mode = $derived(link.ok === false ? 'offline' : phase)
  const stateWord = $derived({ boot: 'Standby', idle: 'Online', listening: 'Listening', transcribing: 'Processing',
    thinking: 'Working', speaking: 'Speaking', offline: 'Offline' }[mode])
  const statusLine = $derived(
    !paired ? '' :
    mode === 'offline' ? 'Your Mac is not answering' :
    wakeState === 'asking' ? 'Allow the microphone in the browser prompt' :
    phase === 'boot' ? 'Click the sphere or press Space to bring JARVIS online' :
    phase === 'listening' ? 'Listening' :
    phase === 'transcribing' ? 'Transcribing' :
    phase === 'thinking' ? (activityText || 'Working') :
    phase === 'speaking' ? 'Speaking · Space to interrupt' :
    micError ? micError :
    wakeState === 'asking' ? 'Allow the microphone in the browser prompt' :
    wakeState === 'armed' ? 'Say “Hey Jarvis”, or press Space' : 'Press Space or click the sphere to talk')

  const level = () => phase === 'speaking' ? voice.level() : phase === 'listening' ? Math.min(1, micLevel / 2200) : 0
  const clock = (d) => d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', second: '2-digit' })

  function describe(name, detail) {
    const map = {
      Bash: ['Shell', 'Running a command'], Read: ['Read', 'Reading a file'], Write: ['Write', 'Writing a file'],
      Edit: ['Edit', 'Editing a file'], Glob: ['Find', 'Looking for files'], Grep: ['Search', 'Searching files'],
      WebSearch: ['Web search', 'Searching the web'], WebFetch: ['Web page', 'Reading a web page'],
      Task: ['Agent', 'Delegating to an agent'], Agent: ['Agent', 'Delegating to an agent'], TodoWrite: ['Plan', 'Planning'],
      mcp__phone__phone: ['Phone', 'Using your phone'],
    }
    const [label, verb] = map[name] || [name.replace(/^mcp__\w+__/, ''), 'Working']
    return { label, verb, code: name === 'Bash' || name === 'Grep' || name === 'Glob' }
  }

  // ------------------------------------------------------------------ voice in

  async function engage() {
    if (engaged) return
    engaged = true
    voice.init()
    voice.onchange = onVoiceChange
    wakeState = 'asking'
    try {
      await openMic(localStorageGet('jarvis-mic')).catch((e) => (e?.name === 'NotAllowedError' ? Promise.reject(e) : openMic())) // a remembered mic may be unplugged
      wakeState = 'loading'
      wake.load().then(() => (wakeState = 'armed')).catch(() => (wakeState = 'error'))
    } catch (e) {
      micError = e?.name === 'NotAllowedError' ? 'Microphone blocked: allow it for this site, then reload' : e?.message || 'No microphone'
      wakeState = 'off'
    }
    if (phase === 'boot') phase = 'idle'
  }

  function localStorageGet(k) { try { return localStorage.getItem(k) } catch { return null } }

  async function openMic(deviceId) {
    mic?.stop()
    mic = await startMic(onFrame, deviceId || undefined)
    micTried.add(mic.deviceId)
    micName = mic.label
    silentFrames = 0
    heardSound = false
  }

  /** Some inputs (virtual Zoom/Teams devices, a muted interface) send pure silence: move on to the next one. */
  async function nextMic(manual = false) {
    if (manual) micTried = new Set([mic?.deviceId])
    const next = (await micCandidates()).find((d) => !micTried.has(d.deviceId))
    if (!next) {
      if (!manual) micError = 'Every microphone is sending silence. Check the input in System Settings → Sound'
      return
    }
    await openMic(next.deviceId)
  }

  function onFrame(frame) {
    micLevel = rms(frame)
    if (!heardSound) {
      if (frame.some((v) => v !== 0)) {
        heardSound = true
        try { localStorage.setItem('jarvis-mic', mic.deviceId) } catch {}
      } else if (++silentFrames === 25) nextMic() // 2 s of exact zeros: this input is dead
    }
    if (wakeState === 'armed') {
      wake.process(frame).then((score) => {
        if (score > 0.5 && performance.now() - lastWake > 1500) onWake()
      })
    }
    if (phase === 'listening' && endpointer && !voice.busy) {
      const r = endpointer.push(frame, micLevel)
      if (r === 'silence') { endpointer = null; conversing = false; setIdle() }
      else if (r === 'done') finishListening()
    }
  }

  function onWake() {
    lastWake = performance.now()
    wake.reset()
    interrupt()
    voice.chime()
    listen(0.35) // ignore our own chime and the tail of "Jarvis", keep the audio for one-breath requests
  }

  function listen(skip = 0, timeout = 6) {
    if (micError) return
    conversing = true
    endpointer = new Endpointer({ skip, timeout })
    phase = 'listening'
  }

  async function finishListening() {
    const e = endpointer
    endpointer = null
    if (!e?.heard) { conversing = false; return setIdle() }
    phase = 'transcribing'
    let text
    try { text = await api.transcribe(e.pcm()) } catch (err) { return fail(err) }
    const norm = text.toLowerCase().replace(/[^\p{L}\p{N}\s]/gu, '').trim().replace(/^(hey |ok |okay )?jarvis\s*/, '')
    if (NOISE.has(norm) || CANCEL.has(norm)) { conversing = false; return setIdle() }
    ask(text)
  }

  // ------------------------------------------------------------------ the conversation

  function interrupt() {
    voice.stop()
    controller?.abort()
    controller = null
    turnDone = true
    endpointer = null
  }

  async function ask(text) {
    interrupt()
    lines.push({ id: ++uid, who: 'you', text, at: new Date() })
    phase = 'thinking'
    activityText = ''
    turnDone = false
    const ctl = new AbortController()
    controller = ctl
    let reply = null
    try {
      for await (const ev of api.ask(text, ctl.signal)) {
        if (ctl.signal.aborted) return
        if (ev.type === 'text') {
          if (!reply) { lines.push({ id: ++uid, who: 'jarvis', text: ev.text, at: new Date() }); reply = lines[lines.length - 1] }
          else reply.text += ' ' + ev.text
          voice.say(ev.text)
        } else if (ev.type === 'activity') {
          acts.unshift({ id: ev.id, name: ev.name, detail: ev.detail, state: 'run', at: new Date() })
          if (acts.length > 30) acts.pop()
          activityText = describe(ev.name).verb
        } else if (ev.type === 'activity_end') {
          const a = acts.find((x) => x.id === ev.id)
          if (a) a.state = ev.error ? 'err' : 'ok'
          activityText = ''
        } else if (ev.type === 'tool') {
          api.toolResult(ev.id, 'The web HUD has no phone actions. Use the Mac (shell, AppleScript) instead.', true)
        } else if (ev.type === 'done') break
      }
    } catch (err) {
      if (ctl.signal.aborted) return
      return fail(err)
    } finally {
      for (const a of acts) if (a.state === 'run') a.state = 'ok'
    }
    if (controller !== ctl) return
    controller = null
    turnDone = true
    afterTurn()
  }

  function onVoiceChange() {
    if (voice.busy) {
      if (phase === 'thinking') phase = 'speaking'
    } else if (phase === 'speaking') {
      if (turnDone) afterTurn()
      else phase = 'thinking'
    }
  }

  function afterTurn() {
    if (voice.busy || !turnDone) return
    if (conversing && !micError) listen(0, 6) // follow-up: keep talking without the wake word
    else setIdle()
  }

  function setIdle() {
    phase = engaged ? 'idle' : 'boot'
    activityText = ''
  }

  function fail(err) {
    if (err?.message === 'token') { paired = false; return setIdle() }
    lines.push({ id: ++uid, who: 'system', text: `Can't reach your Mac. Is \`uv run jarvis.py --serve\` running?`, at: new Date() })
    conversing = false
    checkLink()
    setIdle()
  }

  // ------------------------------------------------------------------ controls

  async function talk() {
    if (!paired) return
    if (!engaged) await engage()
    if (phase === 'listening') {
      conversing = false
      if (endpointer?.heard) finishListening()
      else { endpointer = null; setIdle() }
    } else if (phase === 'thinking' || phase === 'speaking' || phase === 'transcribing') {
      interrupt()
      voice.chime()
      listen(0.2)
    } else {
      voice.chime()
      listen(0.2)
    }
  }

  async function submit(e) {
    e.preventDefault()
    const t = typed.trim()
    if (!t || !paired) return
    typed = ''
    if (!engaged) await engage()
    conversing = false
    ask(t)
  }

  function onKey(e) {
    const typing = e.target instanceof HTMLInputElement
    if (e.code === 'Space' && !typing) { e.preventDefault(); talk() }
    else if (e.key === 'Escape') { conversing = false; interrupt(); setIdle() }
  }

  function pair(e) {
    e.preventDefault()
    if (!tokenInput.trim()) return
    api.saveToken(tokenInput)
    tokenInput = ''
    paired = true
    checkLink()
  }

  async function checkLink() {
    if (!paired) return
    try { link = { ok: true, ms: await api.health() } }
    catch (e) {
      if (e?.message === 'token') paired = false
      link = { ok: false, ms: 0 }
    }
  }

  // The leader line: from the sphere's edge to the action that is running right now.
  async function drawLeader() {
    await tick()
    const row = actsEl?.querySelector('.act.run')
    if (!row || !coreEl || innerWidth < 900) return (leader = null)
    const c = coreEl.getBoundingClientRect(), r = row.getBoundingClientRect()
    const sx = c.left + c.width * 0.5 + c.width * 0.36, sy = c.top + c.width * 0.5
    const ex = r.left - 14, ey = r.top + 14
    const mx = sx + Math.max(24, (ex - sx) * 0.35)
    leader = `M${sx},${sy} L${mx},${sy} L${ex - 18},${ey} L${ex},${ey}`
  }
  $effect(() => { acts.map((a) => a.state).join(); drawLeader() })

  onMount(() => {
    checkLink()
    const ping = setInterval(checkLink, 5000)
    const tickT = setInterval(() => (now = new Date()), 1000)
    const meterT = setInterval(() => (meter = Math.min(1, micLevel / 3000)), 66)
    addEventListener('resize', drawLeader)
    return () => { clearInterval(ping); clearInterval(tickT); clearInterval(meterT); removeEventListener('resize', drawLeader) }
  })
</script>

<svelte:window onkeydown={onKey} />

<main class="hud" data-mode={mode}>
  <header class="corner tl">
    <span class="mark">J.A.R.V.I.S.</span>
    <span class="readout"><i class="dot" class:live={mode !== 'offline' && mode !== 'boot'}></i>{stateWord}</span>
  </header>

  <div class="corner tr">
    <time class="clock" datetime={now.toISOString()}>{clock(now)}</time>
    <span class="readout">{now.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })}</span>
  </div>

  <section class="telemetry frame" aria-label="Conversation" aria-live="polite">
    {#each lines as l (l.id)}
      <p class="line {l.who}">
        <span class="at">{clock(l.at)}</span>
        {#if l.who === 'you'}
          <svg class="chev" viewBox="0 0 10 10" aria-hidden="true"><path d="M2 1 L7 5 L2 9" /></svg>
          <span class="sr-only">You said:</span>
        {/if}
        <span class="txt">{l.text}</span>
      </p>
    {:else}
      <p class="line empty">
        {#if paired}What you say and what JARVIS answers appear here.{/if}
      </p>
    {/each}
  </section>

  <div class="core" bind:this={coreEl}>
    <button class="sphere-btn" onclick={talk} aria-label={phase === 'listening' ? 'Stop listening' : 'Talk to JARVIS'} disabled={!paired}>
      <Sphere {mode} {level} />
    </button>
    {#if paired}
      <p class="status" class:warn={mode === 'offline' || micError}>{statusLine}</p>
    {:else}
      <form class="pair" onsubmit={pair}>
        <p class="status warn">Not paired with your Mac</p>
        <p class="help">Open the HUD link that <code>uv run jarvis.py --serve</code> prints, or paste its token:</p>
        <div class="field">
          <input bind:value={tokenInput} placeholder="Token" aria-label="Pairing token" autocomplete="off" spellcheck="false" />
          <button type="submit">Pair</button>
        </div>
      </form>
    {/if}
  </div>

  <section class="activity frame" class:none={!acts.length} aria-label="What JARVIS is doing" bind:this={actsEl}>
    {#each acts as a (a.id)}
      {@const d = describe(a.name)}
      <div class="act {a.state}">
        <span class="mark-icon" aria-hidden="true">
          {#if a.state === 'run'}
            <svg viewBox="0 0 16 16" class="spin"><circle cx="8" cy="8" r="6" /><path d="M8 2 A6 6 0 0 1 14 8" /></svg>
          {:else if a.state === 'ok'}
            <svg viewBox="0 0 16 16"><path d="M3.5 8.5 L6.5 11.5 L12.5 4.5" /></svg>
          {:else}
            <svg viewBox="0 0 16 16"><path d="M4.5 4.5 L11.5 11.5 M11.5 4.5 L4.5 11.5" /></svg>
          {/if}
        </span>
        <span class="name">{d.label}<span class="sr-only">, {a.state === 'run' ? 'running' : a.state === 'ok' ? 'done' : 'failed'}</span></span>
        <span class="at">{clock(a.at)}</span>
        {#if a.detail}<span class="detail" class:code={d.code}>{a.detail}</span>{/if}
      </div>
    {:else}
      <p class="act-empty">Every action JARVIS takes on your Mac shows up here.</p>
    {/each}
  </section>

  <footer class="corner bl">
    <span class="label">Mac link</span>
    <span class="readout" class:bad={link.ok === false}>
      {#if link.ok === null}Checking{:else if link.ok}Online · {link.ms} ms{:else}Offline{/if}
    </span>
  </footer>

  <form class="ask" onsubmit={submit}>
    <input bind:value={typed} placeholder={paired ? 'Type to JARVIS' : 'Pair first'} aria-label="Type a request" disabled={!paired} autocomplete="off" />
    <button type="submit" aria-label="Send" disabled={!paired || !typed.trim()}>
      <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M3 10 H16 M11 5 L16 10 L11 15" /></svg>
    </button>
  </form>

  <div class="corner br">
    {#if micName}
      <button class="mic-name" onclick={() => nextMic(true)} title="Switch microphone">Mic · {micName}</button>
    {/if}
    <span class="label">Wake word</span>
    <span class="readout" class:bad={wakeState === 'error' || micError}>
      {#if micError}Mic blocked{:else if !engaged}Standby{:else if wakeState === 'asking'}Waiting for mic{:else if wakeState === 'armed'}Armed{:else if wakeState === 'loading'}Loading{:else}Off · Space to talk{/if}
    </span>
    <span class="meter" aria-hidden="true">
      {#each Array(12) as _, i}<i class:on={engaged && meter > i / 12}></i>{/each}
    </span>
  </div>

  {#if leader}
    <svg class="leader" aria-hidden="true"><path d={leader} /></svg>
  {/if}
</main>

<style>
  .hud {
    position: relative;
    height: 100dvh;
    display: grid;
    grid-template-columns: minmax(260px, 1fr) minmax(320px, min(64vh, 42vw)) minmax(260px, 1fr);
    grid-template-rows: auto minmax(0, 1fr) auto;
    grid-template-areas:
      'tl   .    tr'
      'tele core act'
      'bl   ask  br';
    gap: 12px 44px;
    padding: 26px 34px 24px;
  }

  /* ---------- corners: only true state ---------- */
  .corner { display: flex; flex-direction: column; gap: 6px; font-family: var(--wide); font-size: 10.5px; letter-spacing: 0.16em; text-transform: uppercase; }
  .tl { grid-area: tl; }
  .tr { grid-area: tr; align-items: flex-end; }
  .bl { grid-area: bl; justify-content: flex-end; }
  .br { grid-area: br; align-items: flex-end; justify-content: flex-end; }
  .mark { font-size: 15px; letter-spacing: 0.34em; color: var(--arc); text-transform: none; }
  .readout { color: var(--muted); display: inline-flex; align-items: center; gap: 8px; }
  .readout.bad { color: var(--amber); }
  .label { color: var(--muted); }
  .clock { font-family: var(--sans); font-weight: 300; font-size: 26px; letter-spacing: 0.06em; color: var(--text); font-variant-numeric: tabular-nums; }
  .dot { width: 6px; height: 6px; border-radius: 50%; background: var(--muted); }
  .dot.live { background: var(--arc); box-shadow: 0 0 8px var(--arc); }
  .meter { display: flex; gap: 3px; margin-top: 2px; }
  .mic-name {
    all: unset; cursor: pointer; color: var(--muted); font: inherit; letter-spacing: inherit; text-transform: inherit;
    max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; margin-bottom: 4px;
  }
  .mic-name:hover, .mic-name:focus-visible { color: var(--arc); }
  .meter i { width: 5px; height: 10px; background: rgba(99, 227, 255, 0.12); transition: background 0.08s; }
  .meter i.on { background: var(--arc); }

  /* ---------- HUD frames: corner brackets, no boxes ---------- */
  .frame { position: relative; padding: 18px 20px; }
  .frame::before, .frame::after {
    content: ''; position: absolute; width: 14px; height: 14px; border: 1px solid var(--line-strong); pointer-events: none;
  }
  .frame::before { top: 0; left: 0; border-right: 0; border-bottom: 0; }
  .frame::after { bottom: 0; right: 0; border-left: 0; border-top: 0; }

  /* ---------- conversation, read as telemetry ---------- */
  .telemetry {
    grid-area: tele;
    display: flex; flex-direction: column; justify-content: flex-end; gap: 14px;
    overflow: hidden;
    mask-image: linear-gradient(transparent, #000 30%);
  }
  .line { margin: 0; display: grid; grid-template-columns: 62px auto 1fr; column-gap: 8px; align-items: baseline; animation: wipe 0.7s var(--ease) both; }
  .line .at { grid-column: 1; font-family: var(--sans); font-size: 11px; color: var(--muted); font-variant-numeric: tabular-nums; letter-spacing: 0.04em; }
  .line .txt { grid-column: 3; }
  .line.you .txt { color: var(--muted); font-size: 14px; }
  .line.jarvis .txt { grid-column: 2 / 4; color: var(--text); font-size: 17px; line-height: 1.5; font-weight: 400; max-width: 46ch; }
  .line.system .txt { grid-column: 2 / 4; color: var(--amber); font-size: 14px; }
  .line.empty { display: block; color: var(--muted); font-size: 14px; animation: none; }
  .chev { grid-column: 2; width: 9px; height: 9px; fill: none; stroke: var(--arc); stroke-width: 1.6; }
  @keyframes wipe { from { clip-path: inset(0 100% 0 0); opacity: 0.4; } to { clip-path: inset(0 0 0 0); opacity: 1; } }

  /* ---------- the sphere ---------- */
  .core { grid-area: core; display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 0; }
  .sphere-btn { all: unset; cursor: pointer; width: 100%; max-height: 100%; aspect-ratio: 1; border-radius: 50%; }
  .sphere-btn:disabled { cursor: default; }
  .sphere-btn:focus-visible { outline: 1px dashed var(--line-strong); outline-offset: -8%; }
  .status {
    margin: 4px 0 0; min-height: 1.5em; text-align: center;
    font-family: var(--wide); font-size: 11px; letter-spacing: 0.2em; text-transform: uppercase; color: var(--arc);
  }
  .status.warn { color: var(--amber); }
  .pair { display: flex; flex-direction: column; align-items: center; gap: 10px; max-width: 420px; text-align: center; }
  .help { margin: 0; color: var(--muted); font-size: 14px; line-height: 1.5; }
  .help code, .detail.code { font-family: var(--mono); font-size: 12.5px; color: var(--arc-hot); }
  .field { display: flex; width: 100%; gap: 8px; }
  .field input { flex: 1; }

  /* ---------- actions ---------- */
  .activity { grid-area: act; display: flex; flex-direction: column; gap: 4px; overflow: hidden; mask-image: linear-gradient(#000 70%, transparent); }
  .act {
    display: grid; grid-template-columns: 16px 1fr auto; column-gap: 10px; row-gap: 3px; align-items: center;
    padding: 8px 0; border-bottom: 1px solid var(--line);
    animation: arrive 0.6s var(--ease) both;
  }
  .act .name { font-family: var(--wide); font-size: 10.5px; letter-spacing: 0.16em; text-transform: uppercase; color: var(--text); }
  .act .at { font-size: 11px; color: var(--muted); font-variant-numeric: tabular-nums; }
  .act .detail { grid-column: 2 / 4; color: var(--muted); font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .act.run .name { color: var(--arc); }
  .act.err .name { color: var(--amber); }
  .mark-icon svg { width: 16px; height: 16px; fill: none; stroke-width: 1.6; display: block; }
  .act.run svg { stroke: var(--arc); }
  .act.run svg circle { stroke: var(--line-strong); }
  .act.ok svg { stroke: var(--ok); }
  .act.err svg { stroke: var(--amber); }
  .spin { animation: spin 0.9s linear infinite; }
  .act-empty { margin: 0; color: var(--muted); font-size: 14px; }
  @keyframes spin { to { transform: rotate(360deg); } }
  @keyframes arrive { from { transform: translateX(-18px); opacity: 0; filter: blur(4px); } }

  .leader { position: fixed; inset: 0; width: 100%; height: 100%; pointer-events: none; overflow: visible; }
  .leader path { fill: none; stroke: var(--arc); stroke-width: 1; opacity: 0.7; stroke-dasharray: 900; animation: draw 0.8s var(--ease) both; }
  @keyframes draw { from { stroke-dashoffset: 900; } to { stroke-dashoffset: 0; } }

  /* ---------- typing ---------- */
  .ask { grid-area: ask; display: flex; align-items: center; gap: 0; border-bottom: 1px solid var(--line-strong); align-self: end; }
  input {
    background: transparent; border: 0; padding: 10px 2px; font-size: 15px; color: var(--text); min-width: 0;
  }
  .ask input { flex: 1; }
  .field input { border-bottom: 1px solid var(--line-strong); }
  input::placeholder { color: var(--muted); }
  input:focus { outline: none; }
  .ask:focus-within { border-bottom-color: var(--arc); }
  button[type='submit'] {
    background: transparent; border: 0; padding: 8px; cursor: pointer; color: var(--arc);
    font-family: var(--wide); font-size: 10.5px; letter-spacing: 0.16em; text-transform: uppercase;
  }
  button[type='submit']:disabled { color: rgba(134, 185, 201, 0.35); cursor: default; }
  button[type='submit']:not(:disabled):hover { color: var(--arc-hot); }
  .ask svg { width: 18px; height: 18px; fill: none; stroke: currentColor; stroke-width: 1.6; display: block; }

  [data-mode='offline'] .mark { color: var(--muted); }

  /* ---------- phones and narrow windows ---------- */
  @media (max-width: 900px) {
    .hud {
      grid-template-columns: 1fr 1fr;
      grid-template-rows: auto auto minmax(0, 1fr) auto auto;
      grid-template-areas:
        'tl   tr'
        'core core'
        'tele tele'
        'act  act'
        'ask  ask';
      gap: 10px 16px;
      padding: max(14px, env(safe-area-inset-top)) 16px max(14px, env(safe-area-inset-bottom));
    }
    .bl, .br { display: none; }
    .core { width: min(80vw, 42vh); justify-self: center; }
    .clock { font-size: 20px; }
    .telemetry { padding: 12px 14px; }
    .line { grid-template-columns: auto 1fr; }
    .line .at { display: none; }
    .line .txt, .line.jarvis .txt, .line.system .txt { grid-column: 2; }
    .line.jarvis .txt, .line.system .txt { grid-column: 1 / 3; font-size: 16px; }
    .activity { max-height: 96px; padding: 4px 14px; }
    .activity .act:nth-child(n + 3) { display: none; }
    .activity.none { display: none; }
  }
</style>
