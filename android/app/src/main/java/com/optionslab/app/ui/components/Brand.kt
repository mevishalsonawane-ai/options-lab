package com.optionslab.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.R
import com.optionslab.app.ui.theme.LocalPalette

/**
 * The IraAlgo emblem - the circle and rising arrow from the brand logo. It
 * breathes while the app waits, and on unlock the arrow swings up and the
 * emblem lifts away.
 */
@Composable
fun BrandEmblem(size: Dp, modifier: Modifier = Modifier, unlocked: Boolean = false, calm: Boolean = false) {
    val t = rememberInfiniteTransition(label = "emblem")
    val breathe by t.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "breathe")
    val lift by animateFloatAsState(if (unlocked) 1f else 0f, tween(650), label = "lift")
    Image(
        painter = painterResource(R.drawable.iraalgo_emblem),
        contentDescription = "IraAlgo",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .size(size)
            .scale((if (calm || unlocked) 1f else breathe) * (1f + 0.35f * lift))
            .rotate(-12f * lift)
            .alpha(1f - lift),
    )
}

/**
 * The IraAlgo wordmark: the emblem and the name set in the app's type. The
 * emblem's navy would sink into a black ground, so in the dark theme it sits
 * on a small white tile.
 */
@Composable
fun BrandLogo(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    androidx.compose.foundation.layout.Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).then(if (p.dark) Modifier.background(Color.White, RoundedCornerShape(12.dp)) else Modifier).padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.iraalgo_emblem), contentDescription = null, contentScale = ContentScale.Fit)
        }
        androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
        androidx.compose.foundation.layout.Column {
            androidx.compose.material3.Text("IraAlgo", style = com.optionslab.app.ui.theme.Type.masthead.copy(color = p.ink, fontSize = 26.sp))
            androidx.compose.material3.Text("Intelligent algorithmic trading", style = com.optionslab.app.ui.theme.Type.bodySmall.copy(color = p.inkSoft))
        }
    }
}
