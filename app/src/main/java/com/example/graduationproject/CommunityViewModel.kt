package com.example.graduationproject

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import com.example.graduationproject.DataClass.CommunityUser
import com.example.graduationproject.DataClass.FriendRequest

class CommunityViewModel : ViewModel() {
    // 模擬數據：已徹底移除 initialLikes
    private val _leaderboardList = mutableStateListOf<CommunityUser>(
        CommunityUser("1", "張奶奶", "Lv.12 訓練達人", Color(0xFFFFCDD2), 5, 2150, 1, isFriend = true),
        CommunityUser("2", "李爺爺", "Lv.10 復健宗師", Color(0xFFC8E6C9), 4, 1980, 2, isFriend = false),
        CommunityUser("3", "王大叔", "Lv.8 活力楷模", Color(0xFFBBDEFB), 6, 1750, 3, isFriend = false, hasPendingRequestSent = true),
        CommunityUser("4", "林阿姨", "Lv.7 全能長青樹", Color(0xFFF0F4C3), 3, 1520, 4, isFriend = true)
    )
    val leaderboardList: List<CommunityUser> get() = _leaderboardList

    private val _pendingRequests = mutableStateListOf<FriendRequest>(
        FriendRequest("r1", "趙伯伯", "Lv.5 散步愛好者", Color(0xFFE1BEE7), "10:30"),
        FriendRequest("r2", "孫婆婆", "Lv.6 太極高手", Color(0xFFFFF9C4), "昨天")
    )
    val pendingRequests: List<FriendRequest> get() = _pendingRequests

    // 好友名單：從排行榜中過濾並按經驗值排序
    val friendList: List<CommunityUser>
        get() = _leaderboardList.filter { it.isFriend }.sortedByDescending { it.weeklyExp }

    fun sendFriendRequest(userId: String) {
        val index = _leaderboardList.indexOfFirst { it.id == userId }
        if (index != -1) {
            val user = _leaderboardList[index]
            _leaderboardList[index] = user.copy(hasPendingRequestSent = true)
        }
    }

    fun acceptRequest(requestId: String) {
        val request = _pendingRequests.find { it.id == requestId }
        if (request != null) {
            val userIndex = _leaderboardList.indexOfFirst { it.name == request.senderName }
            if (userIndex != -1) {
                _leaderboardList[userIndex] = _leaderboardList[userIndex].copy(isFriend = true)
            } else {
                // 如果不在排行榜，新增進去 (模擬)
                _leaderboardList.add(
                    CommunityUser(
                        id = (_leaderboardList.size + 1).toString(),
                        name = request.senderName,
                        level = request.senderLevel,
                        avatarColor = request.senderAvatarColor,
                        weeklyExercise = 0,
                        weeklyExp = 0,
                        rank = _leaderboardList.size + 1,
                        isFriend = true
                    )
                )
            }
            _pendingRequests.remove(request)
        }
    }

    fun rejectRequest(requestId: String) {
        _pendingRequests.removeIf { it.id == requestId }
    }

    fun deleteFriend(userId: String) {
        val index = _leaderboardList.indexOfFirst { it.id == userId }
        if (index != -1) {
            val user = _leaderboardList[index]
            _leaderboardList[index] = user.copy(isFriend = false, hasPendingRequestSent = false)
        }
    }
}
