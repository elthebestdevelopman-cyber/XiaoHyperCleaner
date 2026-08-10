package com.xiaohypercleaner.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaohypercleaner.R
import com.xiaohypercleaner.ui.theme.Blue500
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.launch

data class OnboardingPage(
    val icon: Int,
    val title: Int,
    val description: Int
)

@Composable
fun OnboardingScreen(
    isDark: Boolean,
    onFinish: () -> Unit
) {
    val pages = listOf(
        OnboardingPage(
            icon = R.drawable.ic_robot_companion,
            title = R.string.onboarding_page1_title,
            description = R.string.onboarding_page1_text
        ),
        OnboardingPage(
            icon = R.drawable.ic_yarn_ball,
            title = R.string.onboarding_page2_title,
            description = R.string.onboarding_page2_text
        ),
        OnboardingPage(
            icon = R.drawable.ic_robot_companion,
            title = R.string.onboarding_page3_title,
            description = R.string.onboarding_page3_text
        )
    )

    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = if (isDark) {
                        listOf(
                            com.xiaohypercleaner.ui.theme.DarkGradientStart,
                            com.xiaohypercleaner.ui.theme.DarkGradientEnd
                        )
                    } else {
                        listOf(
                            com.xiaohypercleaner.ui.theme.GradientStart,
                            com.xiaohypercleaner.ui.theme.GradientEnd
                        )
                    }
                )
            )
            .padding(24.dp)
    ) {
        // Кнопка "Пропустить"
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = {
                AppLog.i("Onboarding", "skipped")
                onFinish()
            }) {
                Text(stringResource(R.string.onboarding_skip))
            }
        }

        // Pager с страницами
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            OnboardingPageContent(pages[page])
        }

        // Индикаторы страниц
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(pages.size) { index ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (pagerState.currentPage == index) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (pagerState.currentPage == index) Blue500
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                )
            }
        }

        // Кнопка "Далее" / "Начать"
        Button(
            onClick = {
                if (pagerState.currentPage < pages.size - 1) {
                    scope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    }
                } else {
                    AppLog.i("Onboarding", "completed")
                    onFinish()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Blue500)
        ) {
            Text(
                if (pagerState.currentPage < pages.size - 1) {
                    stringResource(R.string.onboarding_next)
                } else {
                    stringResource(R.string.onboarding_start)
                },
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(page.icon),
            contentDescription = null,
            modifier = Modifier.size(160.dp)
        )
        Spacer(Modifier.height(32.dp))
        Text(
            text = stringResource(page.title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(page.description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
    }
}