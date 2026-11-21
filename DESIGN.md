---
name: J.A.R.V.I.S.
description: A voice HUD for Claude Code on your Mac, built as a Stark-lab hologram.
colors:
  void: "#02070c"
  arc: "#63e3ff"
  arc-hot: "#e8fbff"
  line: "rgba(99, 227, 255, 0.16)"
  line-strong: "rgba(99, 227, 255, 0.38)"
  text: "#d6f4fc"
  muted: "#86b9c9"
  amber: "#ffb648"
  ok: "#7df2c6"
typography:
  display:
    fontFamily: "Saira, system-ui, sans-serif"
    fontSize: "26px"
    fontWeight: 300
    letterSpacing: "0.06em"
    fontFeature: "tnum"
  headline:
    fontFamily: "Michroma, Eurostile, Saira, sans-serif"
    fontSize: "15px"
    fontWeight: 400
    letterSpacing: "0.34em"
  body:
    fontFamily: "Saira, system-ui, sans-serif"
    fontSize: "17px"
    fontWeight: 400
    lineHeight: 1.5
  body-sm:
    fontFamily: "Saira, system-ui, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.5
  label:
    fontFamily: "Michroma, Eurostile, Saira, sans-serif"
    fontSize: "10.5px"
    fontWeight: 400
    letterSpacing: "0.16em"
  status:
    fontFamily: "Michroma, Eurostile, Saira, sans-serif"
    fontSize: "11px"
    fontWeight: 400
    letterSpacing: "0.2em"
  timestamp:
    fontFamily: "Saira, system-ui, sans-serif"
    fontSize: "11px"
    fontWeight: 400
    letterSpacing: "0.04em"
    fontFeature: "tnum"
  code:
    fontFamily: "JetBrains Mono, ui-monospace, monospace"
    fontSize: "12.5px"
    fontWeight: 400
rounded:
  none: "0"
  full: "50%"
spacing:
  hair: "3px"
  xs: "6px"
  sm: "8px"
  md: "14px"
  lg: "18px"
  gutter: "44px"
components:
  readout:
    textColor: "{colors.muted}"
    typography: "{typography.label}"
  readout-bad:
    textColor: "{colors.amber}"
    typography: "{typography.label}"
  status-line:
    textColor: "{colors.arc}"
    typography: "{typography.status}"
  status-line-warn:
    textColor: "{colors.amber}"
    typography: "{typography.status}"
  frame:
    backgroundColor: "transparent"
    rounded: "{rounded.none}"
    padding: "18px 20px"
  action-row:
    backgroundColor: "transparent"
    textColor: "{colors.text}"
    typography: "{typography.label}"
    padding: "8px 0"
  action-row-running:
    textColor: "{colors.arc}"
  action-row-failed:
    textColor: "{colors.amber}"
  ask-field:
    backgroundColor: "transparent"
    textColor: "{colors.text}"
    rounded: "{rounded.none}"
    padding: "10px 2px"
  button-text:
    backgroundColor: "transparent"
    textColor: "{colors.arc}"
    typography: "{typography.label}"
    padding: "8px"
  button-text-hover:
    textColor: "{colors.arc-hot}"
---

# Design System: J.A.R.V.I.S.

## Overview

**Creative North Star: "The Stark-Lab Hologram"**

The HUD is a projection in a dark room. There is one light source, a cyan point-cloud sphere at the centre, and everything else is the faint instrumentation around it: the conversation reads as telemetry down the left, every action Claude takes lands as a callout on the right, and the four corners carry readouts. Nothing sits on a surface; text and hairlines float on the void the way light floats in air. Depth comes from additive light and from masks that fade content into the dark, never from cards or shadows.

The system is dense but quiet. Labels are tiny, widely tracked, uppercase Michroma; speech is Saira at reading size; real commands are the only mono text. Colour is a single cyan that runs white-hot when the assistant is active, with amber reserved for attention. Motion comes from state: one boot moment where scattered light assembles into the sphere and the rings draw in, then the sphere breathes, ripples with your voice, swirls while working, and pulses with JARVIS's own voice.

Every readout is true. The world's look borrows from film HUDs, but its telemetry never decorates: a corner shows the clock, the Mac link and its latency, the wake-word state and the live mic level, and nothing invented.

**Key Characteristics:**
- Near-black void with a faint 48px holographic grid, masked to a radial bloom behind the sphere.
- One cyan light; white-hot means active; amber means attention; mint means done.
- Open corner brackets instead of boxes.
- Michroma labels, Saira speech, JetBrains Mono only for real commands.
- Motion is driven by state, never by decoration.

## Colors

A single-light palette: one cyan hue on a cold void, with one warm counter-colour reserved for attention.

### Primary
- **Arc Cyan** (arc): the light of the world. The sphere's points and rings, the wordmark, the live status line, the running action, the leader line, the live dot, focus outlines, the lit mic-meter segments. Used for what is alive right now.
- **White-Hot Arc** (arc-hot): cyan at full heat. The sphere shifts toward it with listening and speaking intensity; in the DOM it marks real commands (mono text) and the hover state of text buttons.

### Secondary
- **Attention Amber** (amber): the only warm colour. Offline, mic blocked, unpaired, failed action, the system's error line in the conversation. Never decorative.

### Tertiary
- **Done Mint** (ok): the check stroke on a finished action. Confined to that success icon.

### Neutral
- **Lab Void** (void): page background and browser theme colour. Everything sits directly on it.
- **Hologram Text** (text): JARVIS's replies, the clock, action names, typed input.
- **Instrument Slate** (muted): your own lines, readouts at rest, action details, help text. This is the dimmest colour readable text may use.
- **Hairline** (line): dividers between action rows.
- **Bracket Line** (line-strong): frame corner brackets, input underlines, the dial track of the running-action spinner.

### Named Rules
**The One Light Rule.** Cyan is the only light. A second hue appears only as a state (amber for attention, mint for done), never as decoration or branding.

**The Heat Means Activity Rule.** Brightness tracks activity: the sphere runs from cyan toward white-hot as it listens or speaks, and desaturates to slate when the Mac is offline. Don't brighten anything that isn't doing something.

## Typography

**Display Font:** Michroma (with Eurostile, Saira, sans-serif)
**Body Font:** Saira (with system-ui, sans-serif)
**Label/Mono Font:** JetBrains Mono (with ui-monospace, monospace)

**Character:** Michroma's wide, squared letters are the instrument panel; Saira's narrow, technical humanist shapes carry the voice. Mono appears only where text is literally code a machine runs.

### Hierarchy
- **Display** (Saira 300, 26px, 0.06em, tabular figures; 20px under 900px): the clock in the top-right corner.
- **Headline** (Michroma 400, 15px, 0.34em): the J.A.R.V.I.S. wordmark, set in its own case.
- **Body** (Saira 400, 17px, 1.5; max 46ch): JARVIS's spoken replies in the telemetry column. 16px on narrow screens.
- **Body Small** (Saira 400, 14px, 1.5): your own lines, help text, empty-state lines, system lines. Action details use 13px.
- **Label** (Michroma 400, 10.5px, 0.16em, uppercase): corner readouts, action names, text buttons.
- **Status** (Michroma 400, 11px, 0.2em, uppercase): the single status line under the sphere.
- **Timestamp** (Saira 400, 11px, 0.04em, tabular figures): the time column of conversation and action rows.
- **Code** (JetBrains Mono 400, 12.5px, arc-hot): shell, grep and glob details; command snippets in help text.

### Named Rules
**The Real Command Rule.** Monospace is reserved for text that is literally a command or code. Never use it to make something look technical.

**The Tabular Clock Rule.** Every time value uses tabular figures so readouts don't jitter as they tick.

## Layout

A three-by-three HUD grid filling the viewport (100dvh, no page scroll): corners on the top and bottom rows, telemetry | sphere | actions across the middle, and the type-to-JARVIS field under the sphere. Side columns are `minmax(260px, 1fr)`; the sphere column is `minmax(320px, min(64vh, 42vw))`, so the sphere is always the largest object. Gaps are 12px vertical and 44px horizontal; outer padding is 26px 34px 24px.

The telemetry column is bottom-anchored (newest line at the bottom) and fades out toward the top; the action column is top-anchored (newest first) and fades out toward the bottom. Rows are aligned on a fixed time column (62px for conversation, a 16px icon column for actions).

Under 900px the grid collapses to one column: top corners, sphere at `min(80vw, 42vh)`, telemetry, the two most recent actions (96px max), then the ask field. The bottom corners and timestamps are dropped there, the leader line is not drawn, and padding respects safe-area insets.

## Elevation & Depth

There are no surfaces and no box shadows. Depth comes from light: the sphere's points blend additively, so overlapping light gets brighter, like a hologram; near points are brighter than far ones; a 60vmin cyan bloom sits behind the sphere; and the grid is masked to fade out from the centre. Scrolling content fades into the void through gradient masks rather than being clipped by a container.

### Shadow Vocabulary
- **Live glow** (`box-shadow: 0 0 8px var(--arc)`): only on the live status dot, a light source.
- **Fallback sphere glow** (`box-shadow: inset 0 0 60px var(--arc-soft), 0 0 80px var(--arc-soft)`): the CSS ring that replaces the WebGL sphere when WebGL2 is unavailable.

### Named Rules
**The Light Is the Only Elevation Rule.** Glow belongs only to things that emit light (the sphere, live indicators). Text, rows and fields never lift off the void.

## Shapes

Square and open. Containers are implied by two 14px corner brackets (top-left and bottom-right) in 1px Bracket Line rather than closed borders. Rows are separated by a single bottom hairline; the input is a single underline. The only round things are light sources: the sphere, the live dot (6px). Icons are 1.6px-stroke line SVGs with no fill: chevron, check, cross, spinner arc, send arrow. The mic meter is 12 square 5x10px segments with 3px gaps.

## Components

### Buttons
- **Shape:** no box, no fill, no radius (0).
- **Text button:** Arc Cyan label type (Michroma 10.5px, 0.16em, uppercase) with 8px padding, or an 18px stroked icon in the same colour.
- **Hover / Focus:** hover goes white-hot; focus shows a 1px Arc Cyan outline offset 3px; disabled drops to slate at 35%.
- **The sphere is the primary button:** clicking it (or Space) talks, interrupts, or stops listening. Its focus ring is a 1px dashed Bracket Line circle inset 8%.

### Inputs / Fields
- **Style:** transparent, borderless text in Saira 15px over a 1px Bracket Line underline, padding 10px 2px.
- **Focus:** the underline turns Arc Cyan; no glow, no box.
- **Disabled:** placeholder reads "Pair first"; the send button dims.

### Cards / Containers
- **Corner Style:** open brackets only (14px, 1px Bracket Line), no radius.
- **Background:** none; the void shows through.
- **Shadow Strategy:** none (see Elevation & Depth).
- **Internal Padding:** 18px 20px (12px 14px under 900px).

### Telemetry Line
Conversation rendered as a log: timestamp, then content. Your lines are slate with a small cyan chevron; JARVIS's replies are full Hologram Text at body size spanning the chevron column; system errors are amber. Each new line wipes in left-to-right (clip-path, 0.7s).

### Action Callout
One row per tool call, newest first: a 16px state icon (spinning cyan arc while running, mint check when done, amber cross when failed), the action name in label type, a timestamp, and a one-line ellipsised detail (mono white-hot when it is a real command). Rows slide in from the left with a brief blur (0.6s). On wide screens a 1px Arc Cyan leader line draws from the sphere's edge to the running row (0.8s stroke draw), with one elbow.

### Corner Readouts
Label over value in label type: the wordmark and state word with a live dot (top-left), the clock and date (top-right), the Mac link with real latency (bottom-left), the wake-word state and a 12-segment live mic meter (bottom-right). Failing values turn amber.

### Voice Sphere (signature)
A WebGL2 Fibonacci sphere of points (2800, or 1600 on small screens) inside three orbital rings: a fine continuous orbit, a dashed outer track, an open arc. Each state has a look that the live values ease toward: idle breathes; listening expands and heats with mic level; transcribing half-swirls; thinking swirls and spins faster; speaking pulses with the voice's level; offline shrinks and turns slate. Boot starts as a loose cloud that assembles on first engage. Reduced motion skips the assembly, removes the swirl and slows time to a quarter. Without WebGL2 a breathing 2px cyan CSS ring stands in.

## Do's and Don'ts

### Do:
- **Do** keep the sphere the largest and brightest thing on screen; everything else is instrumentation around it.
- **Do** show only true state in readouts: real time, real latency, real mic level, real wake-word state.
- **Do** mark containers with open 14px corner brackets and separate rows with a single hairline.
- **Do** keep the 48px holographic grid faint (3.5% cyan) and masked to the centre; it is the lab floor of this world.
- **Do** drive motion from state changes; the one ambient motion is the sphere's breathing.
- **Do** use amber only for things that need the user's attention, and say what to do in plain words.
- **Do** keep readable text at Instrument Slate (#86b9c9) or brighter.

### Don't:
- **Don't** invent telemetry: no fake coordinates, percentages, hex dumps or scrolling numbers.
- **Don't** close frames into boxes or fill panels with a background; the void stays visible.
- **Don't** put glow on text, rows or fields; glow is for light sources.
- **Don't** introduce a second accent hue, or use amber or mint as decoration.
- **Don't** use monospace for anything that isn't a real command.
- **Don't** reproduce film imagery, logos or Marvel assets.
