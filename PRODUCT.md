# Product

<!-- impeccable:product-schema 1 -->

## Platform

web (the HUD); native iOS and Android apps live in `ios/` and `android/`.

## Stack

Framework requested by the user. Delegated choice: Svelte 5 + Vite, built to static files in `web/dist/` and served by `jarvis.py --serve` (Python stdlib HTTP server). Chosen because the HUD is animation-heavy with little routing, and the original Jarvis project's frontend was Svelte.

## Users

One person: the owner, a developer, talking to their own assistant. Primary scene (inferred, not confirmed): the HUD open full-screen in Chrome on the Mac or a second monitor while they work, used hands-free. Secondary (inferred): the same HUD in a phone browser.

## Product Purpose

JARVIS is a personal voice assistant whose brain is Claude Code running on the owner's Mac. It answers questions, controls the Mac (shell, AppleScript, apps, files, web, screen) and the owner's phones, and hands long jobs to background Claude Code or Codex agents. The HUD is its face: where you speak to it, see what it heard, watch it work, and read what it said.

## Positioning

Not a chatbot window: an agent that actually operates the owner's machines, with a live view of what it is doing (each tool call, each phone action) as it happens.

## Operating Context

- Backend: `uv run jarvis.py --serve` on the Mac (port 8765, bearer token in `~/.jarvis/token`).
- Endpoints: `POST /ask` (NDJSON stream of `text`, `tool`, `ping`, `done` events), `POST /tool_result`, `POST /transcribe` (16 kHz PCM → Whisper text), `GET /health`.
- Speech: replies stream sentence by sentence and are spoken aloud; the user can interrupt.
- Activation (confirmed): both the "Hey Jarvis" wake word and click / Space push-to-talk.
- Languages: English and Russian are both used.

## Capabilities and Constraints

- Microphone access in browsers requires a secure context: `localhost` on the Mac, HTTPS (e.g. Tailscale) on phones.
- Wake word: openWakeWord "hey jarvis" model, the same one the Mac voice mode uses.
- The assistant asks for spoken confirmation before destructive or outward-facing actions.

## Brand Commitments

- Name: JARVIS. Persona: Tony Stark's AI from the Iron Man films, calm, capable, dry British wit, addresses the user as "sir".
- Binding visual brief from the user: the HUD should feel like JARVIS's interface in the Iron Man movies.

## Evidence on Hand

No screenshots, logos or brand assets beyond `android/app/src/main/res/drawable/ic_jarvis.xml` and `ios/Jarvis/Assets.xcassets/AppIcon.appiconset/icon-1024.png` (cyan ring with glowing core). Do not reproduce film imagery, logos or Marvel assets.

## Product Principles

1. Show the work: what was heard, what it is doing, and what it found should always be visible.
2. Voice first, never voice only: everything spoken is also on screen, and typing always works.
3. Interruptible: the user can cut JARVIS off at any moment.
4. Real state only: every readout reflects something true; no fake telemetry.
