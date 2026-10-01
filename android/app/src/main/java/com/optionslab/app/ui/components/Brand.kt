package com.optionslab.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.R
import com.optionslab.app.ui.theme.LocalPalette

/**
 * The IraAlgo emblem - the circle and rising arrow from the brand logo.
 * On unlock it simply fades out.
 */
@Composable
fun BrandEmblem(size: Dp, modifier: Modifier = Modifier, unlocked: Boolean = false, calm: Boolean = false) {
    val lift by animateFloatAsState(if (unlocked && !calm) 1f else 0f, tween(200), label = "lift")
    Image(
        painter = painterResource(R.drawable.iraalgo_emblem),
        contentDescription = BrandName.name,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .size(size)
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
            Modifier.size(44.dp).then(if (p.dark && !com.optionslab.app.BuildConfig.GOLD) Modifier.background(Color.White, RoundedCornerShape(12.dp)) else Modifier).padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.iraalgo_emblem), contentDescription = null, contentScale = ContentScale.Fit)
        }
        androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
        androidx.compose.foundation.layout.Column {
            androidx.compose.material3.Text(BrandName.name, style = com.optionslab.app.ui.theme.Type.masthead.copy(color = p.ink, fontSize = 26.sp))
            androidx.compose.material3.Text(BrandName.line, style = com.optionslab.app.ui.theme.Type.bodySmall.copy(color = p.inkSoft))
        }
    }
}

/** The app's name and line: IraAlgo, or IraGoldAlgo in the gold build. */
object BrandName {
    val name: String get() = if (com.optionslab.app.BuildConfig.GOLD) "IraGoldAlgo" else "IraAlgo"
    val line: String get() = if (com.optionslab.app.BuildConfig.GOLD) "Gold liquidity, on paper" else "Intelligent algorithmic trading"
}

/** The logo held on screen when the app is opened fresh (not on a rotation or a return from the background). */
object Splash {
    const val MS = 2_500L
    /** Off in the JVM tests, which open the app many times over. */
    @Volatile var enabled = true
}

/** The IraAlgo emblem and name, centred on the app's ground: shown for [Splash.MS] as the app opens. */
@Composable
fun SplashScreen() {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize().background(p.paper), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(140.dp).then(if (p.dark && !com.optionslab.app.BuildConfig.GOLD) Modifier.background(Color.White, RoundedCornerShape(32.dp)) else Modifier).padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.iraalgo_emblem), contentDescription = BrandName.name, contentScale = ContentScale.Fit)
            }
            androidx.compose.foundation.layout.Spacer(Modifier.size(20.dp))
            androidx.compose.material3.Text(BrandName.name, style = com.optionslab.app.ui.theme.Type.masthead.copy(color = p.ink, fontSize = 34.sp))
            androidx.compose.material3.Text(BrandName.line, style = com.optionslab.app.ui.theme.Type.bodySmall.copy(color = p.inkSoft))
        }
    }
}
