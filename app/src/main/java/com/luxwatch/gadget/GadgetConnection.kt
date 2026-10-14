package com.luxwatch.gadget

import android.util.Log
import com.luxwatch.gadget.noise.CommandHandler
import com.luxwatch.gadget.noise.CommandSpec
import com.luxwatch.gadget.noise.MuseApi
import com.luxwatch.gadget.noise.MuseSession
import com.luxwatch.gadget.noise.NoiseTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the Muse gadget connection lifecycle:
 * token refresh → fetch_vms → Noise handshake → link.register,
 * with exponential backoff on failure.
 */
class GadgetConnection(
    private val storage: GadgetStorage,
    private val api: MuseApi = MuseApi(),
    /** Commands offered to Muse (link.invoke), and what runs them. */
    private val commands: List<CommandSpec> = emptyList(),
    private val onInvoke: CommandHandler? = null,
) {
    sealed interface State {
        data object NotPaired : State
        data object Disconnected : State
        data object Connecting : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    /** Called (on any thread) whenever [state] changes. */
    var onStateChanged: ((State) -> Unit)? = null

    var state: State = if (storage.pairing != null) State.Disconnected else State.NotPaired
        private set(value) {
            field = value
            Log.i(TAG, "state: $value")
            onStateChanged?.invoke(value)
        }

    private val mutex = Mutex()
    private var transport: NoiseTransport? = null
    private var session: MuseSession? = null

    /** mgst_… SDK token, sent only in refresh bodies (never as a Bearer token). */
    private val sdkToken: String? get() = storage.sdkToken

    /** Like the SDK: report the SDK token with one refresh per app start. */
    @Volatile
    private var sdkTokenReportAttempted = false

    /** A new SDK token was stored: report it on the next connect. */
    fun sdkTokenChanged() {
        sdkTokenReportAttempted = false
        if (state == State.Ready) state = State.Disconnected.also { closeSession() }
    }

    private fun closeSession() {
        session?.close()
        transport?.close()
        transport = null
        session = null
    }

    /** Called (on a background thread) after Muse unpairs this device and local pairing is wiped. */
    var onUnpaired: (() -> Unit)? = null

    val isPaired: Boolean get() = storage.pairing != null
    /** Registered and the link-control channel is still up. */
    val isReady: Boolean get() = state == State.Ready && session?.isOpen == true

    /**
     * Ensures a live, registered session. Returns true when ready.
     * Safe to call repeatedly; concurrent callers share one connect attempt.
     */
    suspend fun ensureConnected(): Boolean = mutex.withLock {
        if (state == State.Ready && session?.isOpen == true) return true
        state = State.Connecting
        try {
            // fetch_vms / token refresh are blocking OkHttp calls: never on the main thread.
            withContext(Dispatchers.IO) { connectLocked() }
            state = State.Ready
            true
        } catch (e: PairingRevoked) {
            Log.w(TAG, "Muse refused this watch's tokens: the pairing is gone")
            storage.clearPairing()
            state = State.NotPaired
            onUnpaired?.invoke()
            false
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The caller went away mid-connect (e.g. the screen closed): not a failure.
            state = if (isPaired) State.Disconnected else State.NotPaired
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
            state = if (isPaired) State.Failed(e.message ?: "connection failed") else State.NotPaired
            false
        }
    }

    private suspend fun connectLocked() {
        val pairing = storage.pairing ?: throw IllegalStateException("not paired")

        // Rotate the device token if older than 3h, or to report the SDK token:
        // Muse only learns it from refresh bodies until the apps forward it at
        // mint (service.py _maybe_refresh). The SDK does that once per service
        // start; the watch app starts far more often, and every refresh risks the
        // pairing (a lost response leaves only a refresh token Muse has already
        // replaced), so it's reported once per token, not per start.
        var accessToken = pairing.accessToken
        val due = System.currentTimeMillis() - pairing.savedAt > 3 * 3600 * 1000L
        val reportDue = sdkToken != null && !storage.sdkTokenReported && !sdkTokenReportAttempted
        if (due || reportDue) {
            if (reportDue) {
                sdkTokenReportAttempted = true
                Log.i(TAG, "refreshing device token to report the SDK token")
            }
            try {
                val tokens = api.refreshToken(
                    pairing.refreshToken, storage.nodeId, sdkToken, apiBase()
                )
                storage.updateTokens(tokens.accessToken, tokens.refreshToken)
                accessToken = tokens.accessToken
                if (sdkToken != null) storage.sdkTokenReported = true
                Log.i(TAG, "device token rotated")
            } catch (e: Exception) {
                if (!due) {
                    // Only reporting the SDK token: nothing rejected the current
                    // token, so a refusal here must never unpair the device.
                    Log.w(TAG, "SDK token report refresh failed; keeping the pairing: ${e.message}")
                } else if (e is MuseApi.ApiError && e.status == 401) {
                    throw PairingRevoked()
                } else {
                    // Transient failure: keep using the current token while it works.
                    Log.w(TAG, "device token refresh failed; using the current token: ${e.message}")
                }
            }
        }

        // Discover VMs.
        val vms: List<MuseApi.VmEntry> = try {
            api.fetchVms(accessToken, apiBase())
        } catch (e: MuseApi.ApiError) {
            if (e.status == 401) {
                // Force a refresh and retry once. If Muse refuses the refresh token
                // too, the pairing is gone: say so instead of retrying forever.
                val tokens = try {
                    api.refreshToken(
                        storage.pairing?.refreshToken ?: pairing.refreshToken,
                        storage.nodeId, sdkToken, apiBase()
                    )
                } catch (r: MuseApi.ApiError) {
                    if (r.status == 401) throw PairingRevoked() else throw r
                }
                storage.updateTokens(tokens.accessToken, tokens.refreshToken)
                api.fetchVms(tokens.accessToken, apiBase())
            } else throw e
        }
        val vm = vms.firstOrNull { it.default } ?: vms.firstOrNull()
            ?: throw IllegalStateException("no VMs available")

        // Noise handshake + register.
        val t = NoiseTransport()
        val noiseHost = storage.pairing?.noiseHost ?: GadgetStorage.DEFAULT_NOISE_HOST
        t.connect(noiseHost, vm.vmId, vm.authToken)
        val s = MuseSession(
            t, storage.nodeId, onUnpaired = ::handleUnpaired,
            commands = commands, onInvoke = onInvoke,
        )
        try {
            s.register()
        } catch (e: Exception) {
            s.close()
            t.close()
            throw e
        }

        session?.close()
        transport?.close()
        transport = t
        session = s
    }

    /** Runs a full voice turn. Ensures connection first. */
    suspend fun voiceTurn(audio: ByteArray, onHeard: (String) -> Unit = {}): String =
        turn { it.voiceTurn(audio, AudioCapture.SAMPLE_RATE, onHeard) }

    /** Sends a typed message to Muse as a turn from this watch; returns the reply. */
    suspend fun textTurn(text: String): String = turn { it.textTurn(text) }

    private suspend fun turn(run: suspend (MuseSession) -> String): String {
        if (!ensureConnected()) {
            throw IllegalStateException("not connected: ${(state as? State.Failed)?.message}")
        }
        val s = session ?: throw IllegalStateException("no session")
        try {
            return withContext(Dispatchers.IO) { run(s) }
        } catch (e: MuseSession.SessionError) {
            // The turn failed (no reply, rejected note…); the connection is fine.
            if (!s.isOpen) dropSession(e)
            throw e
        } catch (e: Exception) {
            dropSession(e)
            throw e
        }
    }

    /** Drops a broken session; the next call reconnects. */
    private suspend fun dropSession(e: Exception) {
        mutex.withLock {
            session?.close()
            transport?.close()
            transport = null
            session = null
            state = if (isPaired) State.Failed(e.message ?: "session failed") else State.NotPaired
        }
    }

    fun disconnect() {
        session?.close()
        transport?.close()
        transport = null
        session = null
        state = if (isPaired) State.Disconnected else State.NotPaired
    }

    /** link.unpaired / node.unpaired: delete local pairing; the device must be paired again. */
    private fun handleUnpaired() {
        storage.clearPairing()
        disconnect()
        onUnpaired?.invoke()
    }

    /** Muse refused the refresh token: the pairing is no longer valid. */
    private class PairingRevoked : Exception("Muse no longer accepts this pairing")

    companion object {
        private const val TAG = "GadgetConnection"
    }

    /**
     * API root: api_url_v2, else the Muse API. api_url is ignored — only older
     * firmware reads it, adding /hatch (SDK muse_api.api_root).
     */
    private fun apiBase(): String =
        (storage.pairing?.apiUrlV2 ?: "https://api.muse.ai").trimEnd('/')
}
