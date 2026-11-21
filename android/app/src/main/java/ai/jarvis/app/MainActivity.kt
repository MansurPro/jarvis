package ai.jarvis.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : Activity() {
    private enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    private val prefs by lazy { getSharedPreferences("jarvis", MODE_PRIVATE) }
    private val serverUrl get() = prefs.getString("url", "")!!.trimEnd('/')
    private val token get() = prefs.getString("token", "")!!

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var mic: Button
    private lateinit var input: EditText
    private lateinit var tts: TextToSpeech
    private var recognizer: SpeechRecognizer? = null

    private var state = State.IDLE
    private var conversing = false   // keep listening after each reply, like a real conversation
    @Volatile private var turnId = 0
    @Volatile private var conn: HttpURLConnection? = null
    private var turnDone = true
    private var speaking = 0
    private var listenWhenReady = false
    private var ttsReady = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    /** A recognizer that hears nothing at all for 15 s is stuck (offline, missing language pack): try the next one. */
    private val watchdog = Runnable {
        if (state == State.LISTENING) {
            recognizer?.cancel(); recognizer?.destroy(); recognizer = null
            nextRecognizer()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        tts = TextToSpeech(this) { ok ->
            if (ok == TextToSpeech.SUCCESS) {
                tts.language = Locale.UK
                tts.voices?.firstOrNull { it.locale == Locale.UK && !it.isNetworkConnectionRequired && "male" in it.name.lowercase() && "female" !in it.name.lowercase() }
                    ?.let { tts.voice = it }
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) = runOnUiThread { speaking--; maybeFollowUp() }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) = runOnUiThread { speaking--; maybeFollowUp() }
                    override fun onStop(id: String?, interrupted: Boolean) = runOnUiThread { speaking = 0 }
                })
            }
            ttsReady = true
            if (listenWhenReady) runOnUiThread { listenWhenReady = false; startConversation() }
        }
        askPermissions()
        handleIntent(intent)
        if (serverUrl.isEmpty()) showSetupHelp()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        cancelTurn()
        recognizer?.destroy()
        tts.shutdown()
        super.onDestroy()
    }

    private fun handleIntent(i: Intent?) {
        val data = i?.data
        if (data?.scheme == "jarvis" && data.host == "pair") {
            prefs.edit().putString("url", data.getQueryParameter("url")).putString("token", data.getQueryParameter("token")).apply()
            log("system", "Paired with ${data.getQueryParameter("url")}. Testing…")
            testConnection()
        }
        i?.getStringExtra("ask")?.let { i.removeExtra("ask"); conversing = false; ask(it) } // for testing: adb shell am start -e ask "…"
        if (i?.getBooleanExtra("listen", false) == true || i?.action == Intent.ACTION_ASSIST) {
            i.removeExtra("listen")
            if (ttsReady) startConversation() else listenWhenReady = true
        }
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private lateinit var sphere: SphereView
    private lateinit var stateWord: TextView
    private lateinit var clock: TextView
    @Volatile private var micLevel = 0f
    private var offline = false
    private val log = android.text.SpannableStringBuilder()

    /** Same world as the web HUD (DESIGN.md): one cyan light on a near-black void, the sphere at the centre. */
    private fun buildUi() {
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        fun label(size: Float, color: Int, spacing: Float = 0.16f) = TextView(this).apply {
            textSize = size; setTextColor(color); letterSpacing = spacing; isAllCaps = true
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val header = LinearLayout(this).apply { gravity = Gravity.TOP }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(this).apply {
            text = "J.A.R.V.I.S."; textSize = 16f; setTextColor(ACCENT); letterSpacing = 0.34f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        stateWord = label(10f, MUTED).also { left.addView(it) }
        header.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        clock = TextView(this).apply {
            textSize = 20f; setTextColor(TEXT); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
        }
        right.addView(clock)
        right.addView(label(10f, ACCENT).apply {
            text = "Settings"; setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { showSettings() }
        })
        header.addView(right)
        root.addView(header)

        val m = resources.displayMetrics
        val size = minOf((m.widthPixels * 0.82f).toInt(), (m.heightPixels * 0.40f).toInt())
        sphere = SphereView(this).apply {
            contentDescription = "Talk to Jarvis"
            isClickable = true
            setOnClickListener { onMic() }
            level = {
                when (state) {
                    State.LISTENING -> micLevel
                    State.SPEAKING -> 0.3f + 0.18f * kotlin.math.sin(System.nanoTime() / 9e7).toFloat() // TTS gives no samples
                    else -> 0f
                }
            }
        }
        root.addView(sphere, LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_HORIZONTAL })

        status = label(11f, ACCENT, 0.2f).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(10)) }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        logView = TextView(this).apply { setTextColor(TEXT); textSize = 16f; setLineSpacing(0f, 1.25f); setTextIsSelectable(true) }
        scroll = ScrollView(this).apply { addView(logView); isVerticalScrollBarEnabled = false }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(BG) }
        }
        input = EditText(this).apply {
            hint = "Type to JARVIS"; setHintTextColor(MUTED); setTextColor(TEXT); textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            backgroundTintList = android.content.res.ColorStateList.valueOf(LINE)
            setOnEditorActionListener { _, _, _ -> sendTyped(); true }
        }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label(11f, ACCENT).apply {
            text = "Send"; setPadding(dp(14), dp(12), dp(4), dp(12))
            setOnClickListener { sendTyped() }
        })
        root.addView(row)
        // Android 15 draws edge to edge: keep clear of the status bar, navigation bar and keyboard
        root.setOnApplyWindowInsetsListener { v, insets ->
            val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                i.top to i.bottom
            } else @Suppress("DEPRECATION") (insets.systemWindowInsetTop to insets.systemWindowInsetBottom)
            v.setPadding(dp(18), top + dp(10), dp(18), bottom + dp(10))
            insets
        }
        setContentView(root)
        setState(State.IDLE)
        val ticker = object : Runnable {
            override fun run() {
                clock.text = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                handler.postDelayed(this, 1000)
            }
        }
        ticker.run()
    }

    private fun setState(s: State) {
        state = s
        sphere.mode = when {
            offline -> SphereView.Mode.OFFLINE
            s == State.LISTENING -> SphereView.Mode.LISTENING
            s == State.THINKING -> SphereView.Mode.THINKING
            s == State.SPEAKING -> SphereView.Mode.SPEAKING
            else -> SphereView.Mode.IDLE
        }
        stateWord.text = if (offline) "Offline" else when (s) {
            State.IDLE -> "Online"; State.LISTENING -> "Listening"; State.THINKING -> "Working"; State.SPEAKING -> "Speaking"
        }
        status.setTextColor(if (offline) AMBER else ACCENT)
        status.text = if (offline) "Your Mac is not answering" else when (s) {
            State.IDLE -> "Tap the sphere or use the assist gesture"
            State.LISTENING -> "Listening"
            State.THINKING -> "Working"
            State.SPEAKING -> "Speaking · tap to interrupt"
        }
    }

    /** The conversation as telemetry: your words dim, JARVIS's bright, actions in mono, problems in amber. */
    private fun log(who: String, text: String) {
        if (log.isNotEmpty()) log.append(if (who == "act") "\n" else "\n\n")
        val start = log.length
        log.append(if (who == "you") "›  $text" else text)
        val span = { o: Any -> log.setSpan(o, start, log.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        when (who) {
            "you" -> { span(android.text.style.ForegroundColorSpan(MUTED)); span(android.text.style.RelativeSizeSpan(0.88f)) }
            "act" -> { span(android.text.style.ForegroundColorSpan(MUTED)); span(android.text.style.RelativeSizeSpan(0.78f)); span(android.text.style.TypefaceSpan("monospace")) }
            "jarvis" -> span(android.text.style.ForegroundColorSpan(TEXT))
            else -> span(android.text.style.ForegroundColorSpan(AMBER))
        }
        logView.text = log
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** JARVIS sentences of one reply join one paragraph. */
    private var replyOpen = false

    private fun appendReply(t: String) {
        val start = log.length
        log.append(" $t")
        log.setSpan(android.text.style.ForegroundColorSpan(TEXT), start, log.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        logView.text = log
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        conversing = false
        ask(t)
    }

    private fun onMic() {
        when (state) {
            State.LISTENING -> { conversing = false; recognizer?.stopListening(); macListen = MAC_FINISH }
            State.THINKING, State.SPEAKING -> { cancelTurn(); tts.stop(); startConversation() }
            State.IDLE -> startConversation()
        }
    }

    // ---------------------------------------------------------------- listening

    private fun startConversation() {
        conversing = true
        listen()
    }

    /** Speech recognizers to try, best first. Never our own stub (it becomes the system default once Jarvis is
     *  the assistant). "mac" = record here and transcribe with Whisper on the Mac: always works, even on
     *  emulators and phones without Google. The choice that works is remembered. */
    private var recognizerChoice
        get() = prefs.getInt("recognizer", 0)
        set(v) = prefs.edit().putInt("recognizer", v).apply()

    private fun recognizerOptions(): List<String> {
        val services = packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo }.filter { it.packageName != packageName }
            .sortedBy { if (it.packageName == "com.google.android.googlequicksearchbox") 0 else 1 }
            .map { "${it.packageName}/${it.name}" }
        val onDevice = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) listOf("ondevice") else emptyList()
        return services + onDevice + "mac"
    }

    /** Has the current recognizer ever produced text? Until it has, any error means "broken here": move on. */
    private var recognizerProven
        get() = prefs.getBoolean("recognizer_ok", false)
        set(v) = prefs.edit().putBoolean("recognizer_ok", v).apply()

    private fun nextRecognizer() {
        recognizerChoice = (recognizerChoice + 1).coerceAtMost(recognizerOptions().lastIndex)
        recognizerProven = false
        listen()
    }

    private fun makeRecognizer(option: String): SpeechRecognizer =
        if (option == "ondevice" && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        else SpeechRecognizer.createSpeechRecognizer(this, ComponentName.unflattenFromString(option))

    private fun recognizeIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
    }

    @Volatile private var macListen = MAC_OFF

    /** Record until a pause, then let Whisper on the Mac transcribe it. Same endpointing as Mac voice mode. */
    private fun listenWithMac() {
        setState(State.LISTENING)
        status.text = "Listening… (Mac speech recognition)"
        macListen = MAC_RECORDING
        thread {
            val rate = 16000
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 8192))
            val frames = ArrayList<ShortArray>()
            var floor = 300.0; var peak = 0.0; var voiced = 0; var lastVoice = 0L
            val started = SystemClock.elapsedRealtime()
            rec.startRecording()
            try {
                while (macListen == MAC_RECORDING) {
                    val f = ShortArray(1280) // 80 ms
                    val n = rec.read(f, 0, f.size)
                    if (n <= 0) continue
                    val rms = sqrt((0 until n).sumOf { f[it].toDouble() * f[it] } / n)
                    micLevel = (rms / 2500).toFloat().coerceAtMost(1f)
                    val now = SystemClock.elapsedRealtime()
                    frames += f.copyOf(n)
                    if (rms > maxOf(200.0, floor * 3, peak * 0.15)) { voiced++; lastVoice = now; peak = max(peak, rms) }
                    else if (voiced == 0) { floor = 0.9 * floor + 0.1 * min(rms, floor * 2); while (frames.size > 4) frames.removeAt(0) }
                    when {
                        voiced == 0 && now - started > 7000 -> break                        // nothing said
                        voiced in 1..3 && now - lastVoice > 800 -> { voiced = 0; peak = 0.0 } // a click, not speech
                        voiced >= 4 && now - lastVoice > 900 -> break                       // pause: done
                        now - started > 25_000 -> break
                    }
                }
            } finally { rec.stop(); rec.release() }
            android.util.Log.i("Jarvis", "mic: ${frames.size} frames, voiced=$voiced peak=${peak.toInt()} floor=${floor.toInt()}")
            if (macListen == MAC_CANCEL) return@thread
            macListen = MAC_OFF
            if (voiced < 4) { runOnUiThread { conversing = false; setState(State.IDLE) }; return@thread }
            runOnUiThread { status.text = "Transcribing…" }
            val pcm = ByteArrayOutputStream()
            for (f in frames) for (v in f) { pcm.write(v.toInt() and 0xff); pcm.write(v.toInt() shr 8 and 0xff) }
            val text = try {
                val c = open("/transcribe").apply { requestMethod = "POST"; doOutput = true; setRequestProperty("Content-Type", "application/octet-stream") }
                c.outputStream.use { it.write(pcm.toByteArray()) }
                JSONObject(c.inputStream.bufferedReader().readText()).optString("text").trim()
            } catch (e: Exception) { "" }
            runOnUiThread { if (text.isEmpty()) { conversing = false; setState(State.IDLE) } else ask(text) }
        }
    }

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermissions(); return
        }
        if (serverUrl.isEmpty()) { showSettings(); return }
        tts.stop()
        val options = recognizerOptions()
        val option = options[recognizerChoice.coerceAtMost(options.lastIndex)]
        if (option == "mac") return listenWithMac()
        val r = recognizer ?: makeRecognizer(option).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {
                setState(State.LISTENING)
                handler.postDelayed(watchdog, 15_000)
            }
            override fun onPartialResults(p: Bundle?) {
                handler.removeCallbacks(watchdog)
                p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { status.text = "“$it”" }
            }
            override fun onResults(p: Bundle?) {
                handler.removeCallbacks(watchdog)
                val text = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (text.isEmpty()) { conversing = false; setState(State.IDLE) } else { recognizerProven = true; ask(text) }
            }
            override fun onError(error: Int) {
                handler.removeCallbacks(watchdog)
                // 12/13 = language not supported / not downloaded (API 31 constants): move on to the next recognizer
                if (error in setOf(12, 13, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED) || (conversing && !recognizerProven)) {
                    recognizer?.destroy(); recognizer = null
                    return nextRecognizer()
                }
                conversing = false
                setState(State.IDLE)
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && error != SpeechRecognizer.ERROR_CLIENT) {
                    status.text = "Speech recognition error $error"
                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
                        recognizer?.destroy(); recognizer = null
                    }
                }
            }
            override fun onBeginningOfSpeech() = handler.removeCallbacks(watchdog)
            override fun onRmsChanged(rms: Float) { micLevel = ((rms + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        r.startListening(recognizeIntent())
    }

    // ---------------------------------------------------------------- talking to the Mac

    private fun device() = JSONObject()
        .put("platform", "Android ${Build.VERSION.RELEASE}")
        .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
        .put("time", ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm xxx")))
        .put("actions", JSONArray(Actions.supported))

    private fun open(path: String) = (URL(serverUrl + path).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000
        readTimeout = 120_000
        setRequestProperty("Authorization", "Bearer $token")
        setRequestProperty("Content-Type", "application/json")
    }

    private fun post(path: String, body: JSONObject): HttpURLConnection = open(path).apply {
        requestMethod = "POST"; doOutput = true
        outputStream.use { it.write(body.toString().toByteArray()) }
    }

    private fun cancelTurn() {
        if (macListen == MAC_RECORDING) macListen = MAC_CANCEL
        turnId++
        conn?.disconnect()
        conn = null
        turnDone = true
    }

    private fun ask(text: String) {
        cancelTurn()
        val my = turnId
        turnDone = false
        replyOpen = false
        offline = false
        log("you", text)
        setState(State.THINKING)
        val device = device()
        thread {
            try {
                val c = post("/ask", JSONObject().put("text", text).put("device", device))
                conn = c
                when (c.responseCode) {
                    200 -> {}
                    401 -> throw Exception("The Mac rejected the pairing token. Scan the QR code again.")
                    else -> throw Exception("The Mac answered ${c.responseCode}.")
                }
                c.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (my != turnId) break
                        val ev = JSONObject(line)
                        when (ev.optString("type")) {
                            "text" -> ev.getString("text").let { t -> runOnUiThread { if (my == turnId) { if (replyOpen) appendReply(t) else { log("jarvis", t); replyOpen = true }; speak(t) } } }
                            "activity" -> {
                                val name = ev.optString("name").removePrefix("mcp__phone__")
                                val label = mapOf("Bash" to "SHELL", "Read" to "READ", "Write" to "WRITE", "Edit" to "EDIT", "WebSearch" to "WEB",
                                    "WebFetch" to "WEB", "Grep" to "SEARCH", "Glob" to "FIND", "phone" to "PHONE")[name] ?: name.uppercase()
                                val detail = ev.optString("detail").take(80)
                                runOnUiThread { if (my == turnId) { log("act", "$label  $detail"); replyOpen = false } }
                            }
                            "tool" -> {
                                val action = ev.getString("action")
                                runOnUiThread { if (my == turnId) status.text = "Using $action…" }
                                val (result, err) = Actions.run(this, action, ev.optJSONObject("args") ?: JSONObject())
                                post("/tool_result", JSONObject().put("id", ev.getString("id")).put("result", result).put("error", err)).responseCode
                            }
                            "done" -> break
                        }
                    }
                }
            } catch (e: Exception) {
                if (my == turnId) runOnUiThread {
                    val msg = e.message?.takeIf { it.startsWith("The Mac") } ?: "Can't reach your Mac at $serverUrl. Is `jarvis.py --serve` running?"
                    offline = e.message?.startsWith("The Mac") != true
                    log("system", msg)
                    conversing = false
                    speak("I can't reach your Mac, sir.")
                }
            } finally {
                runOnUiThread { if (my == turnId) { turnDone = true; maybeFollowUp() } }
            }
        }
    }

    private fun speak(text: String) {
        tts.language = if (text.any { it in 'а'..'я' || it in 'А'..'Я' }) Locale("ru") else Locale.UK
        speaking++
        setState(State.SPEAKING)
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "u${System.nanoTime()}")
    }

    private fun maybeFollowUp() {
        if (!turnDone || speaking > 0 || state == State.LISTENING) return
        speaking = 0
        if (conversing && hasWindowFocus()) listen() else setState(State.IDLE)
    }

    private fun testConnection() = thread {
        val msg = try {
            when (open("/health").responseCode) { 200 -> "Connected to your Mac."; 401 -> "Wrong token."; else -> "Unexpected reply." }
        } catch (e: Exception) { "Can't reach $serverUrl (${e.message})." }
        runOnUiThread { log("system", msg) }
    }

    // ---------------------------------------------------------------- setup

    private fun askPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS, Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR, Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    private fun showSetupHelp() = log("system", """
        Welcome. To set up Jarvis:
        1. On your Mac run:  uv run jarvis.py --serve
        2. Scan the QR code it prints with this phone's camera (or tap ⚙ and type the URL and token).
        3. In ⚙, make Jarvis your default assistant, so the assist gesture (long-press power or swipe up from a corner) opens it.
        4. Optional: turn on screen control and notification access in ⚙ so Jarvis can operate other apps and read notifications.
    """.trimIndent())

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val url = EditText(this).apply { hint = "http://192.168.1.10:8765"; setText(serverUrl); inputType = InputType.TYPE_TEXT_VARIATION_URI }
        val tok = EditText(this).apply { hint = "token"; setText(token); inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT }
        box.addView(TextView(this).apply { text = "Mac server URL" }); box.addView(url)
        box.addView(TextView(this).apply { text = "Token" }); box.addView(tok)
        fun link(label: String, intent: Intent) = box.addView(Button(this).apply {
            text = label; isAllCaps = false
            setOnClickListener { runCatching { startActivity(intent) }.onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
        })
        link("Make Jarvis the default assistant", Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        link("Screen control (Accessibility)", Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        link("Notification access", Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        link("App permissions", Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        AlertDialog.Builder(this)
            .setTitle("Jarvis settings")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("url", url.text.toString().trim()).putString("token", tok.text.toString().trim()).apply()
                testConnection()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    companion object {
        const val MAC_OFF = 0
        const val MAC_RECORDING = 1
        const val MAC_FINISH = 2
        const val MAC_CANCEL = 3
        val BG = Color.parseColor("#02070C")
        val TEXT = Color.parseColor("#D6F4FC")
        val MUTED = Color.parseColor("#86B9C9")
        val ACCENT = Color.parseColor("#63E3FF")
        val LINE = Color.parseColor("#4063E3FF")
        val AMBER = Color.parseColor("#FFB648")
        val RED = Color.parseColor("#EF4444")
    }
}
