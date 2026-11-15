# Jarvis

A real-time voice assistant for macOS, iPhone and Android. Its brain is **Claude Code** (or **Codex**), so it can
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

## Phones (iOS and Android)

Jarvis also runs on your phones as a Google-Assistant-style app. Claude Code on your Mac stays the
brain; the phone does the listening, the speaking, and the phone-side actions.

```
phone app ──"what's on my calendar?"──► jarvis.py --serve (Mac) ──► Claude Code
          ◄──── phone tool call ─────── (MCP)  ◄── "calendar_events {days: 1}"
          ──── result ─────────────────►       ──► spoken answer streamed back
```

**1. Start the backend on the Mac**

```sh
uv run jarvis.py --serve
```

It prints a QR code. Scan it with the phone's Camera app to pair; the phone needs to reach the Mac
(same Wi-Fi). Away from home, install [Tailscale](https://tailscale.com) on both and run
`uv run jarvis.py --serve --public-url http://<mac-tailscale-ip>:8765`.

**2a. Android** (`android/`, Kotlin, no third-party libraries)

```sh
cd android && ./gradlew assembleDebug        # needs JDK 17 + Android SDK
adb install app/build/outputs/apk/debug/app-debug.apk
```

Then in the app's ⚙: make Jarvis the **default digital assistant** (the assist gesture, a long press
on power or a swipe up from a corner, opens Jarvis already listening), and optionally enable **screen
control** (accessibility) and **notification access**.

Android can: text (sends directly), call, search contacts, read and add calendar events, set
alarms and timers, get location, open apps, open links, navigate, control media and volume, toggle
the flashlight, check battery, use the clipboard, read notifications and texts, and **operate other
apps** by reading the screen and tapping, typing and scrolling ("open Settings and turn on hotspot").

**2b. iPhone** (`ios/`, SwiftUI)

Open `ios/Jarvis.xcodeproj` in Xcode, pick your Apple ID team under Signing & Capabilities (a free
account works; the app then needs re-installing every 7 days), plug in the iPhone and press Run.
`ios/project.yml` regenerates the project with `xcodegen` if you change it.

* "Hey Siri, ask Jarvis": Siri asks what you want, Claude answers, Siri reads it out.
* Action button or Back Tap: set it to the "Talk to Jarvis" shortcut for a hands-free conversation.

iOS can: search contacts, read and add calendar events, add reminders, set timers (as notifications),
get location, open apps and links, navigate, play/pause/skip Apple Music, toggle the flashlight,
check battery, use the clipboard, and **run any of your Shortcuts** (the way to reach HomeKit,
Focus modes, settings and anything else Apple lets Shortcuts do). Calls and texts open filled in;
iOS requires you to tap Call or Send yourself, and no app can replace Siri or read other apps' screens.

On both platforms, everything the Mac can do still works from the phone: "is my Mac's build done?",
"play music on my Mac", "summarize the PDF in my Downloads".

**Security:** the phone talks to the Mac over plain HTTP with a random token (`~/.jarvis/token`;
delete it to revoke all phones). Keep it on your own network or Tailscale; don't port-forward it.

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
