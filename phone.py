"""Phone + HUD backend: lets the Jarvis iOS/Android apps and the web HUD talk to the Claude Code brain on this Mac.

    phone app ──POST /ask──► this server ──► Claude Code session ──► reply streamed back as NDJSON
                ◄─{"type":"tool"}── Claude calls the `phone` MCP tool (served here at /mcp)
                ──POST /tool_result──► the tool call returns to Claude

Run with `uv run jarvis.py --serve`. Pair a phone by scanning the QR code it prints; open the HUD link in Chrome.
"""
import io
import json
import mimetypes
import re
import wave
import queue
import secrets
import socket
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlencode

from jarvis import SYSTEM, Claude, Sentences, clean

TOKEN_FILE = Path.home() / ".jarvis" / "token"
WEB = Path(__file__).resolve().parent / "web" / "dist"   # built HUD (cd web && npm run build)
WAKE_MODELS = ("melspectrogram.onnx", "embedding_model.onnx", "hey_jarvis_v0.1.onnx")

ACTIONS = """\
send_sms {to, body}: text someone (Android sends it; iOS opens a filled-in message for the user to tap Send)
call {number}
contacts {query}: search contacts, returns names and numbers
calendar_events {days}: upcoming events (default 1 day)
calendar_add {title, start, end, location?}: times as ISO 8601 local, e.g. 2026-11-14T15:00
reminder_add {title, due?}: iOS Reminders
alarm {hour, minute, label?}: Android only
timer {seconds, label?}
location {}: current coordinates and address
open_app {name}: launch an app by name (iOS: by URL scheme, best effort)
open_url {url}: web pages, deep links, maps
navigate {destination}: start turn-by-turn directions
media {command}: play, pause, next, previous
volume {level}: 0-100 media volume (Android only)
flashlight {on}
battery {}
clipboard_get {} / clipboard_set {text}
notifications {}: current notifications (Android, needs notification access)
read_sms {count}: recent texts (Android)
run_shortcut {name, input?}: run one of the user's Shortcuts (iOS); Shortcuts can do almost anything on iOS
screen_read {}: text and buttons of the app on screen (Android accessibility)
screen_tap {text}: tap the element with this text or description (Android)
screen_type {text}: type into the focused field (Android)
screen_scroll {direction}: up or down (Android)
screen_back {} / screen_home {} (Android)"""

PHONE_TOOL = {
    "name": "phone",
    "description": "Do something on the user's phone. Each user message lists the actions their phone supports; "
                   "use only those. Actions and args:\n" + ACTIONS,
    "inputSchema": {"type": "object", "required": ["action"], "properties": {
        "action": {"type": "string"},
        "args": {"type": "object", "description": "arguments for the action"}}},
}

PHONE_SYSTEM = SYSTEM + """
The user is talking to you through a Jarvis app: a phone app or the web HUD; your replies are spoken there. Each message starts with the device it came from. Your shell, files and AppleScript still run on their Mac, so you can control both.
Anything on the phone itself (texts, calls, contacts, calendar, reminders, alarms, timers, location, apps, music, notifications, flashlight, what's on its screen) goes through the `phone` tool. Each message starts with the phone's platform, local time and supported actions.
To operate another app on Android: open_app, then screen_read, screen_tap and screen_type step by step, reading the screen again after each step.
Messages, calls and anything sent to other people still need a spoken yes first.
"""


def tool_args(arguments):
    """Claude sometimes nests args, sometimes puts them beside `action`, sometimes sends a JSON string."""
    nested = arguments.get("args") or {}
    if isinstance(nested, str):
        try:
            nested = json.loads(nested)
        except ValueError:
            nested = {}
    flat = {k: v for k, v in arguments.items() if k not in ("action", "args")}
    return {**flat, **(nested if isinstance(nested, dict) else {})}


def lan_ip():
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect(("10.255.255.255", 1))  # no packet is sent; just picks the outgoing interface
        return s.getsockname()[0]


def token():
    TOKEN_FILE.parent.mkdir(parents=True, exist_ok=True)
    if not TOKEN_FILE.exists():
        TOKEN_FILE.write_text(secrets.token_urlsafe(18))
        TOKEN_FILE.chmod(0o600)
    return TOKEN_FILE.read_text().strip()


class Turn(Sentences):
    """One phone request in flight: everything Claude says or asks the phone to do goes into `events`."""

    def __init__(self):
        self.events = queue.Queue()

    def say(self, s):
        if s := clean(s):
            self.events.put({"type": "text", "text": s})


class Brain:
    def __init__(self, port, tok, model, cwd):
        self.turn, self.tools, self.lock = None, {}, threading.Lock()
        mcp = {"mcpServers": {"phone": {"type": "http", "url": f"http://127.0.0.1:{port}/mcp",
                                        "headers": {"Authorization": f"Bearer {tok}"}}}}
        self.agent = Claude(model, cwd, self._text, self._done, system=PHONE_SYSTEM,
                            extra=["--mcp-config", json.dumps(mcp)])
        self.agent.on_tool = self._tool
        self.agent.on_tool_end = self._tool_end

    def _tool(self, tid, name, detail):  # every action Claude takes, for the HUD's activity feed
        if self.turn:
            self.turn.events.put({"type": "activity", "id": tid, "name": name, "detail": detail})

    def _tool_end(self, tid, error):
        if self.turn:
            self.turn.events.put({"type": "activity_end", "id": tid, "error": error})

    def _text(self, t):
        if self.turn:
            self.turn.feed(t)

    def _done(self):
        if self.turn and self.agent.pending <= 1:  # the last answer we were waiting for
            self.turn.flush()
            self.turn.events.put({"type": "done"})

    def ask(self, text, device):
        """Start a turn; returns its Turn. A new request from the phone interrupts the old one."""
        with self.lock:
            if self.agent.busy:
                self.agent.interrupt()
            self.turn = Turn()
            actions = ", ".join(device.get("actions", [])) or "none"
            head = (f"[Device: {device.get('platform', '?')} {device.get('model', '')}; "
                    f"local time {device.get('time', '?')}; device actions: {actions}]")
            self.agent.ask(f"{head}\n{text}")
            return self.turn

    def call_tool(self, args):
        """Runs inside the MCP request: hand the call to the phone and wait for its answer."""
        turn = self.turn
        if not turn:
            return "No phone is connected right now.", True
        tid = uuid.uuid4().hex[:12]
        done, box = threading.Event(), {}
        self.tools[tid] = (done, box)
        turn.events.put({"type": "tool", "id": tid, "action": args.get("action", ""), "args": tool_args(args)})
        try:
            if not done.wait(90):
                return "The phone didn't respond in time.", True
            return box["result"], box["error"]
        finally:
            self.tools.pop(tid, None)

    def tool_result(self, tid, result, error):
        if entry := self.tools.get(tid):
            entry[1].update(result=str(result), error=bool(error))
            entry[0].set()


WHISPER = "mlx-community/whisper-large-v3-turbo"
_whisper_lock = threading.Lock()


def transcribe(pcm):
    """Speech-to-text for phones whose own recognizer is missing (emulators, de-Googled Android):
    raw 16 kHz mono 16-bit PCM in, text out, using the same Whisper model as Mac voice mode."""
    import numpy as np
    import mlx_whisper
    audio = np.frombuffer(pcm[: len(pcm) // 2 * 2], np.int16).astype(np.float32) / 32768
    if len(audio) < 16000 * 0.3:
        return ""
    with _whisper_lock:  # one transcription at a time on the GPU
        text = mlx_whisper.transcribe(audio, path_or_hf_repo=WHISPER, condition_on_previous_text=False,
                                      initial_prompt="Jarvis, Claude Code, Codex.")["text"].strip()
    print(f"\033[2m  🎙  {text}\033[0m", flush=True)
    return "" if looped(text) else text


def looped(text):
    """Whisper's failure mode on non-speech: one short phrase repeated ("in the name of the name of …")."""
    words = text.lower().split()
    return len(words) >= 12 and len(set(words)) / len(words) < 0.35


_kokoro, _kokoro_lock = [], threading.Lock()


def speak(text):
    """Kokoro neural voice (same as Mac voice mode) as WAV bytes; None when the browser should speak instead."""
    if not text.strip() or re.search("[а-яё]", text, re.I):
        return None
    import os
    from jarvis import load_kokoro
    with _kokoro_lock:
        try:
            if not _kokoro:
                _kokoro.append(load_kokoro())
            samples, rate = _kokoro[0].create(text, voice=os.getenv("JARVIS_VOICE", "bm_george"), speed=1.05, lang="en-gb")
        except Exception as e:  # missing model, odd input: the HUD uses the browser's voice instead
            print(f"\033[2m  (tts: {e})\033[0m", flush=True)
            return None
    import numpy as np
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes((np.clip(samples, -1, 1) * 32767).astype(np.int16).tobytes())
    return buf.getvalue()


def handler(brain, tok):
    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def _authed(self):
            if self.headers.get("Authorization") == f"Bearer {tok}":
                return True
            self._json(401, {"error": "bad token"})
            return False

        def _json(self, code, obj):
            body = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _body(self):
            return json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")

        def do_GET(self):
            path = self.path.split("?")[0]
            if path == "/health":
                return self._json(200, {"ok": True}) if self._authed() else None
            if path == "/mcp":
                return self._json(405, {"error": "POST only"})
            if path.startswith("/models/") and path[8:] in WAKE_MODELS:  # wake-word models for the HUD
                import openwakeword
                return self._file(Path(openwakeword.__file__).parent / "resources" / "models" / path[8:])
            # the HUD: static files, no auth (the API calls carry the token)
            f = (WEB / path.lstrip("/")).resolve()
            if path != "/" and f.is_file() and f.is_relative_to(WEB):
                return self._file(f)
            if (WEB / "index.html").exists():
                return self._file(WEB / "index.html")
            self._json(404, {"error": "HUD not built: cd web && npm install && npm run build"})

        def _file(self, f):
            body = f.read_bytes()
            kind = {".mjs": "text/javascript", ".wasm": "application/wasm", ".onnx": "application/octet-stream"}.get(
                f.suffix) or mimetypes.guess_type(f.name)[0] or "application/octet-stream"
            self.send_response(200)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-cache" if f.name == "index.html" else "max-age=86400")
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            if not self._authed():
                return
            if self.path == "/transcribe":
                return self._json(200, {"text": transcribe(self.rfile.read(int(self.headers.get("Content-Length") or 0)))})
            body = self._body()
            if self.path == "/speak":
                audio = speak(body.get("text", ""))
                if audio is None:  # Russian, or no voice model: the client falls back to its own voice
                    self.send_response(204)
                    self.end_headers()
                    return
                self.send_response(200)
                self.send_header("Content-Type", "audio/wav")
                self.send_header("Content-Length", str(len(audio)))
                self.end_headers()
                return self.wfile.write(audio)
            if self.path == "/ask":
                return self._ask(body)
            if self.path == "/tool_result":
                brain.tool_result(body.get("id"), body.get("result", ""), body.get("error"))
                return self._json(200, {"ok": True})
            if self.path == "/mcp":
                return self._mcp(body)
            self._json(404, {"error": "not here"})

        def _ask(self, body):
            text = (body.get("text") or "").strip()
            if not text:
                return self._json(400, {"error": "empty"})
            print(f"\033[32mphone>\033[0m {text}", flush=True)
            turn = brain.ask(text, body.get("device") or {})
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ndjson")
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()  # HTTP/1.0: the stream ends when we close the connection
            try:
                while True:
                    try:
                        ev = turn.events.get(timeout=15)
                    except queue.Empty:
                        ev = {"type": "ping"}  # keeps mobile networks from dropping a long tool run
                    if ev["type"] == "text":
                        print(f"\033[36mjarvis>\033[0m {ev['text']}", flush=True)
                    elif ev["type"] == "activity":
                        print(f"\033[2m  ⚙ {ev['name']}: {ev['detail'][:100]}\033[0m", flush=True)
                    self.wfile.write((json.dumps(ev) + "\n").encode())
                    self.wfile.flush()
                    if ev["type"] == "done" or turn is not brain.turn:
                        return
            except (BrokenPipeError, ConnectionResetError):  # phone hung up: stop working on its request
                if turn is brain.turn:
                    brain.agent.interrupt()

        def _mcp(self, msg):
            """Minimal MCP (streamable HTTP, JSON responses) exposing the single `phone` tool."""
            method, mid = msg.get("method", ""), msg.get("id")
            if mid is None:  # notification
                self.send_response(202)
                self.end_headers()
                return
            if method == "initialize":
                result = {"protocolVersion": msg.get("params", {}).get("protocolVersion", "2025-06-18"),
                          "capabilities": {"tools": {}}, "serverInfo": {"name": "jarvis-phone", "version": "1"}}
            elif method == "tools/list":
                result = {"tools": [PHONE_TOOL]}
            elif method == "tools/call":
                params = msg.get("params", {})
                args = params.get("arguments") or {}
                print(f"\033[2m  📱 {args.get('action')} {json.dumps(tool_args(args))[:100]}\033[0m", flush=True)
                text, err = brain.call_tool(args)
                result = {"content": [{"type": "text", "text": text}], "isError": err}
            elif method == "ping":
                result = {}
            else:
                return self._json(200, {"jsonrpc": "2.0", "id": mid,
                                        "error": {"code": -32601, "message": f"unknown method {method}"}})
            self._json(200, {"jsonrpc": "2.0", "id": mid, "result": result})

    return H


def serve(port, model, cwd, public_url=None):
    tok = token()
    url = public_url or f"http://{lan_ip()}:{port}"
    brain = Brain(port, tok, model, cwd)
    threading.Thread(target=transcribe, args=(bytes(32000),), daemon=True).start()  # load Whisper before a phone needs it
    threading.Thread(target=speak, args=("Ready.",), daemon=True).start()  # and the voice
    server = ThreadingHTTPServer(("0.0.0.0", port), handler(brain, tok))
    pair = "jarvis://pair?" + urlencode({"url": url, "token": tok})
    print(f"Jarvis phone server on {url}\nScan with your phone's camera to pair (or enter the URL and token in the app):\n")
    try:
        import qrcode
        q = qrcode.QRCode(border=1)
        q.add_data(pair)
        q.print_ascii(invert=True)
    except ImportError:
        pass
    print(f"\n  URL:   {url}\n  Token: {tok}\n  Link:  {pair}\n")
    print(f"HUD (open in Chrome on this Mac):  http://localhost:{port}/#token={tok}\n")
    print("Away from home? Install Tailscale on the Mac and phone and pass --public-url http://<mac-tailscale-ip>:8765")
    server.serve_forever()
