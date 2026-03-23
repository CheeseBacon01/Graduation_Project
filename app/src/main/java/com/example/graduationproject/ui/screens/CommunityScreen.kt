package com.example.graduationproject.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.SentimentVerySatisfied
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.graduationproject.ui.theme.GraduationProjectTheme

// 延續專案色調
private val BeigeBg = Color(0xFFFDFCF9)
private val PrimaryPeach = Color(0xFFFF8A65)
private val SecondaryTeal = Color(0xFF4DB6AC)
private val TextMain = Color(0xFF201A18)
private val TextSub = Color(0xFF5D5D5D)

data class CommunityUser(
    val name: String,
    val level: String,
    val avatarColor: Color,
    val weeklyExercise: Int,
    val todaySteps: Int,
    val initialLikes: Int,
    val rank: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen() {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("社區排行榜", "我的好友")

    val leaderboardUsers = listOf(
        CommunityUser("張奶奶", "Lv.12 健走達人", Color(0xFFFFCDD2), 5, 6230, 42, 1),
        CommunityUser("李爺爺", "Lv.10 太極宗師", Color(0xFFC8E6C9), 4, 4500, 38, 2),
        CommunityUser("王大叔", "Lv.8 活力十足", Color(0xFFBBDEFB), 6, 8100, 25, 3),
        CommunityUser("林阿姨", "Lv.7 晨練標兵", Color(0xFFF0F4C3), 3, 3200, 19, 4)
    )

    val currentUser = CommunityUser("陳爺爺", "Lv.3 活力長青", PrimaryPeach.copy(alpha = 0.2f), 2, 1250, 12, 15)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BeigeBg)
    ) {
        // 增加 TabRow 高度，並讓文字有更大的點擊面積
        PrimaryTabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = BeigeBg,
            contentColor = PrimaryPeach,
            modifier = Modifier.height(72.dp), // 增加高度至 72dp
            indicator = { TabRowDefaults.PrimaryIndicator(
                modifier = Modifier.tabIndicatorOffset(selectedTabIndex),
                width = 80.dp,
                color = PrimaryPeach
            )}
        ) {
            tabs.forEachIndexed { index, title ->
                val isSelected = selectedTabIndex == index
                Tab(
                    selected = isSelected,
                    onClick = { selectedTabIndex = index },
                    modifier = Modifier.fillMaxHeight(), // 填滿高度
                    text = { 
                        Text(
                            text = title, 
                            fontSize = 20.sp, // 稍微放大字體
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) PrimaryPeach else TextSub
                        ) 
                    }
                )
            }
        }

        // 固定顯示「我的排名」
        MyRankHeader(user = currentUser)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(leaderboardUsers) { user ->
                CommunityUserCard(user)
            }
        }
    }
}

@Composable
fun MyRankHeader(user: CommunityUser) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        color = SecondaryTeal.copy(alpha = 0.1f),
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, SecondaryTeal.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "第 ${user.rank} 名",
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = SecondaryTeal
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "我的目前表現", fontSize = 14.sp, color = TextSub)
                Text(text = user.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextMain)
            }
            Text(
                text = "${user.todaySteps} 步",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextMain
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityUserCard(user: CommunityUser) {
    var isLiked by remember { mutableStateOf(false) }
    var likesCount by remember { mutableIntStateOf(user.initialLikes) }
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("關閉", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            },
            title = { Text(text = "${user.name} 的訓練成就", fontSize = 24.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("🏆 本週運動：${user.weeklyExercise} 次", fontSize = 20.sp, color = TextMain)
                    Text("👣 今日步數：${user.todaySteps} 步", fontSize = 20.sp, color = TextMain)
                    Text("❤️ 收到愛心：$likesCount 個", fontSize = 20.sp, color = PrimaryPeach)
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = Color.White
        )
    }

    OutlinedCard(
        onClick = { showDialog = true },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = Color.White),
        border = CardDefaults.outlinedCardBorder(enabled = true).copy(width = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 排名顯示
            Text(
                text = user.rank.toString(),
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                color = TextSub.copy(alpha = 0.3f),
                modifier = Modifier.width(32.dp)
            )

            // 頭像
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(user.avatarColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.SentimentVerySatisfied, null, modifier = Modifier.size(36.dp), tint = Color.White)
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = user.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextMain)
                Text(text = user.level, fontSize = 16.sp, color = SecondaryTeal, fontWeight = FontWeight.Medium)
            }

            // 優化後的愛心按鈕
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledTonalIconButton(
                    onClick = { 
                        isLiked = !isLiked
                        if (isLiked) likesCount++ else likesCount--
                    },
                    modifier = Modifier.size(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (isLiked) PrimaryPeach.copy(alpha = 0.2f) else BeigeBg,
                        contentColor = if (isLiked) PrimaryPeach else TextSub
                    )
                ) {
                    Icon(
                        imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "送愛心",
                        modifier = Modifier.size(32.dp)
                    )
                }
                Text(
                    text = likesCount.toString(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isLiked) PrimaryPeach else TextSub
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 892)
@Composable
fun CommunityScreenPreview() {
    GraduationProjectTheme {
        CommunityScreen()
    }
}
