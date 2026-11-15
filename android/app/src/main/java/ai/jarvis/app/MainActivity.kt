package ai.jarvis.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread

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
            recognizerChoice++
            listen()
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

    private fun buildUi() {
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "JARVIS"; setTextColor(ACCENT); textSize = 22f; typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
            letterSpacing = 0.3f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply {
            text = "⚙"; textSize = 20f; setTextColor(MUTED); background = null
            contentDescription = "Settings"
            setOnClickListener { showSettings() }
        })
        root.addView(header)

        status = TextView(this).apply { setTextColor(MUTED); textSize = 14f; text = "Tap the mic or use the assist gesture" }
        root.addView(status)

        logView = TextView(this).apply { setTextColor(TEXT); textSize = 16f; setLineSpacing(0f, 1.2f); setTextIsSelectable(true) }
        scroll = ScrollView(this).apply { addView(logView); setPadding(0, dp(12), 0, dp(12)) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Type to Jarvis…"; setHintTextColor(MUTED); setTextColor(TEXT); textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, _, _ -> sendTyped(); true }
        }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        mic = Button(this).apply {
            text = "●"; textSize = 26f; setTextColor(Color.WHITE)
            contentDescription = "Talk to Jarvis"
            setOnClickListener { onMic() }
        }
        row.addView(mic, LinearLayout.LayoutParams(dp(72), dp(72)).apply { leftMargin = dp(12) })
        root.addView(row)
        setContentView(root)
        setState(State.IDLE)
    }

    private fun setState(s: State) {
        state = s
        val (color, label) = when (s) {
            State.IDLE -> ACCENT to "Tap the mic or use the assist gesture"
            State.LISTENING -> RED to "Listening…"
            State.THINKING -> AMBER to "Thinking…"
            State.SPEAKING -> ACCENT to "Speaking — tap to interrupt"
        }
        mic.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        status.text = label
    }

    private fun log(who: String, text: String) {
        val prefix = when (who) { "you" -> "› "; "jarvis" -> ""; else -> "· " }
        logView.append((if (logView.text.isEmpty()) "" else "\n") + prefix + text + if (who == "you") "\n" else "")
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
            State.LISTENING -> { conversing = false; recognizer?.stopListening() }
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
     *  the assistant). "activity" = Google's voice-input screen, which works on any phone with the Google app. */
    private var recognizerChoice = 0

    private fun recognizerOptions(): List<String> {
        val services = packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo }.filter { it.packageName != packageName }
            .sortedBy { if (it.packageName == "com.google.android.googlequicksearchbox") 0 else 1 }
            .map { "${it.packageName}/${it.name}" }
        val onDevice = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) listOf("ondevice") else emptyList()
        return services + onDevice + "activity"
    }

    private fun makeRecognizer(option: String): SpeechRecognizer =
        if (option == "ondevice" && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        else SpeechRecognizer.createSpeechRecognizer(this, ComponentName.unflattenFromString(option))

    private fun recognizeIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VOICE_SCREEN) return
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
        if (resultCode == RESULT_OK && text.isNotEmpty()) ask(text) else { conversing = false; setState(State.IDLE) }
    }

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermissions(); return
        }
        if (serverUrl.isEmpty()) { showSettings(); return }
        tts.stop()
        val options = recognizerOptions()
        val option = options[recognizerChoice.coerceAtMost(options.lastIndex)]
        if (option == "activity") {
            setState(State.LISTENING)
            @Suppress("DEPRECATION") startActivityForResult(recognizeIntent(), VOICE_SCREEN)
            return
        }
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
                if (text.isEmpty()) { conversing = false; setState(State.IDLE) } else ask(text)
            }
            override fun onError(error: Int) {
                handler.removeCallbacks(watchdog)
                // 12/13 = language not supported / not downloaded (API 31 constants): move on to the next recognizer
                if (error in setOf(12, 13, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED)) {
                    recognizer?.destroy(); recognizer = null
                    recognizerChoice++
                    return listen()
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
            override fun onRmsChanged(rms: Float) {}
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
        turnId++
        conn?.disconnect()
        conn = null
        turnDone = true
    }

    private fun ask(text: String) {
        cancelTurn()
        val my = turnId
        turnDone = false
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
                            "text" -> ev.getString("text").let { t -> runOnUiThread { if (my == turnId) { log("jarvis", t); speak(t) } } }
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
        const val VOICE_SCREEN = 42
        val BG = Color.parseColor("#0B1220")
        val TEXT = Color.parseColor("#E2E8F0")
        val MUTED = Color.parseColor("#94A3B8")
        val ACCENT = Color.parseColor("#0EA5E9")
        val AMBER = Color.parseColor("#F59E0B")
        val RED = Color.parseColor("#EF4444")
    }
}
