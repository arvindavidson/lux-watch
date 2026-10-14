package com.luxwatch.gadget

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.luxwatch.gadget.avatar.AvatarCatalog
import com.luxwatch.gadget.avatar.AvatarMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * One Muse connection for the whole process, shared by the talk screen and the
 * watch face. While Lux is the watch face the process stays alive, so the
 * connection is warmed up when the screen turns on (before you tap).
 *
 * With "Messages from Muse" on it stays connected and reconnects on its own,
 * so Muse can reach the watch (watch.notify); off (the default), it
 * disconnects after a couple of minutes with the screen off, to save battery.
 */
object LuxRuntime {
    private const val TAG = "LuxRuntime"
    private const val IDLE_DISCONNECT_MS = 2 * 60_000L
    private const val PREF_STAY = "stay_connected"
    private val BUSY = setOf(AvatarMode.LISTENING, AvatarMode.THINKING, AvatarMode.SPEAKING)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var storage: GadgetStorage? = null
    private var connection: GadgetConnection? = null
    private var idle: Job? = null
    private var keeper: Job? = null

    /** elapsedRealtime of the last message from Muse (0 = none), for the avatar's reaction. */
    @Volatile var messageAt = 0L
        private set

    /**
     * Set by the talk screen while it's showing: shows (and maybe speaks) a
     * message from Muse and returns true, or false if it can't right now.
     */
    var onMessage: ((title: String, text: String, speak: Boolean) -> Boolean)? = null

    /** elapsedRealtime when an avatar import last started (by command or from a reply). */
    @Volatile var avatarImportAt = 0L

    /** Set by the talk screen: the avatar list changed (Muse sent its clips). */
    var onAvatarsChanged: (() -> Unit)? = null

    /** Muse's clips are ready: make them the avatar everywhere. */
    fun avatarImported(context: Context, name: String) {
        AvatarCatalog.refresh()
        prefs(context).edit().putString("avatar", name).apply()
        onAvatarsChanged?.invoke()
    }

    private var history: ConversationLog? = null

    /** Recent exchanges and Muse messages, for the history page. */
    @Synchronized
    fun history(context: Context): ConversationLog =
        history ?: ConversationLog(java.io.File(context.applicationContext.filesDir, "history.jsonl"))
            .also { history = it }

    @Synchronized
    fun storage(context: Context): GadgetStorage =
        storage ?: GadgetStorage(context.applicationContext).also { storage = it }

    @Synchronized
    fun connection(context: Context): GadgetConnection = connection ?: GadgetConnection(
        storage(context),
        commands = LuxCommands.specs,
        onInvoke = commands(context),
    ).also { connection = it }

    private var commands: LuxCommands? = null

    /** What runs Muse's commands; also imports an avatar from links in a reply. */
    @Synchronized
    fun commands(context: Context): LuxCommands =
        commands ?: LuxCommands(context.applicationContext).also { commands = it }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("lux_prefs", Context.MODE_PRIVATE)

    /** Off by default: staying connected all day costs battery. */
    fun stayConnected(context: Context): Boolean = prefs(context).getBoolean(PREF_STAY, false)

    fun setStayConnected(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(PREF_STAY, on).apply()
        if (on) {
            keepConnected(context)
        } else {
            keeper?.cancel()
            keeper = null
            coolDown(context)
        }
    }

    /** elapsedRealtime when Lux was last open (0 = not since the process started). */
    @Volatile private var lastUsedAt = 0L
    private const val RECENT_USE_MS = 30 * 60_000L

    /** Lux is open (or just closed): wrist raises for the next while warm the connection up. */
    fun markUsed() {
        lastUsedAt = SystemClock.elapsedRealtime()
    }

    /**
     * The wrist came up: connect ahead of a tap only if Lux was used in the last
     * 30 minutes (or Messages from Muse keeps it connected anyway). Otherwise
     * the tap connects, while you speak.
     */
    fun warmUpIfRecent(context: Context) {
        val recent = lastUsedAt != 0L && SystemClock.elapsedRealtime() - lastUsedAt < RECENT_USE_MS
        if (recent || stayConnected(context)) warmUp(context)
    }

    /** The screen is on or Lux is open: get the connection ready now. */
    fun warmUp(context: Context) {
        idle?.cancel()
        idle = null
        val c = connection(context)
        if (c.isPaired && !c.isReady) {
            Log.i(TAG, "warming up the Muse connection")
            scope.launch { c.ensureConnected() }
        }
        keepConnected(context)
    }

    /** The screen is off and Lux isn't open: disconnect if it stays that way (unless staying connected). */
    fun coolDown(context: Context) {
        if (stayConnected(context)) return
        val c = connection(context)
        idle?.cancel()
        idle = scope.launch {
            delay(IDLE_DISCONNECT_MS)
            // Never cut off a turn that's still running.
            val busy = LuxStatus.mode() in BUSY
            if (!busy && c.isReady) {
                Log.i(TAG, "idle: closing the Muse connection")
                c.disconnect()
            }
        }
    }

    /** Keeps the link up for Muse's messages: checks every 30 s, retries with backoff up to 5 min. */
    private fun keepConnected(context: Context) {
        if (keeper?.isActive == true || !stayConnected(context)) return
        val c = connection(context)
        keeper = scope.launch {
            var backoff = 5_000L
            while (isActive) {
                when {
                    !c.isPaired -> delay(60_000)
                    c.isReady -> {
                        backoff = 5_000L
                        delay(30_000)
                    }
                    c.ensureConnected() -> backoff = 5_000L
                    else -> {
                        Log.i(TAG, "reconnect failed; next try in ${backoff / 1000} s")
                        delay(backoff)
                        backoff = min(backoff * 3, 300_000L)
                    }
                }
            }
        }
    }

    /** Set by the watch face: redraw now (it otherwise redraws once a minute while still). */
    var onFaceNudge: (() -> Unit)? = null

    /** A message from Muse (watch.notify). Returns true if the talk screen showed it. */
    fun deliverMessage(title: String, text: String, speak: Boolean): Boolean {
        messageAt = SystemClock.elapsedRealtime()
        onFaceNudge?.invoke()
        return onMessage?.invoke(title, text, speak) == true
    }
}
