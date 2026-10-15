package com.luxwatch.gadget.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

/** The page that sends the token to the watch (docs/token/ on GitHub Pages). */
const val TOKEN_PAGE_URL = "https://arvindavidson.github.io/lux-watch/token/"

/** Settings → SDK token: get it from the phone over Bluetooth. */
data class TokenUi(
    val stage: Stage,
    /** The received token's last characters, to check against the phone. */
    val tail: String = "",
    /** A problem to show (Bluetooth off, a bad send…). */
    val note: String? = null,
) {
    enum class Stage { WAITING, CONFIRM, SAVED }
}

class TokenActions(
    val onSave: () -> Unit,
    /** Not this token: keep waiting for another. */
    val onReject: () -> Unit,
    val onTypeInstead: () -> Unit,
    val onClose: () -> Unit,
)

@Composable
fun TokenScreen(ui: TokenUi, actions: TokenActions) {
    when (ui.stage) {
        TokenUi.Stage.WAITING -> Waiting(ui, actions)
        TokenUi.Stage.CONFIRM -> Centered {
            Caption("Got a token ending in")
            Text(
                ui.tail,
                style = MaterialTheme.typography.title1,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 2.dp),
            )
            Caption("Same as on your phone?")
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            ) {
                Button(onClick = actions.onReject, colors = ButtonDefaults.secondaryButtonColors()) { Text("No") }
                Button(onClick = actions.onSave) { Text("Save") }
            }
        }
        TokenUi.Stage.SAVED -> Centered {
            Text("Token saved", style = MaterialTheme.typography.title3, color = MaterialTheme.colors.primary)
        }
    }
}

/** The QR code in the middle of the screen; the rest scrolls with the bezel. */
@Composable
private fun Waiting(ui: TokenUi, actions: TokenActions) {
    // ScalingLazyColumn centres item 1 at first: the QR code.
    ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
        item { Caption("Scan with your phone") }
        item { Qr(TOKEN_PAGE_URL) }
        item { Caption("Paste your token on that page and tap Send") }
        item {
            Text(
                ui.note ?: "Waiting for your phone\u2026",
                style = MaterialTheme.typography.caption2,
                color = if (ui.note != null) MaterialTheme.colors.error else MaterialTheme.colors.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        item { Caption(TOKEN_PAGE_URL.removePrefix("https://").removeSuffix("/")) }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Type it instead") },
                secondaryLabel = { Text("Or paste with the phone's keyboard") },
                onClick = actions.onTypeInstead,
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Close") },
                onClick = actions.onClose,
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}

@Composable
private fun Centered(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun Caption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.caption2,
        color = MaterialTheme.colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

/** Black modules on white with a quiet zone, so phone cameras read it on a dark watch. */
@Composable
private fun Qr(text: String) {
    val matrix: BitMatrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    }
    Box(
        Modifier.size(116.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = size.minDimension / matrix.width
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    // A hair over one cell so neighbours don't leave seams.
                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}
