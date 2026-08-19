package com.xiaohypercleaner.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
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
    val uriHandler = LocalUriHandler.current

    // ✅ ИСПРАВЛЕНО: URL берётся из strings.xml (был захардкожен)
    val privacyUrl = stringResource(R.string.privacy_policy_url)

    var privacyAccepted by remember { mutableStateOf(false) }
    val isLastPage = pagerState.currentPage == pages.size - 1

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

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            OnboardingPageContent(pages[page])
        }

        if (isLastPage) {
            PrivacyPolicyCheckbox(
                accepted = privacyAccepted,
                privacyUrl = privacyUrl,
                onAcceptedChange = { privacyAccepted = it },
                onLinkClick = {
                    try {
                        uriHandler.openUri(privacyUrl)
                        AppLog.i("Onboarding", "privacy policy link opened")
                    } catch (e: Exception) {
                        AppLog.w("Onboarding", "failed to open privacy policy: ${e.message}")
                    }
                }
            )
            Spacer(Modifier.height(16.dp))
        }

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

        Button(
            onClick = {
                if (pagerState.currentPage < pages.size - 1) {
                    scope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    }
                } else {
                    AppLog.i("Onboarding", "completed, privacyAccepted=$privacyAccepted")
                    onFinish()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = !isLastPage || privacyAccepted,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Blue500)
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
private fun PrivacyPolicyCheckbox(
    accepted: Boolean,
    privacyUrl: String,
    onAcceptedChange: (Boolean) -> Unit,
    onLinkClick: () -> Unit
) {
    // ✅ ИСПРАВЛЕНО: текст локализован через strings.xml
    val annotatedText = buildAnnotatedString {
        append(stringResource(R.string.onboarding_privacy_prefix))
        pushStringAnnotation(tag = "URL", annotation = privacyUrl)
        withStyle(
            style = SpanStyle(
                color = Blue500,
                textDecoration = TextDecoration.Underline,
                fontWeight = FontWeight.Medium
            )
        ) {
            append(stringResource(R.string.onboarding_privacy_link))
        }
        pop()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onAcceptedChange(!accepted) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = accepted,
            onCheckedChange = onAcceptedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = Blue500,
                uncheckedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = annotatedText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.clickable { onLinkClick() }
        )
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
// ✅ УДАЛЕНО: private const val PRIVACY_POLICY_URL (захардкоженный URL)