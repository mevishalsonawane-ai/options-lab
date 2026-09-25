package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.Token
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.Right
import com.optionslab.engine.fmtG
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A contract picked from the option chain. */
data class ChainPick(
    val underlying: String, val expiry: LocalDate, val strike: Double, val right: Right,
    val ltp: Double?, val delta: Double?, val ivPct: Double?, val lotSize: Int,
)

/**
 * The order sheet for one call or put, opened by tapping its price in the
 * chain. Buy or sell, lots, market or limit, product. In PAPER mode it
 * places a paper order; in LIVE mode it opens the usual review (margin
 * check, hold to send, PIN or fingerprint) - nothing is sent from here.
 */
@Composable
fun OptionOrderSheet(model: AppModel, pick: ChainPick, initialBuy: Boolean = true, initialLimit: Double? = null, onClose: () -> Unit) {
    val p = LocalPalette.current
    val s by model.settings.collectAsState()
    var buy by remember { mutableStateOf(initialBuy) }
    var lots by remember { mutableStateOf(1) }
    var limit by remember { mutableStateOf(initialLimit != null) }
    var price by remember { mutableStateOf((initialLimit ?: pick.ltp)?.let { String.format(Locale.ENGLISH, "%.2f", it) } ?: "") }
    var product by remember { mutableStateOf(s.orderProduct.takeIf { it == "MIS" } ?: "NRML") }
    val side = if (buy) p.verdigris else p.oxblood
    val qty = lots * pick.lotSize
    val px = if (limit) price.toDoubleOrNull() else pick.ltp
    val title = "${pick.underlying} ${pick.expiry.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)).uppercase()} ${fmtG(pick.strike)} ${pick.right.name}"

    Dialog(onDismissRequest = onClose, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn, usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().clickable(onClick = onClose), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.fillMaxWidth()
                    .background(p.card, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .border(1.dp, p.rule, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    // Taps inside the sheet must not reach the backdrop, which closes it.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .navigationBarsPadding()
                    .padding(20.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).background(p.rule, CircleShape))
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = Type.title.copy(color = p.ink, fontSize = 17.sp))
                        Text(listOfNotNull(pick.delta?.let { "Δ %.2f".format(Locale.ENGLISH, it) }, pick.ivPct?.let { "IV %.1f%%".format(Locale.ENGLISH, it) },
                            "lot ${pick.lotSize}").joinToString(" · "), style = Type.bodySmall.copy(color = p.inkSoft))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("LTP", style = Type.label.copy(color = p.inkSoft, fontSize = 11.sp))
                        Text(pick.ltp?.let { "₹%.2f".format(Locale.ENGLISH, it) } ?: "—", style = Type.figureLarge.copy(color = p.ink, fontSize = 22.sp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                // Buy / Sell
                Row(Modifier.fillMaxWidth().background(p.chip, RoundedCornerShape(50)).padding(3.dp)) {
                    listOf(true to "BUY", false to "SELL").forEach { (isBuy, label) ->
                        val sel = buy == isBuy
                        Text(label, textAlign = TextAlign.Center,
                            style = Type.label.copy(color = if (sel) Color.White else p.inkSoft, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                            modifier = Modifier.weight(1f).background(if (sel) (if (isBuy) p.verdigris else p.oxblood) else Color.Transparent, RoundedCornerShape(50))
                                .clickable { buy = isBuy }.padding(vertical = 10.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                // Lots
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Lots", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp))
                        Text("$qty qty", style = Type.bodySmall.copy(color = p.inkFaint))
                    }
                    Stepper("−") { if (lots > 1) lots-- }
                    Text("$lots", style = Type.figureLarge.copy(color = p.ink, fontSize = 22.sp), textAlign = TextAlign.Center, modifier = Modifier.size(width = 56.dp, height = 32.dp))
                    Stepper("+") { if (lots < 50) lots++ }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Token("Market", !limit) { limit = false }
                    Token("Limit", limit) { limit = true }
                    Spacer(Modifier.weight(1f))
                    Token("NRML", product == "NRML") { product = "NRML" }
                    Token("MIS", product == "MIS") { product = "MIS" }
                }
                if (limit) OutlinedTextField(price, { price = it.filter { c -> c.isDigit() || c == '.' }.take(10) }, label = { Text("Limit price") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Spacer(Modifier.height(12.dp))
                Row {
                    Text("Approx. value", style = Type.bodySmall.copy(color = p.inkSoft), modifier = Modifier.weight(1f))
                    Text(px?.let { "₹%,.0f".format(Locale.ENGLISH, it * qty) } ?: "—", style = Type.figure.copy(color = p.ink))
                }
                Spacer(Modifier.height(14.dp))
                val ok = !limit || price.toDoubleOrNull()?.let { it > 0 } == true
                if (s.live) {
                    BrassButton("Review ${if (buy) "buy" else "sell"} order", Modifier.fillMaxWidth(), enabled = ok, tone = side) {
                        model.planManual(pick.underlying, pick.expiry, pick.strike, pick.right,
                            if (buy) com.optionslab.engine.Kite.Side.BUY else com.optionslab.engine.Kite.Side.SELL, lots, product, if (limit) price.toDoubleOrNull() else null)
                        onClose()
                    }
                    Note("Live: Zerodha. The order opens for review; it is sent only after you hold the button and confirm with your PIN or fingerprint.")
                } else {
                    BrassButton("${if (buy) "Buy" else "Sell"} (paper)", Modifier.fillMaxWidth(), enabled = ok, tone = side) {
                        model.paperPlace(pick.underlying, pick.expiry, pick.strike, pick.right, if (buy) "BUY" else "SELL", lots,
                            if (limit) "LIMIT" else "MARKET", product, if (limit) price.toDoubleOrNull() else null, null)
                        onClose()
                    }
                    Note("Paper: simulated in your paper account. Nothing reaches Zerodha.")
                }
            }
        }
    }
}

@Composable
private fun Stepper(label: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.size(36.dp).background(p.chip, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, style = Type.title.copy(color = p.ink, fontSize = 18.sp))
    }
}
