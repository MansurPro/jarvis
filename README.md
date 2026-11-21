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

## The HUD (Iron Man-style web interface)

```sh
cd web && npm install && npm run build   # once, and after changing the HUD
cd .. && uv run jarvis.py --serve        # prints a "HUD" link with your token
```

Open the printed `http://localhost:8765/#token=…` link in Chrome and click the sphere (allow the
microphone when Chrome asks).

* **The voice sphere** in the centre is JARVIS: a WebGL cloud of light that breathes when idle,
  ripples with your voice, swirls while it works and pulses as it speaks.
* **Left:** the conversation, as telemetry. **Right:** every action JARVIS takes (shell commands,
  file reads, web searches, phone actions) as it happens, with a line from the sphere to the one running now.
* **Corners:** only real state, such as the clock, the link to your Mac with latency, the wake word and your mic level.
* **Talk:** say "Hey Jarvis" (the same wake-word model as the Mac app, running in the browser), or
  press Space or click the sphere. Speak while it's talking to interrupt, and Esc stops it. It keeps listening
  for a few seconds after each answer, and you can always type.
* **Voice:** the same British Kokoro voice as the Mac app; Russian answers use the browser's voice.

Developing the HUD: run `npm run dev` in `web/` (with the server running) for hot reload on :5173.
On a phone browser the microphone needs HTTPS, for example `tailscale serve` in front of port 8765.

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

## Testing locally

Everything below runs on one Mac; no phone or second person needed.

**1. Unit checks** (sentence splitting, markdown cleanup, phone-tool argument parsing):

```sh
uv run python test_jarvis.py          # prints "voice ok" and "phone ok"
```

**2. The brain, without audio.** Type instead of talk; `--mute` prints replies instead of speaking:

```sh
uv run jarvis.py --text --mute        # try: "what time is it?", "how many files are in Downloads?"
uv run jarvis.py --text --agent codex # same, with Codex as the brain
```

**3. Voice on the Mac.** `say` through the speakers is a stand-in for you talking:

```sh
uv run jarvis.py --mute --followup 0 &
say -v Samantha "Hey Jarvis."; sleep 0.4; say -v Samantha "What is two plus two?"
```

If it never reacts, run `uv run python -c "import sounddevice as sd, numpy as np; a=sd.rec(32000,samplerate=16000,channels=1,dtype='int16'); sd.wait(); print(np.abs(a).max())"`.
`0` means macOS is blocking the microphone: allow your terminal in System Settings → Privacy &
Security → Microphone and restart the terminal (emulators started from it inherit the same block).

**4. Phone backend, without a phone.** Start the server, then play the phone's side with curl:

```sh
uv run jarvis.py --serve &
TOK=$(cat ~/.jarvis/token)
curl -s -H "Authorization: Bearer $TOK" localhost:8765/health                # {"ok": true}
curl -sN -H "Authorization: Bearer $TOK" localhost:8765/ask \
  -d '{"text": "how is my battery?", "device": {"platform": "test", "actions": ["battery"]}}'
```

The stream stops at a `{"type": "tool", "id": "…", "action": "battery"}` line and waits for the
"phone" to answer. Reply from a second terminal and the spoken answer streams back:

```sh
curl -s -H "Authorization: Bearer $TOK" localhost:8765/tool_result -d '{"id": "<id>", "result": "Battery 23%"}'
```

Speech-to-text for phones: `curl -s -H "Authorization: Bearer $TOK" --data-binary @audio.raw localhost:8765/transcribe`
with raw 16 kHz mono 16-bit audio (`ffmpeg -i in.wav -ar 16000 -ac 1 -f s16le audio.raw`).

**5. Android emulator.** One-time setup: JDK 17 and the Android SDK
(`brew install openjdk@17 --cask android-commandlinetools`, then
`sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" "emulator" "system-images;android-35;google_apis_playstore;arm64-v8a"`)
and an emulator (Android Studio's Device Manager, or `avdmanager create avd -n jarvis -k "system-images;android-35;google_apis_playstore;arm64-v8a"`).

```sh
export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home
emulator -avd jarvis -allow-host-audio &                   # window + the Mac's microphone
(cd android && ./gradlew assembleDebug) && adb install -r android/app/build/outputs/apk/debug/app-debug.apk
for p in RECORD_AUDIO READ_CONTACTS CALL_PHONE SEND_SMS READ_SMS READ_CALENDAR WRITE_CALENDAR ACCESS_FINE_LOCATION; do
  adb shell pm grant ai.jarvis.app android.permission.$p; done
adb shell cmd role add-role-holder android.app.role.ASSISTANT ai.jarvis.app 0                 # default assistant
adb shell settings put secure enabled_accessibility_services ai.jarvis.app/ai.jarvis.app.JarvisAccessibility
adb shell am start -a android.intent.action.VIEW \
  -d "'jarvis://pair?url=http%3A%2F%2F10.0.2.2%3A8765&token=$(cat ~/.jarvis/token)'"     # 10.0.2.2 = the Mac
adb emu geo fix 69.2797 41.3111                            # fake GPS position (longitude latitude)
```

Then type in the app, or send a request from the terminal and watch the server's output:

```sh
adb shell am start -n ai.jarvis.app/.MainActivity -e ask "'Open Settings and tell me what is in Network and internet'"
adb shell input keyevent KEYCODE_ASSIST                    # assist gesture: opens Jarvis listening
```

Emulators have no Google account, so Android's own speech recognizers fail there; Jarvis notices
and switches to Whisper on the Mac (status line: "Mac speech recognition"). Its log line
`adb logcat -s Jarvis` shows the microphone level (`peak=0` means the Mac blocked the mic, see 3).

**6. iOS simulator.** Needs Xcode with the Simulator app (Xcode → Open Developer Tool → Simulator).

```sh
cd ios && xcodebuild -project Jarvis.xcodeproj -scheme Jarvis -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath build CODE_SIGNING_ALLOWED=NO build
xcrun simctl boot "iPhone 15 Pro"; open -a Simulator
xcrun simctl install booted build/Build/Products/Debug-iphonesimulator/Jarvis.app
for s in contacts calendar reminders location microphone; do xcrun simctl privacy booted grant $s ai.jarvis.app; done
xcrun simctl location booted set 41.3111,69.2797
xcrun simctl launch booted ai.jarvis.app -url http://127.0.0.1:8765 -token "$(cat ~/.jarvis/token)" \
  -ask "Find Kate Bell in my contacts"                      # simulator ships sample contacts
```

`-listen YES` instead of `-ask` starts a voice conversation. Pairing by QR link also works:
`xcrun simctl openurl booted "jarvis://pair?url=http%3A%2F%2F127.0.0.1%3A8765&token=$(cat ~/.jarvis/token)"`
(tap Open). Siri ("ask Jarvis") only works on a real iPhone.

`legacy/` (git-ignored) holds the original 2023 Windows Python version.
