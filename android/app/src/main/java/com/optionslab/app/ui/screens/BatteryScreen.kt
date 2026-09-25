package com.optionslab.app.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ui.components.BrandLogo
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type

/** Battery: the app is exempt from battery optimisation, so Android will not stop the market watch. */
object BatteryCheck {
    fun unrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    /** The system dialog that asks "Let app always run in background?" for this app. */
    @SuppressLint("BatteryLife")
    fun ask(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        runCatching { context.startActivity(direct) }.recoverCatching { context.startActivity(list) }.onFailure { appSettings(context) }
    }

    /** The app's own page in Settings: Battery, and on some phones Auto-start. */
    fun appSettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
    }
}

/**
 * Shown before anything else until the battery setting is changed: no close,
 * no way past it. It re-checks every second while on screen, so coming back
 * from Settings opens the app by itself.
 */
@Composable
fun BatteryScreen(onDone: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    com.optionslab.app.ui.PollWhileStarted {
        while (true) {
            if (BatteryCheck.unrestricted(ctx)) { onDone(); break }
            kotlinx.coroutines.delay(1000)
        }
    }
    val brand = Build.MANUFACTURER.lowercase()
    val oemHint = when {
        "xiaomi" in brand || "redmi" in brand || "poco" in brand -> "Xiaomi / Redmi / POCO: also turn on Autostart, and set Battery saver to No restrictions."
        "oppo" in brand || "realme" in brand -> "OPPO / realme: also allow Auto launch and Allow background activity."
        "vivo" in brand || "iqoo" in brand -> "vivo / iQOO: also allow High background power consumption and Auto-start."
        "oneplus" in brand -> "OnePlus: also set Battery optimisation to Don't optimise and allow Auto-launch."
        "samsung" in brand -> "Samsung: also make sure IraAlgo is not in Sleeping apps or Deep sleeping apps."
        "huawei" in brand || "honor" in brand -> "Huawei / Honor: also set App launch to Manage manually with all three switches on."
        else -> null
    }
    Box(Modifier.fillMaxSize().background(p.paper).statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp)
                .background(p.card, RoundedCornerShape(20.dp)).border(1.dp, p.rule, RoundedCornerShape(20.dp))
                .verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            BrandLogo()
            Spacer(Modifier.height(18.dp))
            Text("Allow IraAlgo to run in the background", style = Type.masthead.copy(color = p.ink, fontSize = 22.sp))
            Spacer(Modifier.height(4.dp))
            Text("The market watch, price alarms and P&L alerts run all market day. Android's battery saver would stop them, so IraAlgo needs to be left unrestricted before it opens.",
                style = Type.bodySmall.copy(color = p.inkSoft))
            Spacer(Modifier.height(14.dp))
            Text("Tap the button, then choose Allow.", style = Type.body.copy(color = p.ink, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(10.dp))
            BrassButton("Allow background running", Modifier.fillMaxWidth()) { BatteryCheck.ask(ctx) }
            Spacer(Modifier.height(8.dp))
            BrassButton("Open IraAlgo's app settings", Modifier.fillMaxWidth(), tone = p.inkSoft) { BatteryCheck.appSettings(ctx) }
            Spacer(Modifier.height(10.dp))
            Note("If no dialog appears: App settings → Battery → Unrestricted (or Don't optimise).")
            if (oemHint != null) Note(oemHint)
            Note("IraAlgo opens by itself as soon as the setting is changed.")
        }
    }
}
