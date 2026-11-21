#!/usr/bin/env python3
"""Jarvis: a real-time voice front-end for Claude Code (or Codex) on macOS.

Wake word ("hey Jarvis", openWakeWord) -> speech-to-text (Whisper on MLX) ->
a persistent Claude Code session that can drive the whole Mac -> streamed
sentence-by-sentence neural speech (Kokoro, or macOS `say`). Say "hey Jarvis" at any time to
interrupt it.
"""
import argparse
import json
import os
import queue
import re
import subprocess
import sys
import threading
import time
from pathlib import Path

JOBS = Path.home() / ".jarvis" / "jobs"
RUN_JOB = f"{sys.executable} {Path(__file__).resolve()} --job"

SYSTEM = f"""You are JARVIS, the user's personal voice assistant running on their Mac, in the spirit of Tony Stark's AI: calm, capable, quietly witty, dry British understatement. Call the user "sir" occasionally, not every sentence.

Everything you write is spoken aloud by text-to-speech, so:
- Plain conversational sentences only. No markdown, bullet lists, headings, code blocks, tables, emoji. Don't read out URLs, file paths or code unless asked; describe them.
- Be brief: one to three sentences unless asked for detail. Lead with the answer.
- Before any action that takes more than a few seconds, say one short sentence first, like "Checking your calendar."
- Never say your own name; it triggers your wake word.
- Reply in the language the user spoke.
- Input comes from speech recognition and may be misheard ("quick Calculator" is probably "quit Calculator"). If the words don't form a sensible request, confirm what you think was meant ("Quit Calculator?") instead of guessing.

You have full control of this Mac through your tools. Act, don't explain how:
- shell commands; `open` for apps, URLs and files; `osascript` (AppleScript/JXA) for apps, windows, volume, Music/Spotify, Finder, Safari/Chrome tabs, Reminders, Calendar, Notes, Mail, Messages, System Events; `shortcuts run "<name>"` for the user's Shortcuts; `pmset`, `networksetup`, `screencapture` and friends.
- seeing the screen: for "what's on my screen", "read this", "what's this error" and the like, run `screencapture -x /tmp/jarvis-screen.png` and look at the image with your Read tool.
- web search and fetch for news, weather, facts and anything current.
- reading and editing files and code anywhere in the home folder.

Ask for a spoken yes before anything destructive, irreversible or outward-facing: deleting files, sending messages or email, purchases, posting online, git push, quitting apps with unsaved work, shutdown.

Long jobs (big coding tasks, research, anything over a minute or two) must not block the conversation. Hand them to a separate background agent, then tell the user it's running:
  {RUN_JOB} <short-name> <working-dir> "<detailed, self-contained task>"
Add `--agent codex` to use Codex instead of Claude Code (when the user asks for Codex). The job announces itself aloud when finished; its log is {JOBS}/<short-name>.log. When asked how a job is going, read the log and summarize.
"""

HALLUCINATIONS = {"", "you", "thank you", "thanks for watching", "bye", "продолжение следует"}
CANCEL = {"stop", "cancel", "never mind", "nevermind", "shut up", "стоп", "отмена", "хватит"}


def clean(s):
    """Make text speakable: drop markdown symbols and URLs."""
    s = re.sub(r"https?://\S+", "link", s)
    s = re.sub(r"[*_#`|>]+", "", s)
    return re.sub(r"^\s*[-•]\s+", "", s).strip()


MODELS = Path.home() / ".jarvis" / "models"
KOKORO_URL = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/"


def load_kokoro():
    """Neural TTS (Kokoro-82M, Apache-2.0), ~350 MB downloaded once to ~/.jarvis/models."""
    import urllib.request
    from kokoro_onnx import Kokoro

    MODELS.mkdir(parents=True, exist_ok=True)
    for f in ("kokoro-v1.0.onnx", "voices-v1.0.bin"):
        if not (MODELS / f).exists():
            print(f"Downloading voice model {f}…", flush=True)
            urllib.request.urlretrieve(KOKORO_URL + f, MODELS / f".{f}.part")
            (MODELS / f".{f}.part").rename(MODELS / f)
    return Kokoro(str(MODELS / "kokoro-v1.0.onnx"), str(MODELS / "voices-v1.0.bin"))


class Sentences:
    """Turns streamed text into whole sentences; subclasses decide what say() does with each."""
    buf = ""

    def feed(self, text):
        self.buf += text
        while m := re.search(r"(?<=[.!?…:;])\s+|\n+", self.buf):
            self.say(self.buf[: m.start()])
            self.buf = self.buf[m.end():]

    def flush(self):
        self.say(self.buf)
        self.buf = ""


class Voice(Sentences):
    """Sentence-streaming TTS. Kokoro voices (names like bm_george) or any macOS `say` voice.
    Synthesis of the next sentence overlaps playback of the current one. stop() cancels everything."""

    def __init__(self, voice, ru_voice, rate, mute):
        self.voice, self.ru_voice, self.rate, self.mute = voice, ru_voice, rate, mute
        self.q, self.out, self.buf, self.gen, self.p = queue.Queue(), queue.Queue(), "", 0, None
        self.kokoro = load_kokoro() if "_" in voice and not mute else None
        threading.Thread(target=self._synth, daemon=True).start()
        threading.Thread(target=self._play, daemon=True).start()

    @property
    def busy(self):
        return self.q.unfinished_tasks + self.out.unfinished_tasks > 0

    def say(self, s):
        if s := clean(s):
            self.q.put((self.gen, s))

    def stop(self):
        self.gen += 1
        self.buf = ""
        if self.p:
            self.p.terminate()
        if self.kokoro:
            import sounddevice as sd
            sd.stop()

    def _synth(self):
        while True:
            gen, s = self.q.get()
            audio = None
            if gen == self.gen and self.kokoro and not re.search("[а-яё]", s, re.I):
                try:
                    audio = self.kokoro.create(s, voice=self.voice, speed=self.rate / 185, lang="en-gb")
                except Exception as e:  # odd input the phonemizer chokes on: fall back to `say`
                    print(f"\033[2m(tts: {e})\033[0m")
            self.out.put((gen, s, audio))
            self.q.task_done()

    def _play(self):
        import sounddevice as sd
        while True:
            gen, s, audio = self.out.get()
            if gen == self.gen:
                print(f"\033[36mjarvis>\033[0m {s}", flush=True)
                if self.mute:
                    pass
                elif audio is not None:
                    sd.play(*audio)
                    sd.wait()
                else:
                    # Russian -> ru_voice; Kokoro failed -> Daniel; otherwise the chosen `say` voice
                    v = self.ru_voice if re.search("[а-яё]", s, re.I) else "Daniel" if self.kokoro else self.voice
                    self.p = subprocess.Popen(["say", "-v", v, "-r", str(self.rate), s])
                    self.p.wait()
                    self.p = None
            self.out.task_done()


class Claude:
    """One long-lived `claude -p` stream-json session: no per-turn startup cost, full memory."""

    def __init__(self, model, cwd, on_text, on_done, system=SYSTEM, extra=()):
        self.on_text, self.on_done, self.pending = on_text, on_done, 0
        self.on_tool = self.on_tool_end = None  # optional (id, name, detail) / (id, is_error) hooks, used by the HUD
        cmd = ["claude", "-p", "--input-format", "stream-json", "--output-format", "stream-json",
               "--verbose", "--include-partial-messages", "--permission-mode", "bypassPermissions",
               "--append-system-prompt", system, *extra]
        if model:
            cmd += ["--model", model]
        self.cmd, self.cwd = cmd, cwd
        self._start()

    def _start(self):
        self.p = subprocess.Popen(self.cmd, cwd=self.cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=subprocess.DEVNULL, text=True, bufsize=1)
        threading.Thread(target=self._read, args=(self.p,), daemon=True).start()

    @property
    def busy(self):
        return self.pending > 0

    def _send(self, obj):
        self.p.stdin.write(json.dumps(obj) + "\n")
        self.p.stdin.flush()

    def ask(self, text):
        if self.p.poll() is not None:  # session died (crash, logout): start a fresh one
            self._start()
        self.pending += 1
        self._send({"type": "user", "message": {"role": "user", "content": text}})

    def interrupt(self):
        if self.busy and self.p.poll() is None:
            self._send({"type": "control_request", "request_id": f"int-{time.time()}",
                        "request": {"subtype": "interrupt"}})

    def _read(self, p):
        for line in p.stdout:
            try:
                e = json.loads(line)
            except ValueError:
                continue
            t = e.get("type")
            if t == "stream_event":
                ev = e["event"]
                if ev.get("type") == "content_block_delta" and ev["delta"].get("type") == "text_delta":
                    self.on_text(ev["delta"]["text"])
                elif ev.get("type") == "message_stop":
                    self.on_text("\n")  # speak what we have before a tool runs
            elif t == "assistant":
                for b in e["message"].get("content", []):
                    if b.get("type") == "tool_use":
                        i = b["input"]
                        arg = next((i[k] for k in ("command", "description", "action", "query", "url", "file_path", "pattern",
                                                   "prompt") if i.get(k)), "")
                        print(f"\033[2m  ⚙ {b['name']}: {str(arg)[:100]}\033[0m", flush=True)
                        if self.on_tool:
                            self.on_tool(b["id"], b["name"], str(arg)[:300])
            elif t == "user" and self.on_tool_end:
                for b in e.get("message", {}).get("content", []):
                    if isinstance(b, dict) and b.get("type") == "tool_result":
                        self.on_tool_end(b.get("tool_use_id"), bool(b.get("is_error")))
            elif t == "system" and e.get("subtype") == "init" and not getattr(self, "sid", None):
                self.sid = e["session_id"]
                print(f"\033[2m(claude session {self.sid}; inspect with `claude --resume {self.sid}`)\033[0m")
            elif t == "result":
                self.on_done()
                self.pending = max(0, self.pending - 1)
        self.pending = 0
        self.on_text("\nMy Claude Code process has stopped, sir.\n")
        self.on_done()


class Codex:
    """`codex exec` per turn, resuming the same thread. Slower to start than Claude."""

    def __init__(self, model, cwd, on_text, on_done):
        self.model, self.cwd, self.on_text, self.on_done = model, cwd, on_text, on_done
        self.thread, self.p, self.pending = None, None, 0

    @property
    def busy(self):
        return self.pending > 0

    def ask(self, text):
        self.pending += 1
        threading.Thread(target=self._run, args=(text,), daemon=True).start()

    def interrupt(self):
        if self.p:
            self.p.terminate()

    def _run(self, text):
        cmd = ["codex", "exec", "--json", "--skip-git-repo-check", "-s", "danger-full-access"]
        if self.model:
            cmd += ["-m", self.model]
        if self.thread:
            cmd += ["resume", self.thread]
        else:
            text = f"{SYSTEM}\nThis is a live voice conversation: no verification, risk or summary footers.\n\nThe user says: {text}"
        self.p = subprocess.Popen(cmd + [text], cwd=self.cwd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                  stderr=subprocess.DEVNULL, text=True)
        for line in self.p.stdout:
            try:
                e = json.loads(line)
            except ValueError:
                continue
            item = e.get("item", {})
            if e.get("type") == "thread.started":
                self.thread = e["thread_id"]
            elif e.get("type") == "item.started" and item.get("type") == "command_execution":
                print(f"\033[2m  ⚙ {item.get('command', '')[:100]}\033[0m", flush=True)
            elif e.get("type") == "item.completed" and item.get("type") == "agent_message":
                self.on_text(item["text"] + "\n")
            elif e.get("type") == "turn.failed":
                print(f"\033[31mcodex: {e['error'].get('message', '')[:300]}\033[0m", flush=True)
                self.on_text("Codex ran into an error, sir. The details are in the terminal.\n")
        self.p.wait()
        self.on_done()
        self.pending -= 1


def spawn_job(name, cwd, task, agent):
    """Run an agent detached from Jarvis (own session), so it survives Jarvis restarting."""
    import shlex
    JOBS.mkdir(parents=True, exist_ok=True)
    cwd = Path(cwd).expanduser()
    cwd.mkdir(parents=True, exist_ok=True)
    log = JOBS / f"{name}.log"
    cmd = (["claude", "-p", "--permission-mode", "bypassPermissions", task] if agent == "claude"
           else ["codex", "exec", "--skip-git-repo-check", "-s", "danger-full-access", task])
    done = shlex.quote(f"The {name} job is finished.")
    script = f"{shlex.join(cmd)} > {shlex.quote(str(log))} 2>&1 < /dev/null; say {done}"
    subprocess.Popen(["sh", "-c", script], cwd=cwd, start_new_session=True,
                     stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print(f"started job {name!r} ({agent}) in {cwd}; log: {log}")


def chime(name):
    subprocess.Popen(["afplay", f"/System/Library/Sounds/{name}.aiff"])


def voice_loop(args, agent, tts):
    import numpy as np
    import openwakeword
    import sounddevice as sd
    import mlx_whisper
    from openwakeword.model import Model

    RATE, FRAME = 16000, 1280  # 80 ms frames, what openWakeWord expects
    wake_path = Path(openwakeword.__file__).parent / "resources/models/hey_jarvis_v0.1.onnx"
    wake = Model(wakeword_model_paths=[str(wake_path)])
    key = next(iter(wake.models))

    def transcribe(audio):
        r = mlx_whisper.transcribe(audio.astype(np.float32) / 32768, path_or_hf_repo=args.whisper,
                                   language=args.lang, condition_on_previous_text=False,
                                   initial_prompt="Jarvis, Claude Code, Codex.")  # vocabulary hint
        return r["text"].strip()

    print("Loading speech model…", flush=True)
    transcribe(np.zeros(RATE, np.int16))  # warm up (downloads the model on first run)

    frames = queue.Queue()
    stream = sd.InputStream(samplerate=RATE, channels=1, dtype="int16", blocksize=FRAME,
                            device=args.mic, callback=lambda d, *_: frames.put(d[:, 0].copy()))
    stream.start()
    print(f"Mic: {sd.query_devices(stream.device)['name']}. Say “hey Jarvis”. Ctrl+C to quit.", flush=True)
    tts.say("Online and at your service, sir.")
    if not any(np.abs(frames.get()).max() for _ in range(25)):  # 2 s of exact zeros = macOS blocked the mic
        print("\033[31mMic is sending pure silence: allow your terminal in System Settings → Privacy & Security"
              " → Microphone, then restart it.\033[0m", flush=True)

    # ponytail: energy-based endpointing with an adaptive noise floor; swap in Silero VAD if noisy rooms misfire.
    floor, listening, buf, started, last_voice, voiced, timeout = 200.0, False, [], 0.0, 0.0, 0, 0.0
    awaiting_followup, last_wake, peak = False, 0.0, 0.0

    def listen(t, skip=0.0):  # skip: ignore our own chime and the tail of the wake word
        nonlocal listening, buf, started, last_voice, voiced, timeout, peak
        listening, buf, started, last_voice, voiced, timeout, peak = True, [], time.time() + skip, 0.0, 0, t, 0.0

    while True:
        frame = frames.get()
        now = time.time()
        rms = float(np.sqrt(np.mean(frame.astype(np.float32) ** 2)))

        if wake.predict(frame)[key] > args.wake_threshold and now - last_wake > 1.5:
            wake.reset()
            last_wake = now
            if tts.busy or agent.busy:
                tts.stop()
                agent.interrupt()
            chime("Pop")
            print("\033[33m● listening\033[0m", flush=True)
            listen(6, skip=0.45)
            continue

        talking = tts.busy or agent.busy
        if not listening:
            if not talking:
                floor = 0.98 * floor + 0.02 * min(rms, floor * 2)
                if awaiting_followup and args.followup > 0:
                    awaiting_followup = False
                    print("\033[2m● follow-up window\033[0m", flush=True)
                    listen(args.followup)
            continue
        if tts.busy:  # never transcribe our own voice
            continue

        buf.append(frame)
        if now < started:  # chime / wake-word tail: keep the audio, but don't count it as speech yet
            continue
        # once speech starts, "silence" is relative to how loud the speaker is, so fans and room noise can't hold it open
        if rms > max(args.mic_threshold, floor * 3, peak * 0.15):
            voiced += 1
            last_voice = now
            peak = max(peak, rms)
        elif not voiced:
            buf = buf[-3:]  # nothing said yet: keep only 240 ms of pre-roll (drops the chime if you paused)
        if not voiced and now - started > timeout:
            listening = False
        elif voiced and voiced < 4 and now - last_voice > args.silence:
            buf, voiced, peak = [], 0, 0.0  # a click or our own chime, not speech: keep waiting
        elif (voiced and now - last_voice > args.silence) or now - started > 30:
            listening = False
            text = transcribe(np.concatenate(buf))
            norm = re.sub(r"[^\w\s]", "", text.lower()).strip()
            norm = re.sub(r"^(hey |ok |okay )?jarvis\s*", "", norm)
            print(f"\033[32myou>\033[0m {text}", flush=True)
            if norm in HALLUCINATIONS:
                continue
            if norm in CANCEL:
                awaiting_followup = False
                chime("Tink")
                continue
            agent.ask(text)
            awaiting_followup = True


def text_loop(agent, tts):
    print("Text mode. Type a request, Ctrl+D to quit.")
    while True:
        try:
            line = input("\033[32myou>\033[0m ").strip()
        except (EOFError, KeyboardInterrupt):
            return
        if not line:
            continue
        agent.ask(line)
        while agent.busy or tts.busy:
            time.sleep(0.1)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--agent", choices=["claude", "codex"], default=os.getenv("JARVIS_AGENT", "claude"))
    ap.add_argument("--model", default=os.getenv("JARVIS_MODEL", "sonnet"),
                    help="model for the brain (claude alias like sonnet/opus/haiku, or a codex model); '' for default")
    ap.add_argument("--cwd", default=str(Path.home()), help="working directory for the agent")
    ap.add_argument("--text", action="store_true", help="type instead of talk")
    ap.add_argument("--mute", action="store_true", help="print replies instead of speaking them")
    ap.add_argument("--voice", default=os.getenv("JARVIS_VOICE", "bm_george"),
                    help="Kokoro voice (bm_george, bm_lewis, bm_daniel, bm_fable, am_michael…) or a macOS `say` voice (Daniel)")
    ap.add_argument("--ru-voice", default="Milena", help="voice used for Cyrillic replies")
    ap.add_argument("--rate", type=int, default=195, help="speech words per minute")
    ap.add_argument("--lang", default=os.getenv("JARVIS_LANG"), help="force STT language, e.g. en or ru")
    ap.add_argument("--whisper", default="mlx-community/whisper-large-v3-turbo")
    ap.add_argument("--mic", type=int, default=None, help="input device index (python -m sounddevice lists them)")
    ap.add_argument("--wake-threshold", type=float, default=0.5)
    ap.add_argument("--mic-threshold", type=float, default=300, help="minimum RMS that counts as speech")
    ap.add_argument("--silence", type=float, default=0.8, help="seconds of silence that end a request")
    ap.add_argument("--followup", type=float, default=6, help="seconds to keep listening after a reply (0 = off)")
    ap.add_argument("--serve", action="store_true", help="run the backend for the Jarvis phone apps")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--public-url", help="URL phones use to reach this Mac, e.g. a Tailscale address")
    ap.add_argument("--job", nargs=3, metavar=("NAME", "DIR", "TASK"), help="run TASK in a detached background agent")
    args = ap.parse_args()
    if args.job:
        return spawn_job(*args.job, args.agent)
    if args.serve:
        import phone
        try:
            return phone.serve(args.port, args.model or None, args.cwd, args.public_url)
        except KeyboardInterrupt:
            return print("\nGoodbye, sir.")

    tts = Voice(args.voice, args.ru_voice, args.rate, args.mute)
    Agent = Claude if args.agent == "claude" else Codex
    model = args.model if (args.agent == "claude" or args.model != "sonnet") else None
    agent = Agent(model or None, args.cwd, tts.feed, tts.flush)
    try:
        text_loop(agent, tts) if args.text else voice_loop(args, agent, tts)
    except KeyboardInterrupt:
        pass
    finally:
        tts.stop()
        print("\nGoodbye, sir.")


if __name__ == "__main__":
    main()
