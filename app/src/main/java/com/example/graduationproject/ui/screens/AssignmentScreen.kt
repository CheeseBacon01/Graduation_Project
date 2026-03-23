package com.example.graduationproject.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.graduationproject.ui.theme.GraduationProjectTheme

// 延續 HomeScreen 的色調
private val BeigeBg = Color(0xFFFDFCF9)
private val PrimaryPeach = Color(0xFFFF8A65)
private val SecondaryTeal = Color(0xFF4DB6AC)
private val TextMain = Color(0xFF201A18)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignmentScreen() {
    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        "訓練手冊 - 第 1 週",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMain
                    )
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = BeigeBg
                )
            )
        },
        containerColor = BeigeBg
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // 任務 1：已完成
            item {
                TaskCompletedCard()
            }

            // 任務 2：今日任務
            item {
                TaskTodayCard()
            }

            // 任務 3：未解鎖
            item {
                TaskLockedCard()
            }
        }
    }
}

@Composable
fun TaskTag(text: String, containerColor: Color, contentColor: Color) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(end = 8.dp)
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun TaskCompletedCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFEEEEEE) // 灰色填滿卡片
        )
    ) {
        Row(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 任務圖示/部位
            Box(
                modifier = Modifier
                    .size(70.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SelfImprovement,
                    contentDescription = null,
                    modifier = Modifier.size(45.dp),
                    tint = TextMain.copy(alpha = 0.3f)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TaskTag("5 mins", Color.White.copy(alpha = 0.6f), TextMain.copy(alpha = 0.5f))
                    TaskTag("輕鬆", Color.White.copy(alpha = 0.6f), TextMain.copy(alpha = 0.5f))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "基礎呼吸練習",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMain.copy(alpha = 0.5f)
                )
                Text(
                    text = "AI 準確率：92%",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = SecondaryTeal.copy(alpha = 0.7f)
                )
            }
            
            // 綠色打勾標誌
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = Color(0xFFE8F5E9)
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "已完成",
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}

@Composable
fun TaskTodayCard() {
    // 今日任務卡片強化：加粗邊框與實心按鈕
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                BorderStroke(4.dp, PrimaryPeach.copy(alpha = 0.5f)),
                RoundedCornerShape(32.dp)
            ),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = Color.White
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 動作縮圖
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(PrimaryPeach.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DirectionsRun,
                        contentDescription = null,
                        modifier = Modifier.size(50.dp),
                        tint = PrimaryPeach
                    )
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column {
                    Row {
                        TaskTag("15 mins", StatsPastelOrange, PrimaryPeach)
                        TaskTag("中等", StatsPastelBlue, Color(0xFF1976D2))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "核心平衡訓練",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextMain
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 強化：改為實心填滿按鈕 (Filled Button)
            Button(
                onClick = { /* 開始任務 */ },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryPeach,
                    contentColor = Color.White
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "現在開始訓練",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// 定義今日任務標籤用的粉彩色
private val StatsPastelBlue = Color(0xFFE3F2FD)
private val StatsPastelOrange = Color(0xFFFFF3E0)

@Composable
fun TaskLockedCard() {
    // 鎖定任務：提高透明度下限，確保文字可讀
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        border = BorderStroke(1.5.dp, TextMain.copy(alpha = 0.15f)),
        colors = CardDefaults.outlinedCardColors(
            containerColor = Color.Transparent
        )
    ) {
        Row(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(70.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(TextMain.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "未解鎖",
                    tint = TextMain.copy(alpha = 0.3f),
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row {
                    TaskTag("20 mins", Color.Transparent, TextMain.copy(alpha = 0.4f))
                    TaskTag("挑戰", Color.Transparent, TextMain.copy(alpha = 0.4f))
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "進階下肢強化",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMain.copy(alpha = 0.4f) // 提高 Alpha 確保可讀
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 892)
@Composable
fun AssignmentScreenPreview() {
    GraduationProjectTheme {
        AssignmentScreen()
    }
}
