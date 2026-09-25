package com.optionslab.app.ui.screens

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

/** The Trade tab: your account (or the paper one) and the strategies that trade it. */
@Composable
fun TradeHub(model: AppModel, page: String, onPage: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Token("Account", page != "strategies") { onPage("account") }
            Token("Strategies", page == "strategies") { onPage("strategies") }
        }
        Box(Modifier.weight(1f)) {
            if (page == "strategies") StrategiesScreen(model) else TradeScreen(model)
        }
    }
}
