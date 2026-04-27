package com.example.graduationproject.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.graduationproject.ui.components.ScaleButton
import com.example.graduationproject.ui.theme.GraduationProjectTheme
import com.example.graduationproject.ui.theme.scaledSp

private val BeigeBg = Color(0xFFFDFCF9)
private val TextMain = Color(0xFF201A18)
private val TextSub = Color(0xFF5D5D5D)
private val ErrorRed = Color(0xFFD32F2F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onLogout: () -> Unit = {}
) {
    var showLogoutDialog by remember { mutableStateOf(false) }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("登出帳號", fontWeight = FontWeight.Bold) },
            text = { Text("您確定要登出目前帳號嗎？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        onLogout()
                    }
                ) {
                    Text("確定登出", color = ErrorRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("系統設定", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BeigeBg,
                    titleContentColor = TextMain
                )
            )
        },
        containerColor = BeigeBg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = "個人化設定",
                fontSize = 24.scaledSp(),
                fontWeight = FontWeight.Bold,
                color = TextMain
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "目前暫無可調整的設定。",
                        fontSize = 18.scaledSp(),
                        color = TextSub
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // 修正：使用專案統一的 ScaleButton 樣式，並設定為紅色系以符合登出警示
            ScaleButton(
                onClick = { showLogoutDialog = true },
                text = "登出目前帳號",
                containerColor = Color.White,
                contentColor = ErrorRed,
                icon = Icons.AutoMirrored.Filled.Logout,
                fontSize = 20.scaledSp()
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    GraduationProjectTheme {
        SettingsScreen(onNavigateBack = {}, onLogout = {})
    }
}
