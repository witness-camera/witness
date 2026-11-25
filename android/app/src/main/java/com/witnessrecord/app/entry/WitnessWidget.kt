package com.witnessrecord.app.entry

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import com.witnessrecord.app.AppState
import com.witnessrecord.app.MainActivity
import com.witnessrecord.app.R

/**
 * Home screen widget that deep-links into the instant-start flow. It launches
 * an activity rather than running an in-widget action because recording
 * requires the app to be in the foreground for camera access - the tap takes
 * the user straight into black-screen recording.
 *
 * (Android has had no general lock screen widgets since Android 5, so unlike
 * the iOS build's lock screen widget this one lives on the home screen; the
 * quick-settings tile covers the lock screen case instead.)
 */
class WitnessWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WidgetContent(context) }
    }

    @Composable
    private fun WidgetContent(context: Context) {
        val launch = Intent(Intent.ACTION_VIEW, AppState.startUri).apply {
            setClass(context, MainActivity::class.java)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        GlanceTheme {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(Color.Black))
                    .padding(8.dp)
                    .clickable(actionStartActivity(launch)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_record),
                    contentDescription = null,
                    modifier = GlanceModifier.size(40.dp),
                )
                Spacer(GlanceModifier.height(6.dp))
                Text(
                    text = context.getString(R.string.widget_start_recording),
                    style = TextStyle(
                        color = ColorProvider(Color.White),
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }
    }
}

class WitnessWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WitnessWidget()
}
