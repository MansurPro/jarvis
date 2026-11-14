# Jarvis

A real-time voice assistant for macOS. Its brain is **Claude Code** (or **Codex**), so it can
do anything you could do in a terminal: control apps, music, volume, windows, files, Reminders,
Calendar, Mail; search the web; write and run code; and kick off long background coding jobs.

```
"Hey Jarvis" ──► Whisper (MLX, on-device) ──► Claude Code session ──► spoken reply (Kokoro neural TTS)
 openWakeWord        ~0.7 s                    one persistent process     sentence by sentence
```

* **Wake word**: openWakeWord "hey jarvis", fully offline.
* **Speech-to-text**: Whisper large-v3-turbo on Apple Silicon (MLX), English, Russian and ~100 more languages.
* **Brain**: one long-lived `claude -p` stream-json session. It needs no per-turn startup, keeps
  memory across the conversation, and streams its answer, so Jarvis starts speaking right away.
  Uses your existing Claude Code login (no API key).
* **Sees your screen**: "what's this error?", "summarise what I'm reading" (needs Screen Recording permission for your terminal).
* **Voice**: Kokoro-82M neural TTS, on-device, with a British male butler voice (`bm_george`). Try
  `--voice bm_lewis`, `bm_daniel` or `bm_fable`; `--voice Daniel` uses plain macOS `say`. Russian replies use macOS `Milena`.
* **Barge-in**: say "hey Jarvis" while it's talking or working to cut it off.
* **Follow-up**: after a reply it keeps listening for 6 s, so you can keep talking without the wake word.
* **Background jobs**: "hand this to a background Claude Code agent: …" starts a separate headless
  agent that keeps running even if you quit Jarvis, and announces itself aloud when it's done. You can
  start one yourself: `uv run jarvis.py --job <name> <dir> "<task>" [--agent codex]`. Logs are in `~/.jarvis/jobs/`.

## Run

Requires macOS on Apple Silicon, [uv](https://docs.astral.sh/uv/), and `claude` (or `codex`) logged in.

```sh
uv run jarvis.py                 # voice mode (first run downloads ~1.6 GB Whisper + 350 MB voice models)
uv run jarvis.py --text          # type instead of talk
uv run jarvis.py --agent codex   # Codex as the brain (slower, ~10-20 s per turn)
uv run jarvis.py --model opus    # smarter, slower; default is sonnet
uv run jarvis.py --lang ru       # pin recognition language
```

Grant your terminal microphone access when macOS asks. The first time Jarvis scripts an app,
macOS also asks for Automation permission.

Tuning knobs (`--help` for all): `--wake-threshold` (false wakes ↔ missed wakes), `--mic-threshold`
and `--silence` (end-of-speech detection), `--followup 0` (disable the follow-up window),
`--mic N` (`uv run python -m sounddevice` lists devices).

The current conversation is a normal Claude Code session. Jarvis prints its id, and
`claude --resume <id>` shows everything it did.

## Safety

The agent runs with `bypassPermissions` in your home folder: it can do anything you can.
Its instructions make it ask for a spoken "yes" before deleting, sending, purchasing, pushing or
shutting down, but speech recognition can mishear. Use headphones (so it doesn't hear itself),
and don't leave it listening in a room with a TV on.

## Tests

```sh
uv run python test_jarvis.py
```

`legacy/` (git-ignored) holds the original 2023 Windows Python version.
