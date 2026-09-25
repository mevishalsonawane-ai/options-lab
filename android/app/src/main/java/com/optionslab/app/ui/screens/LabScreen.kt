package com.optionslab.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.Token

/** The research bench: the strategy's trials (arms), its health checks, and the portfolio and SIP backtesters. */
@Composable
fun LabScreen(model: AppModel, page: String, onPage: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Token("Backtests", page == "trials") { onPage("trials") }
            Token("Health", page == "health") { onPage("health") }
            Token("Portfolio", page == "portfolio") { onPage("portfolio") }
            Token("SIP", page == "sip") { onPage("sip") }
        }
        Box(Modifier.weight(1f)) {
            when (page) {
                "health" -> HealthScreen(model)
                "portfolio" -> PortfolioLab(model)
                "sip" -> SipLab(model)
                else -> TrialsScreen(model)
            }
        }
    }
}
