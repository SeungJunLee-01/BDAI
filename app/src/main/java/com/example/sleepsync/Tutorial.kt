package com.example.sleepsync

import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class TutorialStore(context: Context) {
    private val prefs = context.getSharedPreferences("tutorial", Context.MODE_PRIVATE)

    fun isDone(): Boolean = prefs.getBoolean(DONE_KEY, false)

    fun markDone() = prefs.edit().putBoolean(DONE_KEY, true).apply()

    private companion object {
        const val DONE_KEY = "done"
    }
}

private class TutorialPage(val title: String, val body: String, val points: List<String>)

private val pages = listOf(
    TutorialPage(
        title = "수면 부채란?",
        body = "목표보다 덜 잔 시간이 쌓인 거예요.\n" +
            "목표가 8시간인데 6시간 잤다면, 모자란 2시간이 부채로 쌓여요.",
        points = listOf(
            "최근 7일을 보고, 최근 날일수록 크게 반영해요.",
            "목표보다 더 잔 날은 부채를 줄여 줘요.",
            "워치를 안 차서 기록이 없는 날은 계산에서 빠져요.",
        ),
    ),
    TutorialPage(
        title = "워치 기록 불러오기",
        body = "갤럭시 워치나 핏이 잰 수면을 Health Connect를 거쳐 가져와요.",
        points = listOf(
            "다음 화면에서 '수면' 권한을 허용해 주세요.",
            "기록이 안 보이면 삼성 헬스 설정의 Health Connect 메뉴에서 수면 공유를 켜 주세요.",
            "워치를 깜빡하고 안 찬 날은 '수면 추가'로 직접 넣을 수 있어요.",
        ),
    ),
    TutorialPage(
        title = "이렇게 써요",
        body = "아침에 열어서 부채와 최근 7일 그래프만 보면 돼요.",
        points = listOf(
            "오른쪽 위 설정에서 목표 수면 시간과 요일별 기상 목표를 정해요.",
            "기상 목표를 정하면 오늘 밤 몇 시에 자면 좋을지 알려 드려요.",
            "그래프 막대를 누르면 그날 기록을 볼 수 있어요.",
        ),
    ),
)

// Shown once, before the first Health Connect permission request.
@Composable
fun TutorialScreen(onFinish: () -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val page = pages[index]
    val isLast = index == pages.lastIndex
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    BackHandler(enabled = index > 0) { index-- }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.systemBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (!isLast) TextButton(onClick = onFinish) { Text("건너뛰기") } else Spacer(Modifier.height(48.dp))
            }

            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "${index + 1} / ${pages.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = page.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = page.body, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(20.dp))
                SectionCard {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        page.points.forEach { point ->
                            Row {
                                Text(text = "•", style = MaterialTheme.typography.bodyMedium, color = muted)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = point, style = MaterialTheme.typography.bodyMedium, color = muted)
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                pages.indices.forEach { i ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (i == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            ),
                    )
                }
            }
            Button(
                onClick = { if (isLast) onFinish() else index++ },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (isLast) "시작하기" else "다음") }
        }
    }
}
