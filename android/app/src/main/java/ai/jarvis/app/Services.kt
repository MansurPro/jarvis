package ai.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Registered as the default digital assistant: the assist gesture opens Jarvis already listening. */
class AssistService : VoiceInteractionService()

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

class AssistSession(ctx: Context) : VoiceInteractionSession(ctx) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startAssistantActivity(
            Intent(context, MainActivity::class.java)
                .putExtra("listen", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        hide()
    }
}

/** Android requires an assistant to declare a recognizer. Jarvis itself uses the phone's real one. */
class StubRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback?) {}
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}

class NotificationReader : NotificationListenerService() {
    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { instance = null }

    companion object {
        var instance: NotificationReader? = null

        fun summary(): String {
            val svc = instance ?: return "Notification access is off. Turn on Jarvis in Settings → Notifications → Device & app notifications."
            val pm = svc.packageManager
            return svc.activeNotifications.orEmpty().mapNotNull { n ->
                val e = n.notification.extras
                val title = e.getCharSequence("android.title")?.toString().orEmpty()
                val text = e.getCharSequence("android.bigText")?.toString() ?: e.getCharSequence("android.text")?.toString().orEmpty()
                if (title.isBlank() && text.isBlank()) return@mapNotNull null
                val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(n.packageName, 0)) }.getOrDefault(n.packageName)
                "$app: $title — $text".take(300)
            }.ifEmpty { listOf("No notifications.") }.joinToString("\n")
        }
    }
}

/** Screen control for other apps: read what's on screen, tap, type, scroll, back, home. */
class JarvisAccessibility : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        var instance: JarvisAccessibility? = null
        private const val OFF = "Screen control is off. Turn on \"Jarvis screen control\" in Settings → Accessibility."

        private fun root(): AccessibilityNodeInfo? = instance?.rootInActiveWindow

        fun read(): String {
            val svc = instance ?: return OFF
            val r = root() ?: return "Can't read the screen right now."
            val out = StringBuilder("App: ${r.packageName}\n")
            fun walk(n: AccessibilityNodeInfo?, depth: Int) {
                if (n == null || depth > 40 || out.length > 6000) return
                val label = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
                if (label.isNotEmpty() || n.isEditable) {
                    val kind = when {
                        n.isEditable -> "[field${if (n.isFocused) ", focused" else ""}]"
                        n.isClickable -> "[button]"
                        n.isCheckable -> "[${if (n.isChecked) "on" else "off"}]"
                        else -> ""
                    }
                    out.append("$kind $label\n".trimStart())
                }
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
            }
            walk(r, 0)
            if (r.packageName == svc.packageName) out.append("(This is Jarvis itself. Open another app first.)")
            return out.toString()
        }

        fun tap(text: String): String {
            val svc = instance ?: return OFF
            val r = root() ?: return "Can't read the screen right now."
            val node = r.findAccessibilityNodeInfosByText(text).firstOrNull()
                ?: return "Nothing on screen matches \"$text\"."
            var n: AccessibilityNodeInfo? = node
            while (n != null && !n.isClickable) n = n.parent
            if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Tapped \"$text\"."
            val b = Rect().also { node.getBoundsInScreen(it) } // not clickable through the tree: tap its centre
            val path = Path().apply { moveTo(b.exactCenterX(), b.exactCenterY()) }
            svc.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null)
            return "Tapped \"$text\"."
        }

        fun type(text: String): String {
            instance ?: return OFF
            val r = root() ?: return "Can't read the screen right now."
            val field = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(r) ?: return "No text field on screen."
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            return if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "Typed it." else "Couldn't type there."
        }

        fun scroll(direction: String): String {
            instance ?: return OFF
            val r = root() ?: return "Can't read the screen right now."
            val node = findScrollable(r) ?: return "Nothing scrollable on screen."
            val action = if (direction.lowercase() == "up") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            return if (node.performAction(action)) "Scrolled $direction." else "Can't scroll further."
        }

        fun global(action: Int): String {
            val svc = instance ?: return OFF
            return if (svc.performGlobalAction(action)) "Done." else "Couldn't do that."
        }

        private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.isEditable) return n
            for (i in 0 until n.childCount) findEditable(n.getChild(i))?.let { return it }
            return null
        }

        private fun findScrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.isScrollable) return n
            for (i in 0 until n.childCount) findScrollable(n.getChild(i))?.let { return it }
            return null
        }
    }
}
