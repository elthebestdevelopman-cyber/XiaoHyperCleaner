package com.xiaohypercleaner.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaohypercleaner.R
import com.xiaohypercleaner.ui.theme.GradientEnd
import com.xiaohypercleaner.ui.theme.GradientStart
import kotlinx.coroutines.delay

@SuppressLint("CustomSplashScreen")
class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SplashContent {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
    }
}

@Composable
private fun SplashContent(onFinished: () -> Unit) {
    val infinite = rememberInfiniteTransition(label = "splash")
    val catY by infinite.animateFloat(
        initialValue = 0f, targetValue = -14f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "cat"
    )
    val yarnX by infinite.animateFloat(
        initialValue = -50f, targetValue = 50f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "yarn"
    )
    val yarnRot by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900)),
        label = "rot"
    )
    LaunchedEffect(Unit) {
        delay(2200)
        onFinished()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(GradientStart, GradientEnd)))
    ) {
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box {
                Image(
                    painterResource(R.drawable.ic_robot_companion), null,
                    Modifier
                        .size(140.dp)
                        .offset(y = catY.dp)
                )
                Image(
                    painterResource(R.drawable.ic_yarn_ball), null,
                    Modifier
                        .size(44.dp)
                        .align(Alignment.BottomCenter)
                        .offset(x = yarnX.dp, y = 10.dp)
                        .rotate(yarnRot)
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(
                stringResource(R.string.app_name),
                fontSize = 28.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.splash_tagline),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}