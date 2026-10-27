package com.luxwatch.gadget.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

/** A picture Muse sent (display.draw_file / draw_url), full screen; a tap closes it. */
@Composable
fun PictureScreen(picture: ImageBitmap, caption: String?, onClose: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            picture,
            contentDescription = caption ?: "Picture from Muse",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (caption != null) {
            Text(
                caption,
                style = MaterialTheme.typography.caption2,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 40.dp, end = 40.dp, bottom = 22.dp)
                    .background(Color(0xAA000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
