package com.example.graduationproject

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.graduationproject.ui.screens.ElderlyDashboard
import com.example.graduationproject.ui.screens.LoginScreen
import com.example.graduationproject.ui.screens.RegisterScreen
import com.example.graduationproject.ui.theme.GraduationProjectTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GraduationProjectTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "login"
    ) {
        // 登入頁面
        composable("login") {
            LoginScreen(
                onNavigateToRegister = {
                    navController.navigate("register")
                },
                // 💡 修正：接收 API 傳回的 role 與 accountId
                onLoginSuccess = { role, accountId ->

                    // 這裡未來可以加入 SharedPreferences，把 accountId 存進手機本機

                    // 💡 優化：根據不同的身分 (role) 跳轉到專屬的首頁
                    when (role) {
                        "elder" -> {
                            navController.navigate("elder_home") {
                                // 清空返回棧，防止按返回鍵回到登入頁
                                popUpTo("login") { inclusive = true }
                            }
                        }
                        "doctor" -> {
                            // 假設未來有醫療人員儀表板
                            // navController.navigate("doctor_dashboard") { ... }
                        }
                        "family" -> {
                            // 假設未來有家屬觀看頁面
                            // navController.navigate("family_home") { ... }
                        }
                        else -> {
                            // 預設防呆機制，跳轉到長者首頁
                            navController.navigate("elder_home") {
                                popUpTo("login") { inclusive = true }
                            }
                        }
                    }
                }
            )
        }

        // 註冊頁面
        composable("register") {
            RegisterScreen(
                onNavigateBackToLogin = {
                    navController.popBackStack()
                }
            )
        }

        // 首頁
        composable("home") {
            ElderlyDashboard()
        }
    }
}
