package com.luxwatch.gadget

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest

/**
 * Primary launcher / status indicator as a watch-face complication.
 * Tapping it opens the push-to-talk UI.
 */
class LuxComplicationProvider : ComplicationDataSourceService() {

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationDataSourceService.ComplicationRequestListener
    ) {
        val launchIntent = Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TALK, true)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentDescription = PlainComplicationText.Builder("Talk to Lux").build()

        val data: ComplicationData? = when (request.complicationType) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder("Lux").build(),
                contentDescription = contentDescription
            )
                .setSmallImage(
                    SmallImage.Builder(
                        Icon.createWithResource(this, R.drawable.ic_lux),
                        SmallImageType.ICON
                    ).build()
                )
                .setTapAction(pendingIntent)
                .build()
            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                smallImage = SmallImage.Builder(
                    Icon.createWithResource(this, R.drawable.ic_lux),
                    SmallImageType.ICON
                ).build(),
                contentDescription = contentDescription
            )
                .setTapAction(pendingIntent)
                .build()
            else -> null
        }
        listener.onComplicationData(data)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        // Shown in the watch-face picker; static preview is fine for v1.
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder("Lux").build(),
                contentDescription = PlainComplicationText.Builder("Talk to Lux").build()
            ).build()
            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                smallImage = SmallImage.Builder(
                    Icon.createWithResource(this, R.drawable.ic_lux),
                    SmallImageType.ICON
                ).build(),
                contentDescription = PlainComplicationText.Builder("Talk to Lux").build()
            ).build()
            else -> null
        }
    }
}
