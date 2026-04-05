package com.example.graduationproject.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.graduationproject.ui.theme.GraduationProjectTheme
import com.example.graduationproject.ui.theme.scaledSp

private val BeigeBg = Color(0xFFFDFCF9)
private val PrimaryPeach = Color(0xFFFF8A65)
private val TextMain = Color(0xFF201A18)

@Composable
fun SurveyScreen(
    currentQuestionIndex: Int,
    onProgressUpdate: (Int) -> Unit,
    onComplete: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val questions = listOf(
        "您最近一週是否有規律運動？",
        "您是否能獨立上下樓梯？",
        "您是否感到關節不適？",
        "您的平衡感目前如何？",
        "您是否需要輔助具行走？"
    )

    val options = listOf("是的", "偶爾", "不是")

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = BeigeBg
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 進度指示
            LinearProgressIndicator(
                progress = { (currentQuestionIndex + 1).toFloat() / questions.size },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .padding(bottom = 32.dp),
                color = PrimaryPeach,
                trackColor = PrimaryPeach.copy(alpha = 0.2f)
            )

            // 問題卡片
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                shape = RoundedCornerShape(32.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "問題 ${currentQuestionIndex + 1}",
                        fontSize = 20.scaledSp(),
                        color = PrimaryPeach,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = questions[currentQuestionIndex],
                        fontSize = 28.scaledSp(),
                        fontWeight = FontWeight.ExtraBold,
                        color = TextMain,
                        textAlign = TextAlign.Center,
                        lineHeight = 36.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 選項
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                options.forEach { option ->
                    Button(
                        onClick = {
                            if (currentQuestionIndex < questions.size - 1) {
                                onProgressUpdate(currentQuestionIndex + 1)
                            } else {
                                onComplete()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        border = BorderStroke(2.dp, PrimaryPeach.copy(alpha = 0.3f)),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                    ) {
                        Text(
                            text = option,
                            fontSize = 22.scaledSp(),
                            fontWeight = FontWeight.Bold,
                            color = PrimaryPeach
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            TextButton(onClick = onNavigateBack) {
                Text("暫時離開並儲存進度", fontSize = 18.scaledSp(), color = TextMain.copy(alpha = 0.5f))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SurveyScreenPreview() {
    GraduationProjectTheme {
        SurveyScreen(
            currentQuestionIndex = 0,
            onProgressUpdate = {},
            onComplete = {},
            onNavigateBack = {}
        )
    }
}
