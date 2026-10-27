package com.luxwatch.gadget.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.HierarchicalFocusCoordinator
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.rememberActiveFocusRequester
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.HorizontalPageIndicator
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PageIndicatorState
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import com.luxwatch.gadget.ConversationLog
import com.luxwatch.gadget.avatar.AvatarArt
import com.luxwatch.gadget.avatar.AvatarMode
import kotlinx.coroutines.delay

/** Everything the talk page shows. */
data class TalkUi(
    val mode: AvatarMode,
    /** Short status under the avatar ("Tap to talk", "Listening…", an error…). */
    val prompt: String,
    /** Lux's reply, paged through at reading pace; null when there is none. */
    val reply: String?,
    /** Extra small lines (pairing / token notes) shown while unpaired. */
    val notes: List<String> = emptyList(),
    val isError: Boolean = false,
)

/** Everything the settings page shows. */
data class SettingsUi(
    val paired: Boolean,
    val link: String,
    val tokenSet: Boolean,
    /** Changed since pairing: Muse uses it only after the watch pairs again. */
    val tokenNeedsRepair: Boolean = false,
    val haptics: Boolean,
    val version: String,
    /** Media volume now and its maximum. */
    val volume: Int = 0,
    val maxVolume: Int = 15,
    val avatarName: String = "",
    /** Reading voice ("2 of 5") and speed (1.0 = normal). */
    val voice: String = "Default",
    val speechRate: Float = 1f,
    /** Stay connected so Muse can send messages (watch.notify). */
    val museMessages: Boolean = false,
    val canChangeAvatar: Boolean = false,
    /** The watch's talks have their own chat in Muse. */
    val ownChat: Boolean = false,
    /** Tapping Lux starts a realtime call with Muse. */
    val liveCalls: Boolean = false,
)

class SettingsActions(
    val onReconnect: () -> Unit,
    val onVolumeDown: () -> Unit,
    val onVolumeUp: () -> Unit,
    val onHaptics: (Boolean) -> Unit,
    val onRepair: () -> Unit,
    /** Opens the token screen: sent from the phone's browser, or typed. */
    val onSetToken: () -> Unit = {},
    val onMuseMessages: (Boolean) -> Unit = {},
    val onNextVoice: () -> Unit = {},
    val onSpeechRate: (Float) -> Unit = {},
    /** Asks Muse to send its own avatar clips (avatar.set_clips). */
    val onGetAvatar: () -> Unit = {},
    /** Switches to the next avatar in the catalog. */
    val onNextAvatar: () -> Unit = {},
    val onOwnChat: (Boolean) -> Unit = {},
    val onLiveCalls: (Boolean) -> Unit = {},
)

/** Three pages, swiped horizontally: talk (default), history, settings. */
@OptIn(ExperimentalWearFoundationApi::class)
@Composable
fun LuxApp(
    talk: TalkUi,
    avatar: AvatarArt,
    settings: SettingsUi,
    actions: SettingsActions,
    level: () -> Float,
    happyAt: () -> Long,
    onTap: () -> Unit,
    /** Changes whenever the talk page should come to the front. */
    showTalk: Int = 0,
    /** Bezel while Lux speaks: +1 louder, -1 quieter. */
    onVolume: (Int) -> Unit = {},
    /** The user is reading a reply with the bezel: keep it on screen. */
    onReading: () -> Unit = {},
    history: List<ConversationLog.Entry> = emptyList(),
    onClearHistory: () -> Unit = {},
    /** The page on screen changed (0 = talk). */
    onPageChanged: (Int) -> Unit = {},
    /** Volume feedback on the talk page: shown for a moment each time this changes. */
    volumeShownAt: Long = 0L,
) {
    val pager = rememberPagerState(pageCount = { 3 })
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { onPageChanged(it) } }
    LaunchedEffect(showTalk) { if (showTalk > 0) pager.animateScrollToPage(0) }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            // The bezel goes to the page on screen.
            HierarchicalFocusCoordinator(requiresFocus = { pager.currentPage == page }) {
                when (page) {
                    0 -> TalkPage(
                        talk, level, happyAt, onTap, avatar, onVolume, onReading,
                        volume = settings.volume, maxVolume = settings.maxVolume, volumeShownAt = volumeShownAt,
                    )
                    1 -> HistoryPage(history, onClearHistory)
                    else -> SettingsPage(settings, actions)
                }
            }
        }
        HorizontalPageIndicator(
            pageIndicatorState = pager.asIndicatorState(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
        )
    }
}

private fun PagerState.asIndicatorState() = object : PageIndicatorState {
    override val pageOffset: Float get() = currentPageOffsetFraction
    override val selectedPage: Int get() = currentPage
    override val pageCount: Int get() = this@asIndicatorState.pageCount
}

@OptIn(ExperimentalWearFoundationApi::class)
@Composable
fun TalkPage(
    ui: TalkUi,
    level: () -> Float,
    happyAt: () -> Long,
    onTap: () -> Unit,
    avatar: AvatarArt,
    onVolume: (Int) -> Unit = {},
    onReading: () -> Unit = {},
    volume: Int = 0,
    maxVolume: Int = 15,
    volumeShownAt: Long = 0L,
    /** Hardware video for Lux's clips; screenshot tests turn it off (no GPU). */
    video: Boolean = true,
) {
    var showVolume by remember { mutableStateOf(false) }
    LaunchedEffect(volumeShownAt) {
        if (volumeShownAt > 0L) {
            showVolume = true
            delay(1_500)
            showVolume = false
        }
    }
    // A long reply goes page by page: on its own at reading pace, or with the bezel.
    // It grows while Muse is still writing it: same reply (same start), same page.
    val replyKey = ui.reply?.take(24)
    val pages = remember(ui.reply) { ui.reply?.let { paginate(it, maxChars = 72) } ?: emptyList() }
    var page by remember(replyKey) { mutableStateOf(0) }
    var turnedByHand by remember(replyKey) { mutableStateOf(false) }
    if (page > pages.lastIndex) page = maxOf(0, pages.lastIndex)
    LaunchedEffect(replyKey, turnedByHand, pages.size) {
        while (!turnedByHand && page < pages.size - 1) {
            delay(maxOf(2_200L, pages[page].length * 1000L / 15)) // ~15 chars/s
            page++
        }
    }
    // Battery: Lux moves only while it's doing something, or celebrating a reply.
    val lastHappy = happyAt()
    var celebrating by remember { mutableStateOf(false) }
    LaunchedEffect(lastHappy) {
        val left = CELEBRATE_MS - (android.os.SystemClock.elapsedRealtime() - lastHappy)
        if (lastHappy != 0L && left > 0) {
            celebrating = true
            delay(left)
        }
        celebrating = false
    }
    val animate = ui.mode in LIVELY || celebrating
    var spin by remember { mutableStateOf(0f) }
    val focus = rememberActiveFocusRequester()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
            .onRotaryScrollEvent { e ->
                spin += e.verticalScrollPixels
                if (kotlin.math.abs(spin) >= BEZEL_STEP_PX) {
                    val step = if (spin > 0) 1 else -1
                    spin = 0f
                    // Pages of a long reply being read; volume otherwise (and while Lux speaks).
                    if (pages.size > 1 && ui.mode != AvatarMode.SPEAKING) {
                        turnedByHand = true
                        page = (page + step).coerceIn(0, pages.size - 1)
                        onReading()
                    } else {
                        onVolume(step)
                    }
                }
                true
            }
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        // Make room on the round screen when there's text to read.
        val hasText = ui.reply != null || ui.notes.isNotEmpty()
        val avatarSize = minOf(maxWidth, maxHeight) * (if (hasText) 0.44f else 0.58f)
        val accent = Color(avatar.accent(ui.mode))
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AvatarView(avatar, ui.mode, level, happyAt, Modifier.size(avatarSize), animate = animate, video = video)
            Spacer(Modifier.height(4.dp))
            if (ui.reply != null) {
                ReplyCaption(pages.getOrElse(page) { "" })
                if (pages.size > 1) {
                    Text(
                        "${page + 1}/${pages.size}",
                        style = MaterialTheme.typography.caption3,
                        color = MaterialTheme.colors.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    ui.prompt,
                    style = MaterialTheme.typography.caption1,
                    color = if (ui.isError) accent else MaterialTheme.colors.onBackground,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                for (note in ui.notes) {
                    Text(
                        note,
                        style = MaterialTheme.typography.caption3,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (showVolume) {
            // The volume as a ring round the edge (open at the bottom), and the level on top.
            CircularProgressIndicator(
                progress = if (maxVolume > 0) volume / maxVolume.toFloat() else 0f,
                modifier = Modifier.fillMaxSize().padding(3.dp),
                startAngle = 120f,
                endAngle = 60f,
                indicatorColor = accent,
                trackColor = Color(0x33FFFFFF),
                strokeWidth = 6.dp,
            )
            Text(
                "Volume $volume/$maxVolume",
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp),
                style = MaterialTheme.typography.caption1,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** States in which the avatar animates; otherwise it's a still frame. */
private val LIVELY = setOf(AvatarMode.LISTENING, AvatarMode.THINKING, AvatarMode.SPEAKING, AvatarMode.BOOT)

/** How long Lux stays animated after a reply or message. */
private const val CELEBRATE_MS = 8_000L

/** Bezel movement that counts as one step (a Galaxy bezel click, or a bit of crown turn). */
private const val BEZEL_STEP_PX = 30f

/** One page of a reply (a few short lines), like the Muse boards' caption. */
@Composable
fun ReplyCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.caption1,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Splits text into word-wrapped chunks of at most [maxChars]. */
internal fun paginate(text: String, maxChars: Int): List<String> {
    val pages = mutableListOf<String>()
    val current = StringBuilder()
    for (word in text.trim().split(Regex("\\s+"))) {
        if (current.isNotEmpty() && current.length + 1 + word.length > maxChars) {
            pages += current.toString()
            current.clear()
        }
        if (current.isNotEmpty()) current.append(' ')
        current.append(word)
    }
    if (current.isNotEmpty()) pages += current.toString()
    return pages.ifEmpty { listOf("") }
}

/** Recent exchanges with Lux and messages from Muse, newest first. */
@Composable
fun HistoryPage(entries: List<ConversationLog.Entry>, onClear: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(confirmClear) {
        if (confirmClear) {
            delay(4_000)
            confirmClear = false
        }
    }
    ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
        item { ListHeader { Text("History") } }
        if (entries.isEmpty()) {
            item {
                Text(
                    "Nothing yet. Tap Lux and ask something.",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        for (e in entries) {
            item(key = e.time) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // The time today, the date before that.
                    Text(
                        android.text.format.DateUtils.formatSameDayTime(
                            e.time, System.currentTimeMillis(), java.text.DateFormat.SHORT, java.text.DateFormat.SHORT,
                        ).toString(),
                        style = MaterialTheme.typography.caption3,
                        color = MaterialTheme.colors.onSurfaceVariant,
                    )
                    if (e.you != null) {
                        Text(
                            "\u201c${e.you}\u201d",
                            style = MaterialTheme.typography.caption2,
                            color = MaterialTheme.colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Text(
                        if (e.you == null && e.from != "Lux") "${e.from}: ${e.reply}" else e.reply,
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        if (entries.isNotEmpty()) item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (confirmClear) "Tap again to clear" else "Clear history") },
                onClick = {
                    if (confirmClear) {
                        confirmClear = false
                        onClear()
                    } else {
                        confirmClear = true
                    }
                },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}

@Composable
fun SettingsPage(ui: SettingsUi, actions: SettingsActions) {
    var confirmRepair by remember { mutableStateOf(false) }
    LaunchedEffect(confirmRepair) {
        if (confirmRepair) {
            delay(4_000)
            confirmRepair = false
        }
    }
    ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
        item { ListHeader { Text("Lux settings") } }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Muse") },
                secondaryLabel = { Text(ui.link, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                onClick = actions.onReconnect,
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                label = { Text("SDK token") },
                secondaryLabel = {
                    Text(
                        when {
                            ui.tokenNeedsRepair -> "Changed: re-pair to use it"
                            ui.tokenSet -> "Set \u2014 tap to change"
                            else -> "Set it before pairing"
                        },
                    )
                },
                onClick = actions.onSetToken,
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = actions.onVolumeDown,
                    colors = ButtonDefaults.secondaryButtonColors(),
                    modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
                ) { Text("−") }
                Text("Volume ${ui.volume}/${ui.maxVolume}", style = MaterialTheme.typography.caption1)
                Button(
                    onClick = actions.onVolumeUp,
                    colors = ButtonDefaults.secondaryButtonColors(),
                    modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
                ) { Text("+") }
            }
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Get my avatar") },
                secondaryLabel = { Text("Asks Muse for its own") },
                onClick = actions.onGetAvatar,
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        if (ui.canChangeAvatar) item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Avatar") },
                secondaryLabel = { Text(ui.avatarName) },
                onClick = actions.onNextAvatar,
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Voice") },
                secondaryLabel = { Text("${ui.voice} \u2014 tap to change") },
                onClick = actions.onNextVoice,
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { actions.onSpeechRate(ui.speechRate - 0.1f) },
                    colors = ButtonDefaults.secondaryButtonColors(),
                    modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
                ) { Text("\u2212") }
                Text("Speed %.1f\u00d7".format(ui.speechRate), style = MaterialTheme.typography.caption1)
                Button(
                    onClick = { actions.onSpeechRate(ui.speechRate + 0.1f) },
                    colors = ButtonDefaults.secondaryButtonColors(),
                    modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
                ) { Text("+") }
            }
        }
        item {
            ToggleChip(
                modifier = Modifier.fillMaxWidth(),
                checked = ui.museMessages,
                onCheckedChange = actions.onMuseMessages,
                label = { Text("Messages from Muse") },
                secondaryLabel = { Text(if (ui.museMessages) "Stays connected: uses battery" else "Off: saves battery") },
                toggleControl = { Switch(checked = ui.museMessages) },
            )
        }
        item {
            ToggleChip(
                modifier = Modifier.fillMaxWidth(),
                checked = ui.liveCalls,
                onCheckedChange = actions.onLiveCalls,
                label = { Text("Live calls (beta)") },
                secondaryLabel = { Text(if (ui.liveCalls) "Tap Lux to call Muse" else "Off: voice messages") },
                toggleControl = { Switch(checked = ui.liveCalls) },
            )
        }
        item {
            ToggleChip(
                modifier = Modifier.fillMaxWidth(),
                checked = ui.ownChat,
                onCheckedChange = actions.onOwnChat,
                label = { Text("Own chat in Muse") },
                secondaryLabel = { Text(if (ui.ownChat) "Talks have their own chat" else "In your main Muse chat") },
                toggleControl = { Switch(checked = ui.ownChat) },
            )
        }
        item {
            ToggleChip(
                modifier = Modifier.fillMaxWidth(),
                checked = ui.haptics,
                onCheckedChange = actions.onHaptics,
                label = { Text("Haptics") },
                toggleControl = { Switch(checked = ui.haptics) },
            )
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (confirmRepair) "Tap again to confirm" else if (ui.paired) "Re-pair watch" else "Start pairing") },
                secondaryLabel = {
                    Text(if (ui.paired) "Forgets this pairing" else "Add it in the Muse app")
                },
                onClick = {
                    if (!ui.paired || confirmRepair) {
                        confirmRepair = false
                        actions.onRepair()
                    } else {
                        confirmRepair = true
                    }
                },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        item {
            Text(
                "Lux Watch ${ui.version}",
                style = MaterialTheme.typography.caption3,
                color = MaterialTheme.colors.onSurfaceVariant,
            )
        }
    }
}
