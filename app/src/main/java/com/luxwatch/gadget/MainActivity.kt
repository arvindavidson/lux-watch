package com.luxwatch.gadget

import android.Manifest
import android.app.RemoteInput
import androidx.wear.input.RemoteInputIntentHelper
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.MaterialTheme
import com.luxwatch.gadget.avatar.AvatarMode
import com.luxwatch.gadget.avatar.AvatarArt
import com.luxwatch.gadget.avatar.AvatarCatalog
import com.luxwatch.gadget.avatar.AvatarLinks
import com.luxwatch.gadget.avatar.ClipImporter
import com.luxwatch.gadget.noise.SdkToken
import com.luxwatch.gadget.noise.VoiceNote
import com.luxwatch.gadget.ui.LuxApp
import com.luxwatch.gadget.ui.SettingsActions
import com.luxwatch.gadget.ui.SettingsUi
import com.luxwatch.gadget.ui.TalkUi
import com.luxwatch.gadget.ui.TokenActions
import com.luxwatch.gadget.ui.TokenScreen
import com.luxwatch.gadget.ui.TokenUi
import com.luxwatch.gadget.pairing.TokenTransfer
import com.luxwatch.gadget.voice.Endpointer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_SDK_TOKEN = "sdk_token"

        /** Start listening as soon as Lux opens (watch face, tile, complication). */
        const val EXTRA_TALK = "talk"

        /** Opened from the Lux watch face: go back to it after the reply. */
        const val EXTRA_FROM_FACE = "from_face"

        private const val RETURN_TO_FACE_MS = 3_000L
        private const val KEY_TOKEN_INPUT = "sdk_token"
        private const val AVATAR_REQUEST =
            "Please put your avatar on my watch: use this watch's avatar.set_clips command with HTTPS " +
                "links to your avatar video clips (idle, plus any of listening, thinking, speaking, happy " +
                "and error that you have) and your name. If you can't run the command, reply with only " +
                "the links instead, one per line as \"state: https://link\", using those state names, " +
                "and a line \"name: your name\"; the watch will fetch them itself."

        /** Muse stops listening to a voice note after 15 s, so stop and send there. */
        private const val MAX_RECORD_MS = VoiceNote.MAX_SECONDS * 1000L
        private const val ERROR_SHOW_MS = 4_000L
        private const val REPLY_LINGER_MS = 10_000L
        /** How long Settings → SDK token waits for the phone. */
        private const val TOKEN_WAIT_MS = 5 * 60_000L
    }

    private lateinit var tts: LuxTts
    private lateinit var storage: GadgetStorage
    private lateinit var connection: GadgetConnection
    private lateinit var backend: MuseLuxBackend
    private lateinit var haptics: Haptics
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ---- UI state ----
    private var status by mutableStateOf(TalkStatus.IDLE)
    private var paired by mutableStateOf(false)
    private var pairingError by mutableStateOf<String?>(null)
    /** Why pairing is needed again (Muse removed the watch or refused its tokens). */
    private var unpairedNote by mutableStateOf<String?>(null)
    private var linkState by mutableStateOf<GadgetConnection.State>(GadgetConnection.State.NotPaired)
    private var tokenSet by mutableStateOf(false)
    private var tokenNote by mutableStateOf<String?>(null)
    private var heard by mutableStateOf<String?>(null)
    private var reply by mutableStateOf<String?>(null)
    private var error by mutableStateOf<String?>(null)
    private var hapticsOn by mutableStateOf(true)
    private var happyAt by mutableLongStateOf(0L)
    private var museMessages by mutableStateOf(false)
    private var showTalk by mutableStateOf(0)
    private var history by mutableStateOf<List<ConversationLog.Entry>>(emptyList())
    private var volume by mutableStateOf(0)
    private var maxVolume by mutableStateOf(15)
    private var volumeShownAt by mutableLongStateOf(0L)
    private var voiceLabel by mutableStateOf("Default")
    private var speechRate by mutableStateOf(1f)
    private lateinit var avatars: List<AvatarArt>
    private var avatar by mutableStateOf<AvatarArt?>(null)
    /** Settings → SDK token is open (null = closed). */
    private var tokenUi by mutableStateOf<TokenUi?>(null)
    private var tokenReceiver: TokenReceiver? = null
    /** Received over Bluetooth, waiting for Save; never logged. */
    private var pendingToken: String? = null
    private var tokenTimeout: Job? = null

    private var capture: AudioCapture? = null
    private var autoStop: Job? = null
    private var turn: Job? = null
    private var clearError: Job? = null
    private var clearReply: Job? = null
    private var returnToFace: Job? = null
    private var fromFace = false

    private val stateListener: (GadgetConnection.State) -> Unit = { state -> runOnUiThread { linkState = state } }
    private val unpairedListener: () -> Unit = {
        runOnUiThread {
            paired = false
            unpairedNote = "Muse no longer knows this watch"
            requestPairing()
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else showError("Microphone permission needed")
        }

    private val tokenInputLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val text = result.data?.let { RemoteInput.getResultsFromIntent(it) }
                ?.getCharSequence(KEY_TOKEN_INPUT)?.toString() ?: return@registerForActivityResult
            val token = SdkToken.find(text)
            if (token == null) {
                tokenNote = "That isn't an SDK token \u2014 copy it again from gadgets.muse.ai"
                showError("Not a valid SDK token")
            } else {
                applySdkToken(token)
            }
        }

    private val tokenPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                startTokenReceiver()
            } else {
                tokenUi = TokenUi(TokenUi.Stage.WAITING, note = "Needs Bluetooth permission: type it instead")
            }
        }

    /** Settings → SDK token: wait for the phone's "Send token to watch" page over Bluetooth. */
    private fun openTokenScreen() {
        tokenUi = TokenUi(TokenUi.Stage.WAITING)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tokenTimeout?.cancel()
        tokenTimeout = scope.launch {
            delay(TOKEN_WAIT_MS)
            closeTokenScreen()
        }
        val missing = BlePairingServer.requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startTokenReceiver() else tokenPermissionLauncher.launch(missing.toTypedArray())
    }

    private fun startTokenReceiver() {
        if (tokenUi == null || tokenReceiver != null) return
        val r = TokenReceiver(
            this,
            onReceived = { token ->
                pendingToken = token
                tokenUi = TokenUi(TokenUi.Stage.CONFIRM, tail = token.takeLast(4))
                haptics.reply()
            },
            onInvalid = {
                tokenUi = TokenUi(TokenUi.Stage.WAITING, note = "That didn't come through: send it again")
            },
            onError = { message -> tokenUi = tokenUi?.copy(note = "$message: type it instead") },
        )
        try {
            r.start()
            tokenReceiver = r
        } catch (e: Exception) {
            r.stop()
            tokenUi = tokenUi?.copy(note = "${e.message ?: "Bluetooth error"}: type it instead")
        }
    }

    private fun saveReceivedToken() {
        val token = pendingToken ?: return
        pendingToken = null
        applySdkToken(token)
        tokenReceiver?.status = TokenTransfer.Status.SAVED
        tokenUi = TokenUi(TokenUi.Stage.SAVED)
        tokenTimeout?.cancel()
        // Long enough for the page to read "saved".
        tokenTimeout = scope.launch {
            delay(3_000)
            closeTokenScreen()
        }
    }

    private fun rejectReceivedToken() {
        pendingToken = null
        tokenReceiver?.status = TokenTransfer.Status.CANCELLED
        tokenUi = TokenUi(TokenUi.Stage.WAITING)
    }

    private fun closeTokenScreen() {
        tokenTimeout?.cancel()
        tokenTimeout = null
        tokenReceiver?.stop()
        tokenReceiver = null
        pendingToken = null
        tokenUi = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Asks for the SDK token on the watch: type it, or paste it with the phone's keyboard. */
    private fun requestTokenInput() {
        val input = RemoteInput.Builder(KEY_TOKEN_INPUT).setLabel("SDK token (mgst_\u2026)").build()
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(input))
        RemoteInputIntentHelper.putTitleExtra(intent, "Paste your SDK token")
        tokenInputLauncher.launch(intent)
    }

    private val blePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                startPairingServer()
            } else {
                pairingError = "Can't pair: Bluetooth permission denied. " +
                    "Allow Nearby devices for Lux Watch in Settings, then reopen."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = LuxTts(this)
        speechRate = tts.rate
        tts.onReady = { voiceLabel = tts.voiceLabel }
        storage = LuxRuntime.storage(this)
        connection = LuxRuntime.connection(this)
        haptics = Haptics(this)
        hapticsOn = haptics.enabled
        val prefs = getSharedPreferences("lux_prefs", MODE_PRIVATE)
        avatars = AvatarCatalog.all(this)
        museMessages = LuxRuntime.stayConnected(this)
        // Muse's messages arrive as notifications while Lux isn't open.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        avatar = AvatarCatalog.byName(this, prefs.getString("avatar", null))
        backend = MuseLuxBackend(
            connection, tts,
            onStatus = { s -> runOnUiThread { onStatus(s) } },
            onHeard = { h -> runOnUiThread { heard = h } },
            onReply = { r -> runOnUiThread { onReply(r) } },
            onError = { e -> runOnUiThread { showError(e) } },
        )
        consumeSdkTokenExtra(intent)
        linkState = connection.state
        connection.onStateChanged = stateListener
        connection.onUnpaired = unpairedListener
        paired = connection.isPaired
        LuxStatus.mode = modeSource
        LuxStatus.happyAt = { happyAt }
        setContent {
            MaterialTheme {
                val token = tokenUi
                BackHandler(enabled = token != null) { closeTokenScreen() }
                if (token != null) {
                    TokenScreen(
                        token,
                        TokenActions(
                            onSave = ::saveReceivedToken,
                            onReject = ::rejectReceivedToken,
                            onTypeInstead = {
                                closeTokenScreen()
                                requestTokenInput()
                            },
                            onClose = ::closeTokenScreen,
                        ),
                    )
                } else LuxApp(
                    talk = talkUi(),
                    avatar = avatar ?: avatars.first(),
                    settings = SettingsUi(
                        paired = paired,
                        link = linkLabel(linkState),
                        tokenSet = tokenSet,
                        haptics = hapticsOn,
                        version = appVersion,
                        volume = volume,
                        maxVolume = maxVolume,
                        avatarName = avatar?.name ?: "",
                        canChangeAvatar = avatars.size > 1,
                        museMessages = museMessages,
                        voice = voiceLabel,
                        speechRate = speechRate,
                    ),
                    actions = SettingsActions(
                        onReconnect = ::reconnect,
                        onVolumeDown = { adjustVolume(AudioManager.ADJUST_LOWER) },
                        onVolumeUp = { adjustVolume(AudioManager.ADJUST_RAISE) },
                        onHaptics = { on -> haptics.enabled = on; hapticsOn = on },
                        onRepair = ::repair,
                        onSetToken = ::openTokenScreen,
                        onGetAvatar = ::askMuseForAvatar,
                        onNextVoice = {
                            tts.nextVoice()
                            voiceLabel = tts.voiceLabel
                            sampleVoice()
                        },
                        onSpeechRate = { r ->
                            tts.rate = r
                            speechRate = tts.rate
                            sampleVoice()
                        },
                        onMuseMessages = { on ->
                            museMessages = on
                            LuxRuntime.setStayConnected(this, on)
                        },
                        onNextAvatar = {
                            val next = avatars[(avatars.indexOf(avatar) + 1) % avatars.size]
                            avatar = next
                            prefs.edit().putString("avatar", next.name).apply()
                        },
                    ),
                    level = { capture?.level ?: 0f },
                    happyAt = { happyAt },
                    onTap = ::onTap,
                    showTalk = showTalk,
                    onVolume = { step ->
                        adjustVolume(if (step > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER)
                    },
                    onReading = ::keepReply,
                    history = history,
                    onClearHistory = { LuxRuntime.history(this).clear() },
                    onPageChanged = ::onPageChanged,
                    volumeShownAt = volumeShownAt,
                )
            }
        }
        // Start BLE pairing advertisement if not paired yet; otherwise onResume connects.
        if (!connection.isPaired) requestPairing()
        if (savedInstanceState == null) handleLaunch(intent)
    }

    override fun onResume() {
        super.onResume()
        LuxRuntime.markUsed()
        LuxRuntime.warmUp(this)
        readVolume()
        LuxRuntime.onMessage = messageListener
        LuxRuntime.onAvatarsChanged = avatarsListener
        avatarsListener()
        val log = LuxRuntime.history(this)
        log.onChange = historyListener
        historyListener()
    }

    private val historyListener: () -> Unit = { history = LuxRuntime.history(this).all() }

    private val avatarsListener: () -> Unit = {
        avatars = AvatarCatalog.all(this)
        avatar = AvatarCatalog.byName(this, getSharedPreferences("lux_prefs", MODE_PRIVATE).getString("avatar", null))
    }

    /** The user is paging through the reply with the bezel: keep it up, and stay on Lux. */
    private fun keepReply() {
        returnToFace?.cancel()
        clearReply?.cancel()
        clearReply = scope.launch {
            delay(REPLY_LINGER_MS)
            reply = null
        }
    }

    /** Lets the voice or speed just chosen be heard (only when Lux isn't busy). */
    private fun sampleVoice() {
        if (status != TalkStatus.IDLE) return
        scope.launch { tts.speak("Hi, this is how I sound.") }
    }

    /** Settings → Get my avatar: asks Muse to send its clips with avatar.set_clips. */
    private fun askMuseForAvatar() {
        if (!paired) {
            showError("Pair with Muse first")
            return
        }
        if (status != TalkStatus.IDLE) return
        showTalk++
        clearMessages()
        heard = "Send me your avatar"
        status = TalkStatus.THINKING
        val asked = SystemClock.elapsedRealtime()
        turn = scope.launch {
            // Not through the backend: the reply may be a list of links, which shouldn't be read out.
            val answer = try {
                connection.textTurn(AVATAR_REQUEST)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = TalkStatus.IDLE
                showError("Can't reach Muse")
                return@launch
            }
            status = TalkStatus.IDLE
            LuxRuntime.history(this@MainActivity).add(
                ConversationLog.Entry(System.currentTimeMillis(), heard, answer),
            )
            val found = AvatarLinks.parse(answer)
            when {
                // Muse called avatar.set_clips during the turn: the import is already under way.
                LuxRuntime.avatarImportAt > asked -> reply = "Getting your avatar\u2026"
                // Muse sent the links as text instead: import them here.
                found != null -> {
                    reply = "Getting your avatar\u2026"
                    try {
                        LuxRuntime.commands(this@MainActivity).importAvatar(found.name ?: "My Muse", found.clips)
                    } catch (e: ClipImporter.ImportError) {
                        reply = null
                        showError(e.message ?: "Couldn't download the clips")
                    }
                }
                else -> reply = answer.ifBlank { null }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (LuxRuntime.onMessage === messageListener) LuxRuntime.onMessage = null
        if (LuxRuntime.onAvatarsChanged === avatarsListener) LuxRuntime.onAvatarsChanged = null
        val log = LuxRuntime.history(this)
        if (log.onChange === historyListener) log.onChange = null
    }

    private val messageListener: (String, String, Boolean) -> Boolean = ::showMuseMessage

    /** A message from Muse while Lux is on screen: show it as Lux's reply, and speak it. */
    private fun showMuseMessage(title: String, text: String, speak: Boolean): Boolean {
        if (status != TalkStatus.IDLE) return false // mid-talk: it goes to a notification
        clearMessages()
        returnToFace?.cancel()
        reply = if (title == "Lux") text else "$title: $text"
        happyAt = SystemClock.elapsedRealtime()
        haptics.reply()
        if (speak) {
            scope.launch {
                onStatus(TalkStatus.SPEAKING)
                try {
                    tts.speak(text)
                } finally {
                    onStatus(TalkStatus.IDLE)
                }
            }
        } else {
            onStatus(TalkStatus.IDLE)
        }
        return true
    }

    override fun onStop() {
        super.onStop()
        closeTokenScreen() // stop advertising when the watch goes dark
        LuxRuntime.markUsed() // a talk just now: the next wrist raise warms up
        LuxRuntime.coolDown(this)
    }

    /** One tap from the watch face, tile or complication: open listening. */
    private fun handleLaunch(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_FROM_FACE, false)) fromFace = true
        if (!intent.getBooleanExtra(EXTRA_TALK, false)) return
        intent.removeExtra(EXTRA_TALK)
        intent.removeExtra(EXTRA_FROM_FACE)
        if (!paired) return
        returnToFace?.cancel()
        when (status) {
            TalkStatus.IDLE -> startListening()
            TalkStatus.SPEAKING -> {
                tts.stop()
                startListening()
            }
            else -> {} // already listening or thinking: just show it
        }
    }

    /** After a talk that started on the watch face, go back to the face. */
    private fun scheduleReturnToFace() {
        if (!fromFace) return
        returnToFace?.cancel()
        returnToFace = scope.launch {
            delay(RETURN_TO_FACE_MS)
            if (status == TalkStatus.IDLE && capture == null) {
                fromFace = false
                moveTaskToBack(true)
            }
        }
    }

    // ---- talk page ----

    private val modeSource: () -> AvatarMode = ::avatarMode

    private fun avatarMode(): AvatarMode {
        val link = linkState
        return when {
            error != null -> AvatarMode.ERROR
            status == TalkStatus.LISTENING -> AvatarMode.LISTENING
            status == TalkStatus.THINKING -> AvatarMode.THINKING
            status == TalkStatus.SPEAKING -> AvatarMode.SPEAKING
            paired && link is GadgetConnection.State.Connecting -> AvatarMode.BOOT
            else -> AvatarMode.IDLE
        }
    }

    private fun talkUi(): TalkUi {
        val link = linkState
        val mode = avatarMode()
        val prompt = when {
            error != null -> error!!
            !paired -> "Not paired yet"
            status == TalkStatus.LISTENING -> "Listening\u2026"
            status == TalkStatus.THINKING -> heard?.let { "\u201c$it\u201d" } ?: "Thinking\u2026"
            link is GadgetConnection.State.Connecting -> "Connecting to Muse\u2026"
            link is GadgetConnection.State.Failed -> "Muse offline \u2014 swipe for settings"
            else -> "Tap to talk"
        }
        val notes = if (paired) emptyList() else listOfNotNull(unpairedNote, "Add me in the Muse app", tokenNote, pairingError)
        val showReply = error == null && status != TalkStatus.LISTENING && status != TalkStatus.THINKING
        return TalkUi(
            mode = mode,
            prompt = prompt,
            reply = if (showReply) reply else null,
            notes = notes,
            isError = error != null,
        )
    }

    private fun onTap() {
        returnToFace?.cancel()
        when {
            !paired -> if (pairingServer == null) requestPairing()
            status == TalkStatus.IDLE -> startListening()
            status == TalkStatus.LISTENING -> stopListeningAndSend()
            status == TalkStatus.SPEAKING -> tts.stop()
            status == TalkStatus.THINKING -> {
                turn?.cancel()
                status = TalkStatus.IDLE
            }
        }
    }

    private fun startListening() {
        if (capture != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val cap = AudioCapture()
        if (!cap.start()) {
            showError("Microphone unavailable")
            return
        }
        capture = cap
        clearMessages()
        status = TalkStatus.LISTENING
        haptics.listen()
        // Send when the speaker stops (or at Muse's 15 s limit); a tap sends sooner.
        autoStop = scope.launch {
            val endpointer = Endpointer()
            val start = SystemClock.elapsedRealtime()
            while (true) {
                delay(50)
                val elapsed = SystemClock.elapsedRealtime() - start
                val decision = endpointer.update(cap.level, elapsed)
                when {
                    decision == Endpointer.Decision.SEND || elapsed >= MAX_RECORD_MS -> {
                        stopListeningAndSend()
                        return@launch
                    }
                    decision == Endpointer.Decision.GIVE_UP -> {
                        cancelListening("Didn't hear anything")
                        return@launch
                    }
                }
            }
        }
    }

    /**
     * Swiped away from the talk page: the user is doing something else, so stop
     * listening without a fuss and stay in Lux (no trip back to the watch face).
     */
    private fun onPageChanged(page: Int) {
        if (page == 0) return
        returnToFace?.cancel()
        fromFace = false
        autoStop?.cancel()
        val cap = capture ?: return
        capture = null
        scope.launch(Dispatchers.IO) { cap.stop() }
        status = TalkStatus.IDLE
    }

    private fun cancelListening(message: String) {
        autoStop?.cancel()
        val cap = capture ?: return
        capture = null
        scope.launch(Dispatchers.IO) { cap.stop() }
        status = TalkStatus.IDLE
        showError(message)
        scheduleReturnToFace()
    }

    private fun stopListeningAndSend() {
        autoStop?.cancel()
        val cap = capture ?: return
        capture = null
        haptics.send()
        status = TalkStatus.THINKING
        turn = scope.launch {
            val audio = withContext(Dispatchers.IO) { cap.stop() }
            if (VoiceNote.durationMs(audio, AudioCapture.SAMPLE_RATE) < VoiceNote.MIN_MS) {
                status = TalkStatus.IDLE
                showError("Didn't catch that")
                scheduleReturnToFace()
                return@launch
            }
            backend.voiceTurn(audio)
        }
    }

    private fun onStatus(s: TalkStatus) {
        // A cancelled or finished turn returns to idle; keep listening state ours.
        if (status == TalkStatus.LISTENING && s == TalkStatus.IDLE) return
        val turnEnded = s == TalkStatus.IDLE && status != TalkStatus.IDLE
        status = s
        if (turnEnded) scheduleReturnToFace()
        if (s == TalkStatus.IDLE && reply != null) {
            clearReply?.cancel()
            clearReply = scope.launch {
                delay(REPLY_LINGER_MS)
                reply = null
            }
        }
    }

    private fun onReply(text: String) {
        LuxRuntime.history(this).add(ConversationLog.Entry(System.currentTimeMillis(), heard ?: "(voice message)", text))
        reply = text
        happyAt = SystemClock.elapsedRealtime()
        haptics.reply()
    }

    private fun showError(message: String) {
        error = message
        haptics.error()
        clearError?.cancel()
        clearError = scope.launch {
            delay(ERROR_SHOW_MS)
            error = null
        }
    }

    private fun clearMessages() {
        clearError?.cancel()
        clearReply?.cancel()
        error = null
        reply = null
        heard = null
    }

    // ---- connection / settings ----

    /**
     * Connects to Muse (fetch_vms → Noise → link.register) in the background,
     * like the SDK's service does right after setup: the Muse app expects a
     * newly paired gadget to come online.
     */
    private fun connectToMuse() {
        // The shared runtime's scope: the connection outlives this screen.
        LuxRuntime.warmUp(this)
    }

    private fun reconnect() {
        if (!paired) return
        connection.disconnect()
        connectToMuse()
    }

    private fun repair() {
        if (paired) {
            storage.clearPairing()
            connection.disconnect()
            paired = false
        }
        requestPairing()
    }

    /** One volume step with Lux's own feedback (ring + tick) instead of the system popup over the app. */
    private fun adjustVolume(direction: Int) {
        val audio = getSystemService(AudioManager::class.java) ?: return
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        readVolume()
        volumeShownAt = SystemClock.elapsedRealtime()
        haptics.tick()
    }

    private fun readVolume() {
        val audio = getSystemService(AudioManager::class.java) ?: return
        volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    /** Read once: it's a package manager call, too slow for every recomposition. */
    private val appVersion: String by lazy { versionName() }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""

    private fun linkLabel(state: GadgetConnection.State): String = when (state) {
        GadgetConnection.State.NotPaired -> "Not paired"
        GadgetConnection.State.Disconnected -> "Offline \u2014 tap to connect"
        GadgetConnection.State.Connecting -> "Connecting\u2026"
        GadgetConnection.State.Ready -> "Connected"
        is GadgetConnection.State.Failed -> "${state.message} \u2014 tap to retry".take(80)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeSdkTokenExtra(intent)
        handleLaunch(intent)
    }

    /**
     * Takes the gadget SDK token from an adb launch, so it never has to be typed
     * on the watch:
     *   adb shell am start -S -n com.luxwatch.gadget.debug/com.luxwatch.gadget.MainActivity --es sdk_token mgst_…
     * (-S: if the app is already open, Android otherwise just brings its task forward
     * and drops the extra.)
     * Stored encrypted; the value is never logged or shown.
     */
    private fun consumeSdkTokenExtra(intent: Intent?) {
        // Debug builds only: MainActivity is exported, so in a release any app on
        // the watch could hand it a token. Everyone else enters it in Settings.
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val token = intent?.getStringExtra(EXTRA_SDK_TOKEN)?.trim()?.takeIf { debuggable }
        intent?.removeExtra(EXTRA_SDK_TOKEN)
        tokenSet = storage.sdkToken != null
        if (token == null) {
            tokenNote = if (tokenSet) "SDK token set" else "No SDK token: Settings \u2192 SDK token"
            return
        }
        if (!SdkToken.isValid(token)) {
            tokenNote = "That SDK token isn't valid — copy it again from gadgets.muse.ai"
            return
        }
        applySdkToken(token)
    }

    /** Saves a valid SDK token (from adb or the watch's input) and reports it to Muse. */
    private fun applySdkToken(token: String) {
        storage.sdkToken = token
        tokenSet = true
        tokenNote = "SDK token saved"
        connection.sdkTokenChanged()
        if (connection.isPaired) {
            // Report it to Muse now (one refresh, like the SDK on start).
            connectToMuse()
        } else if (pairingServer != null) {
            // Restart advertising so pairing_confirmed carries the token.
            pairingServer?.stop()
            pairingServer = null
            requestPairing()
        }
    }

    private var pairingServer: BlePairingServer? = null

    /** Gets the Bluetooth runtime permissions if needed, then starts pairing. */
    private fun requestPairing() {
        val missing = BlePairingServer.requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startPairingServer()
        } else {
            blePermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startPairingServer() {
        if (pairingServer != null) return
        pairingError = null
        try {
            pairingServer = BlePairingServer(
                context = this,
                bleName = storage.bleName,
                nodeId = storage.nodeId,
                deviceId = storage.deviceId,
                mac = storage.mac,
                sdkToken = storage.sdkToken, // sent in the encrypted pairing_confirmed status
                callbacks = object : BlePairingServer.PairingCallbacks {
                    override fun onProvisioned(
                        accessToken: String,
                        refreshToken: String,
                        tokenType: String,
                        username: String,
                        apiUrl: String,
                        apiUrlV2: String?,
                        noiseHost: String,
                    ) {
                        storage.savePairing(
                            accessToken, refreshToken, tokenType,
                            username, apiUrl, apiUrlV2, noiseHost,
                        )
                        scope.launch {
                            pairingError = null
                            unpairedNote = null
                            paired = true
                            connectToMuse()
                            // handleProvision sends auth_ok after this callback returns;
                            // give that notification time to go out before closing GATT.
                            launch {
                                delay(3_000)
                                pairingServer?.stop()
                                pairingServer = null
                            }
                            happyAt = SystemClock.elapsedRealtime() // Lux celebrates
                            tts.speak("Paired. Tap to talk to Lux.")
                        }
                    }

                    override fun onUnpair() {
                        storage.clearPairing()
                        connection.disconnect()
                        runOnUiThread { paired = false }
                    }

                    override fun onError(message: String) {
                        runOnUiThread { pairingError = message }
                    }
                },
            ).also { it.start() }
        } catch (e: Exception) {
            pairingServer?.stop()
            pairingServer = null
            pairingError = "Can't pair: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    override fun onDestroy() {
        if (LuxStatus.mode === modeSource) LuxStatus.reset()
        if (connection.onStateChanged === stateListener) connection.onStateChanged = null
        if (connection.onUnpaired === unpairedListener) connection.onUnpaired = null
        scope.cancel()
        capture?.stop()
        pairingServer?.stop()
        tokenReceiver?.stop()
        tts.shutdown()
        super.onDestroy()
    }
}
