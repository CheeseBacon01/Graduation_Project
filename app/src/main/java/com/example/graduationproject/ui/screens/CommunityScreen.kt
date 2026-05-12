package com.example.graduationproject.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.rounded.*
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.graduationproject.CommunityViewModel
import com.example.graduationproject.DataClass.CommunityUser
import com.example.graduationproject.DataClass.FriendRequest
import com.example.graduationproject.ui.theme.GraduationProjectTheme
import com.example.graduationproject.ui.theme.scaledSp
import java.util.Calendar

// 延續專案色調
private val BeigeBg = Color(0xFFFDFCF9)
private val PrimaryPeach = Color(0xFFFF8A65)
private val SecondaryTeal = Color(0xFF4DB6AC)
private val TextMain = Color(0xFF201A18)
private val TextSub = Color(0xFF5D5D5D)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(viewModel: CommunityViewModel = viewModel()) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("社區排行榜", "我的好友")

    // 當前使用者 (模擬)
    val currentUser = CommunityUser("0", "陳爺爺", "Lv.3 活力長青", PrimaryPeach.copy(alpha = 0.2f), 2, 1250, 15)

    // 動態計算本週日期範圍
    val dateRangeStr = remember {
        val calendar = Calendar.getInstance()
        calendar.firstDayOfWeek = Calendar.MONDAY
        while (calendar.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            calendar.add(Calendar.DATE, -1)
        }
        val startMonth = calendar.get(Calendar.MONTH) + 1
        val startDay = calendar.get(Calendar.DAY_OF_MONTH)
        calendar.add(Calendar.DATE, 6)
        val endMonth = calendar.get(Calendar.MONTH) + 1
        val endDay = calendar.get(Calendar.DAY_OF_MONTH)
        "$startMonth/$startDay - $endMonth/$endDay"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BeigeBg)
    ) {
        PrimaryTabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = BeigeBg,
            contentColor = PrimaryPeach,
            modifier = Modifier.height(72.dp),
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
                    modifier = Modifier.fillMaxHeight(),
                    text = {
                        Text(
                            text = title,
                            fontSize = 20.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) PrimaryPeach else TextSub
                        )
                    }
                )
            }
        }

        if (selectedTabIndex == 0) {
            LeaderboardTab(currentUser, dateRangeStr, viewModel)
        } else {
            FriendsTab(viewModel)
        }
    }
}

@Composable
fun LeaderboardTab(currentUser: CommunityUser, dateRangeStr: String, viewModel: CommunityViewModel) {
    Column {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = "社區排行榜",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMain
                )
                Text(
                    text = "本週排行 ($dateRangeStr)",
                    fontSize = 14.sp,
                    color = TextSub
                )
            }
            Text(
                text = "排行榜將於週日 23:59 結算",
                fontSize = 12.sp,
                color = TextSub.copy(alpha = 0.7f)
            )
        }

        MyRankHeader(user = currentUser)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(
                items = viewModel.leaderboardList,
                key = { it.id }
            ) { user ->
                CommunityUserCard(
                    user = user,
                    isMe = user.id == currentUser.id,
                    onAddFriend = { viewModel.sendFriendRequest(user.id) },
                    onDeleteFriend = { viewModel.deleteFriend(user.id) }
                )
            }
        }
    }
}

@Composable
fun FriendsTab(viewModel: CommunityViewModel) {
    var searchQuery by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "新增好友",
                    fontSize = 20.scaledSp(),
                    fontWeight = FontWeight.Bold,
                    color = TextMain
                )
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = "輸入完整手機號碼以傳送好友邀請",
                            fontSize = 18.scaledSp()
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { /* 搜尋邏輯 */ }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "搜尋",
                                tint = PrimaryPeach
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryPeach,
                        unfocusedBorderColor = TextSub.copy(alpha = 0.3f)
                    )
                )
            }
        }

        if (viewModel.pendingRequests.isNotEmpty()) {
            item {
                SectionTitle(title = "新的交友邀請", icon = Icons.Rounded.Notifications)
            }
            items(viewModel.pendingRequests) { request ->
                FriendRequestCard(
                    request = request,
                    onAccept = { viewModel.acceptRequest(request.id) },
                    onReject = { viewModel.rejectRequest(request.id) }
                )
            }
        }

        item {
            SectionTitle(title = "我的好友", icon = Icons.Rounded.People)
        }

        if (viewModel.friendList.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "目前還沒有好友喔！\n趕快從上方新增，或到排行榜認識新朋友吧！",
                        fontSize = 18.sp,
                        color = TextSub,
                        lineHeight = 28.sp,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            }
        } else {
            items(
                items = viewModel.friendList,
                key = { it.id }
            ) { friend ->
                CommunityUserCard(
                    user = friend,
                    isMe = false,
                    onAddFriend = {},
                    onDeleteFriend = { viewModel.deleteFriend(friend.id) }
                )
            }
        }
    }
}

@Composable
fun SectionTitle(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = PrimaryPeach, modifier = Modifier.size(28.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextMain)
    }
}

@Composable
fun FriendRequestCard(
    request: FriendRequest,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = Color.White),
        border = CardDefaults.outlinedCardBorder(enabled = true).copy(width = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(request.senderAvatarColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Person, null, modifier = Modifier.size(36.dp), tint = Color.White)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = request.senderName, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextMain)
                Text(text = request.senderLevel, fontSize = 14.sp, color = TextSub)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = onReject,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFFEEEEEE),
                        contentColor = TextSub
                    ),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Rounded.Close, null, modifier = Modifier.size(22.dp))
                }

                Button(
                    onClick = onAccept,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryPeach,
                        contentColor = Color.White
                    ),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Rounded.Check, null, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
fun MyRankHeader(user: CommunityUser) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        color = SecondaryTeal.copy(alpha = 0.1f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(2.dp, SecondaryTeal.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "第 ${user.rank} 名",
                    fontSize = 20.scaledSp(),
                    fontWeight = FontWeight.ExtraBold,
                    color = SecondaryTeal
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "本週累積表現", fontSize = 14.scaledSp(), color = TextSub)
                    Text(text = user.name, fontSize = 20.scaledSp(), fontWeight = FontWeight.Bold, color = TextMain)
                }
                Text(
                    text = "${user.weeklyExp} 經驗值",
                    fontSize = 22.scaledSp(),
                    fontWeight = FontWeight.Black,
                    color = SecondaryTeal
                )
            }
        }
    }
}

@Composable
fun CommunityUserCard(
    user: CommunityUser,
    isMe: Boolean,
    onAddFriend: () -> Unit,
    onDeleteFriend: () -> Unit = {}
) {
    var showAchievementDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // 使用傳入的 onDeleteFriend 建立一個穩定的回調
    val stableOnDelete = rememberUpdatedState(onDeleteFriend)

    if (showAchievementDialog) {
        AchievementDialog(user, onDismiss = { showAchievementDialog = false })
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text(text = "刪除好友", fontWeight = FontWeight.Bold) },
            text = { Text("確定要將 ${user.name} 從好友名單中移除嗎？") },
            confirmButton = {
                TextButton(onClick = {
                    stableOnDelete.value()
                    showDeleteConfirmDialog = false
                }) {
                    Text("確定刪除", color = Color.Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("取消", color = TextSub)
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(24.dp)
        )
    }

    OutlinedCard(
        onClick = { showAchievementDialog = true },
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
            Text(
                text = user.rank.toString(),
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                color = if (user.rank <= 3) PrimaryPeach else TextSub.copy(alpha = 0.3f),
                modifier = Modifier.width(36.dp)
            )

            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(user.avatarColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Person, null, modifier = Modifier.size(36.dp), tint = Color.White)
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = user.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextMain)
                Text(text = user.level, fontSize = 14.sp, color = SecondaryTeal, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.MilitaryTech,
                        contentDescription = null,
                        tint = PrimaryPeach,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${user.weeklyExp} 經驗值",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = PrimaryPeach
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 右側動態按鈕
            Box {
                when {
                    isMe -> {
                        Spacer(modifier = Modifier.width(0.dp))
                    }
                    user.isFriend -> {
                        TextButton(
                            onClick = { 
                                // 明確觸發對話框
                                showDeleteConfirmDialog = true 
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = TextSub),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "刪除好友",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "刪除",
                                fontSize = 16.scaledSp(),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    user.hasPendingRequestSent -> {
                        Text(
                            text = "已送出",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSub.copy(alpha = 0.5f),
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    else -> {
                        Button(
                            onClick = onAddFriend,
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryPeach.copy(alpha = 0.1f), contentColor = PrimaryPeach),
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.PersonAdd, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("加好友", fontSize = 16.scaledSp(), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AchievementDialog(user: CommunityUser, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("關閉", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = PrimaryPeach)
            }
        },
        title = { Text(text = "${user.name} 的訓練成就", fontSize = 24.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AchievementRow(Icons.Rounded.EmojiEvents, Color(0xFFFFD700), "本週運動：${user.weeklyExercise} 次")
                AchievementRow(Icons.Rounded.MilitaryTech, PrimaryPeach, "本週經驗值：${user.weeklyExp} 經驗值")
            }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = Color.White
    )
}

@Composable
fun AchievementRow(icon: androidx.compose.ui.graphics.vector.ImageVector, iconColor: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(28.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = text, fontSize = 20.sp, color = TextMain)
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 892)
@Composable
fun CommunityScreenPreview() {
    GraduationProjectTheme {
        CommunityScreen()
    }
}
