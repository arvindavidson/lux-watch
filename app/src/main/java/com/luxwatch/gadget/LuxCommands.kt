package com.luxwatch.gadget

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.luxwatch.gadget.avatar.ClipImporter
import com.luxwatch.gadget.noise.CommandHandler
import com.luxwatch.gadget.noise.CommandParams
import com.luxwatch.gadget.noise.CommandResult
import com.luxwatch.gadget.noise.CommandSpec
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * What Muse can ask the watch to do (link.invoke). Muse sees these in
 * link.register and calls them on its own, e.g. "remind me at 5 on my watch".
 */
class LuxCommands(private val context: Context) : CommandHandler {
    companion object {
        const val NOTIFY = "watch.notify"
        const val HEALTH = "device.health"
        const val AVATAR = "avatar.set_clips"
        /** As on Meta's boards: a picture from a file on Muse's VM, or from a link. */
        const val DRAW_FILE = "display.draw_file"
        const val DRAW_URL = "display.draw_url"
        private const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
        private const val TAG = "LuxCommands"
        private const val CHANNEL = "muse_messages"

        val specs = listOf(
            CommandSpec(
                name = NOTIFY,
                description = "Show a message on the user's Galaxy Watch: it buzzes and shows the text, " +
                    "and reads it aloud if they're looking at Lux. Use it for reminders, alerts and anything " +
                    "the user asked to be told on their watch. Keep it short; the screen is small and round.",
                required = listOf(CommandSpec.Param("text", "string", "The message, a sentence or two.")),
                optional = listOf(
                    CommandSpec.Param("title", "string", "A few words above the message. Default: Lux."),
                    CommandSpec.Param("speak", "boolean", "Read it aloud when the user is looking at Lux. Default true."),
                ),
            ),
            CommandSpec(
                name = AVATAR,
                description = "Put your own avatar on the user's watch, in place of the default one. For " +
                    "each clip give the path of the video file on your computer (the watch reads it over its " +
                    "own connection to you; no public link needed) or an HTTPS link (MP4, up to 30 MB; the " +
                    "first 10 s are used; a white or plain light background is fine, the watch turns it " +
                    "black). Only idle is " +
                    "required; states without a clip use idle. The watch downloads the clips before it " +
                    "answers, then prepares them (a minute or two) and shows them on its watch face and " +
                    "talk screen.",
                required = listOf(clip("idle", "the clip shown while idle and connecting")),
                optional = listOf(
                    CommandSpec.Param("name", "string", "Your avatar's name, shown in the watch's settings."),
                    clip("listening", "the clip shown while the user is talking"),
                    clip("thinking", "the clip shown while you work on a reply"),
                    clip("speaking", "the clip shown while your reply is read out"),
                    clip("happy", "a short celebration, played once when something good happens"),
                    clip("error", "the clip shown when something went wrong"),
                ),
                timeoutMs = 300_000,
            ),
            CommandSpec(
                name = DRAW_FILE,
                description = "Show a picture on the user's watch, full screen until they tap it: the path " +
                    "of an image file on your computer (PNG, JPEG, WebP or GIF, up to 10 MB). The watch reads " +
                    "it over its own connection to you. The screen is round, 450 by 450 pixels: keep what " +
                    "matters in the middle.",
                required = listOf(CommandSpec.Param("path", "string", "Absolute path of the image file.")),
                optional = listOf(CommandSpec.Param("caption", "string", "A few words shown under the picture.")),
                timeoutMs = 60_000,
            ),
            CommandSpec(
                name = DRAW_URL,
                description = "Show a picture from an HTTPS link on the user's watch, full screen until they " +
                    "tap it (PNG, JPEG, WebP or GIF, up to 10 MB; round screen, 450 by 450 pixels).",
                required = listOf(CommandSpec.Param("url", "string", "HTTPS link to the image.")),
                optional = listOf(CommandSpec.Param("caption", "string", "A few words shown under the picture.")),
                timeoutMs = 60_000,
            ),
            CommandSpec(
                name = HEALTH,
                description = "Watch status: battery percent, whether it's charging, the watch model and the Lux app version.",
            ),
        )
    }

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun invoke(command: String, paramsJson: String): CommandResult {
        val result = when (command) {
            NOTIFY -> notify(paramsJson)
            HEALTH -> health()
            AVATAR -> setAvatar(paramsJson)
            DRAW_FILE -> drawImage(paramsJson, fromVm = true)
            DRAW_URL -> drawImage(paramsJson, fromVm = false)
            else -> CommandResult.Error("unsupported command: $command")
        }
        // The command and the outcome only: params can hold personal text.
        Log.i(TAG, "Muse called $command: " + if (result is CommandResult.Error) "error: ${result.message}" else "ok")
        return result
    }

    private suspend fun notify(params: String): CommandResult {
        val text = CommandParams.string(params, "text")?.trim().orEmpty()
        if (text.isEmpty()) return CommandResult.Error("text is required")
        val title = CommandParams.string(params, "title")?.trim()?.takeIf { it.isNotEmpty() } ?: "Lux"
        val speak = CommandParams.bool(params, "speak") ?: true
        withContext(Dispatchers.Main) {
            LuxRuntime.history(context).add(ConversationLog.Entry(System.currentTimeMillis(), null, text, title))
        }
        val shownInApp = withContext(Dispatchers.Main) { LuxRuntime.deliverMessage(title, text, speak) }
        if (shownInApp) return CommandResult.Ok("""{"shown":true,"where":"app"}""")
        if (!postNotification(title, text)) {
            return CommandResult.Error("notifications from Lux are turned off on the watch")
        }
        return CommandResult.Ok("""{"shown":true,"where":"notification"}""")
    }

    private fun postNotification(title: String, text: String, picture: android.graphics.Bitmap? = null): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Messages from Muse", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_lux)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(
                if (picture != null) NotificationCompat.BigPictureStyle().bigPicture(picture).setSummaryText(text)
                else NotificationCompat.BigTextStyle().bigText(text),
            )
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /** Downloads the clips now (so bad links come back to Muse), then prepares them in the background. */
    private suspend fun setAvatar(params: String): CommandResult {
        val urls = ClipImporter.STATES.mapNotNull { state ->
            CommandParams.string(params, state)?.trim()?.takeIf { it.isNotEmpty() }?.let { state to it }
        }.toMap()
        val name = CommandParams.string(params, "name")?.trim()?.take(24)?.takeIf { it.isNotEmpty() } ?: "My Muse"
        val clips = try {
            importAvatar(name, urls)
        } catch (e: ClipImporter.ImportError) {
            return CommandResult.Error(e.message ?: "couldn't download the clips")
        }
        return CommandResult.Ok(
            JSONObject()
                .put("downloaded", org.json.JSONArray(clips))
                .put("status", "preparing; the avatar appears on the watch in a minute or two")
                .toString(),
        )
    }

    /**
     * Downloads the clips now (throws [ClipImporter.ImportError] on a bad link),
     * then prepares them in the background and says when they're ready.
     * Returns the states downloaded. Used by avatar.set_clips and when Muse
     * sends the links in a reply instead.
     */
    suspend fun importAvatar(name: String, urls: Map<String, String>): List<String> {
        LuxRuntime.avatarImportAt = android.os.SystemClock.elapsedRealtime()
        val importer = ClipImporter(context) { path, max -> LuxRuntime.connection(context).readVmFile(path, max) }
        val clips = importer.fetch(urls)
        background.launch {
            val message = try {
                importer.build(name, clips)
                withContext(Dispatchers.Main) { LuxRuntime.avatarImported(context, name) }
                "$name is on your watch now."
            } catch (e: Exception) {
                Log.w(TAG, "avatar import failed", e)
                "Couldn't use the avatar clips: ${e.message ?: "unknown error"}"
            }
            withContext(Dispatchers.Main) {
                if (!LuxRuntime.deliverMessage("Lux", message, speak = false)) postNotification("Lux", message)
            }
        }
        return clips.keys.toList()
    }

    /** display.draw_file / display.draw_url: fetch the picture, then show it (or post it as a notification). */
    private suspend fun drawImage(params: String, fromVm: Boolean): CommandResult {
        val where = CommandParams.string(params, if (fromVm) "path" else "url")?.trim().orEmpty()
        if (where.isEmpty()) return CommandResult.Error(if (fromVm) "path is required" else "url is required")
        val caption = CommandParams.string(params, "caption")?.trim()?.take(80)?.takeIf { it.isNotEmpty() }
        val bytes = try {
            if (fromVm) {
                if (!ClipImporter.isVmPath(where)) return CommandResult.Error("path must be an absolute path")
                LuxRuntime.connection(context).readVmFile(where, MAX_IMAGE_BYTES)
            } else {
                if (!where.startsWith("https://")) return CommandResult.Error("only https:// links work")
                withContext(Dispatchers.IO) { ImageFetch.download(where, MAX_IMAGE_BYTES) }
            }
        } catch (e: Exception) {
            return CommandResult.Error("couldn't get the picture: ${e.message ?: "unknown error"}")
        }
        val bitmap = withContext(Dispatchers.Default) { ImageFetch.decode(bytes, maxSide = 900) }
            ?: return CommandResult.Error("not a picture the watch can show")
        withContext(Dispatchers.Main) {
            LuxRuntime.history(context).add(
                ConversationLog.Entry(System.currentTimeMillis(), null, caption ?: "(a picture)", "Muse"),
            )
        }
        val shown = withContext(Dispatchers.Main) { LuxRuntime.deliverImage(bitmap, caption) }
        if (shown) return CommandResult.Ok("""{"shown":true,"where":"app","width":${bitmap.width},"height":${bitmap.height}}""")
        if (!postNotification("Muse", caption ?: "Sent you a picture", bitmap)) {
            return CommandResult.Error("notifications from Lux are turned off on the watch")
        }
        return CommandResult.Ok("""{"shown":true,"where":"notification"}""")
    }

    private fun health(): CommandResult {
        val battery = context.getSystemService(BatteryManager::class.java)
        val percent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = battery?.isCharging ?: false
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
        return CommandResult.Ok(
            """{"battery_percent":$percent,"charging":$charging,""" +
                """"model":${jsonString(Build.MODEL)},"android":${jsonString(Build.VERSION.RELEASE)},""" +
                """"app_version":${jsonString(version)}}""",
        )
    }

    private fun jsonString(s: String) = JSONObject.quote(s)
}

private fun clip(state: String, what: String) =
    CommandSpec.Param(state, "string", "Path on your computer, or HTTPS link, of $what.")
