package ai.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.view.KeyEvent
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Everything Claude can do on this phone. Each action returns text for Claude; exceptions become errors. */
object Actions {
    val supported = listOf(
        "send_sms", "call", "contacts", "calendar_events", "calendar_add", "alarm", "timer", "location",
        "open_app", "open_url", "navigate", "media", "volume", "flashlight", "battery", "clipboard_get",
        "clipboard_set", "notifications", "read_sms", "screen_read", "screen_tap", "screen_type",
        "screen_scroll", "screen_back", "screen_home",
    )

    private val torchOn = mutableMapOf<String, Boolean>()

    /** Returns (result, isError). */
    fun run(ctx: Context, action: String, a: JSONObject): Pair<String, Boolean> = try {
        Pair(dispatch(ctx, action, a), false)
    } catch (e: SecurityException) {
        Pair("Permission missing for $action. Ask the user to allow it in the Jarvis app settings. (${e.message})", true)
    } catch (e: Exception) {
        Pair("$action failed: ${e.message ?: e.javaClass.simpleName}", true)
    }

    private fun start(ctx: Context, intent: Intent) =
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun dispatch(ctx: Context, action: String, a: JSONObject): String = when (action) {
        "send_sms" -> {
            val to = a.getString("to"); val body = a.getString("body")
            val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
            sms.sendMultipartTextMessage(to, null, sms.divideMessage(body), null, null)
            "Sent to $to."
        }
        "call" -> { start(ctx, Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(a.getString("number"))))); "Calling." }
        "contacts" -> contacts(ctx, a.optString("query"))
        "calendar_events" -> calendarEvents(ctx, a.optInt("days", 1))
        "calendar_add" -> calendarAdd(ctx, a)
        "alarm" -> {
            start(ctx, Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, a.getInt("hour"))
                .putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute", 0))
                .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Jarvis"))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            "Alarm set."
        }
        "timer" -> {
            start(ctx, Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, a.getInt("seconds"))
                .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Jarvis"))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            "Timer started."
        }
        "location" -> location(ctx)
        "open_app" -> openApp(ctx, a.getString("name"))
        "open_url" -> { start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(a.getString("url")))); "Opened." }
        "navigate" -> {
            val q = Uri.encode(a.getString("destination"))
            runCatching { start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q"))) }
                .getOrElse { start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q"))) }
            "Navigation started."
        }
        "media" -> {
            val code = when (a.getString("command").lowercase()) {
                "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                "pause", "stop" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "previous", "back" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }
            val am = ctx.getSystemService(AudioManager::class.java)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            "Done."
        }
        "volume" -> {
            val am = ctx.getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (a.getInt("level").coerceIn(0, 100) * max + 50) / 100, 0)
            "Volume set to ${a.getInt("level")}%."
        }
        "flashlight" -> {
            val cm = ctx.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            val on = if (a.has("on")) a.getBoolean("on") else !(torchOn[id] ?: false)
            cm.setTorchMode(id, on); torchOn[id] = on
            "Flashlight ${if (on) "on" else "off"}."
        }
        "battery" -> {
            val bm = ctx.getSystemService(BatteryManager::class.java)
            "Battery ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%, ${if (bm.isCharging) "charging" else "not charging"}."
        }
        "clipboard_get" -> ctx.getSystemService(ClipboardManager::class.java).primaryClip
            ?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: "Clipboard is empty."
        "clipboard_set" -> {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Jarvis", a.getString("text")))
            "Copied."
        }
        "notifications" -> NotificationReader.summary()
        "read_sms" -> readSms(ctx, a.optInt("count", 5))
        "screen_read" -> JarvisAccessibility.read()
        "screen_tap" -> JarvisAccessibility.tap(a.getString("text"))
        "screen_type" -> JarvisAccessibility.type(a.getString("text"))
        "screen_scroll" -> JarvisAccessibility.scroll(a.optString("direction", "down"))
        "screen_back" -> JarvisAccessibility.global(AccessibilityService.GLOBAL_ACTION_BACK)
        "screen_home" -> JarvisAccessibility.global(AccessibilityService.GLOBAL_ACTION_HOME)
        else -> throw IllegalArgumentException("unknown action on Android")
    }

    private fun contacts(ctx: Context, query: String): String {
        val cols = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        val out = mutableListOf<String>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols,
            "${cols[0]} LIKE ?", arrayOf("%$query%"), "${cols[0]} ASC"
        )?.use { c -> while (c.moveToNext() && out.size < 15) out += "${c.getString(0)}: ${c.getString(1)}" }
        return out.distinct().ifEmpty { listOf("No contacts match \"$query\".") }.joinToString("\n")
    }

    private val fmt = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")

    private fun calendarEvents(ctx: Context, days: Int): String {
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString()).appendPath((now + days.coerceAtLeast(1) * 86_400_000L).toString()).build()
        val cols = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.EVENT_LOCATION)
        val out = mutableListOf<String>()
        ctx.contentResolver.query(uri, cols, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && out.size < 30) {
                val start = Instant.ofEpochMilli(c.getLong(1)).atZone(ZoneId.systemDefault()).format(fmt)
                out += "$start ${c.getString(0)}" + (c.getString(2)?.takeIf { it.isNotBlank() }?.let { " @ $it" } ?: "")
            }
        }
        return out.ifEmpty { listOf("Nothing on the calendar.") }.joinToString("\n")
    }

    private fun calendarAdd(ctx: Context, a: JSONObject): String {
        val zone = ZoneId.systemDefault()
        val start = LocalDateTime.parse(a.getString("start")).atZone(zone)
        val end = a.optString("end").takeIf { it.isNotBlank() }?.let { LocalDateTime.parse(it).atZone(zone) } ?: start.plusHours(1)
        val calId = ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}", null,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC"
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return "No writable calendar on this phone."
        ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, a.getString("title"))
            put(CalendarContract.Events.DTSTART, start.toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, end.toInstant().toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            a.optString("location").takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        })
        return "Added \"${a.getString("title")}\" on ${start.format(fmt)}."
    }

    @SuppressLint("MissingPermission")
    private fun location(ctx: Context): String {
        val lm = ctx.getSystemService(LocationManager::class.java)
        var loc: Location? = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        if (loc == null || System.currentTimeMillis() - loc.time > 5 * 60_000) {
            val latch = CountDownLatch(1)
            val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            lm.getCurrentLocation(provider, null, ctx.mainExecutor) { l -> if (l != null) loc = l; latch.countDown() }
            latch.await(15, TimeUnit.SECONDS)
        }
        val l = loc ?: return "Location unavailable. Is location turned on?"
        @Suppress("DEPRECATION")
        val addr = runCatching { Geocoder(ctx).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()?.getAddressLine(0) }.getOrNull()
        return "%.5f, %.5f (±%.0f m)%s".format(l.latitude, l.longitude, l.accuracy, addr?.let { " — $it" } ?: "")
    }

    private fun openApp(ctx: Context, name: String): String {
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val q = name.lowercase().trim()
        val best = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: return "No app called \"$name\". Installed apps include: " +
                apps.map { it.loadLabel(pm).toString() }.sorted().take(60).joinToString(", ")
        start(ctx, pm.getLaunchIntentForPackage(best.activityInfo.packageName)!!)
        return "Opened ${best.loadLabel(pm)}."
    }

    private fun readSms(ctx: Context, count: Int): String {
        val out = mutableListOf<String>()
        ctx.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("address", "body", "date"), null, null, "date DESC")?.use { c ->
            while (c.moveToNext() && out.size < count.coerceIn(1, 30)) {
                val at = Instant.ofEpochMilli(c.getLong(2)).atZone(ZoneId.systemDefault()).format(fmt)
                out += "$at from ${c.getString(0)}: ${c.getString(1)}".take(400)
            }
        }
        return out.ifEmpty { listOf("No texts.") }.joinToString("\n")
    }
}
