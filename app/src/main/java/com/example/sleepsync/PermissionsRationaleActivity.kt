package com.example.sleepsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Health Connect only lists apps that expose this privacy-policy screen.
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SleepTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(modifier = Modifier.systemBarsPadding().padding(24.dp)) {
                        Text(text = "개인정보 처리 안내", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "이 앱은 Health Connect의 수면 기록을 읽어 화면에 표시하고, " +
                                "사용자가 직접 입력한 수면 기록을 Health Connect에 저장합니다. " +
                                "지난 기록을 계속 볼 수 있도록 수면 기록 사본을 이 기기의 앱 저장소에 보관합니다. " +
                                "홈 화면 위젯을 최신으로 유지하기 위해 앱이 꺼져 있을 때도 약 1시간마다 수면 기록을 읽습니다. " +
                                "수면 데이터를 외부 서버로 전송하지 않으며, 기기의 Android 백업이 켜져 있으면 " +
                                "사용자의 Google 계정 백업에 포함될 수 있습니다.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
