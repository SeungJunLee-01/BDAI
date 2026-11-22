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
                                "수면 데이터는 기기 밖으로 전송하지 않습니다.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
