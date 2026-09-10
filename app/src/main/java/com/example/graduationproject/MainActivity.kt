package com.example.graduationproject

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.graduationproject.DataClass.SaveAssessmentRequest
import com.example.graduationproject.api.ApiClient
import com.example.graduationproject.ui.screens.ElderlyDashboard
import com.example.graduationproject.ui.screens.ForgotPasswordScreen
import com.example.graduationproject.ui.screens.LoginScreen
import com.example.graduationproject.ui.screens.RegisterScreen
import com.example.graduationproject.ui.screens.SettingsScreen
import com.example.graduationproject.ui.screens.SurveyScreen
import com.example.graduationproject.ui.theme.GraduationProjectTheme
import com.example.graduationproject.ui.theme.LocalFontScale
import kotlinx.coroutines.launch
import com.google.mediapipe.examples.poselandmarker.MainActivity as CameraActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val fontScale by remember { mutableFloatStateOf(1.0f) }

            GraduationProjectTheme(fontScale = fontScale) {
                CompositionLocalProvider(LocalFontScale provides fontScale) {
                    AppNavigation()
                }
            }
        }
    }
}

@Composable
fun AppNavigation(userViewModel: UserViewModel = viewModel()) {
    val navController = rememberNavController()
    val context = LocalContext.current

    val sharedPreferences = remember {
        context.getSharedPreferences("ElderCarePrefs", Context.MODE_PRIVATE)
    }

    val savedAccountId = sharedPreferences.getInt("ACCOUNT_ID", -1)
    var globalAccountId by remember { mutableIntStateOf(savedAccountId) }

    val initialRoute = if (savedAccountId != -1) "home" else "login"

    NavHost(
        navController = navController,
        startDestination = initialRoute
    ) {
        composable("login") {
            LoginScreen(
                onNavigateToRegister = { navController.navigate("register") },
                onNavigateToForgotPassword = { navController.navigate("forgot_password") },
                onLoginSuccess = { role, accountId ->
                    globalAccountId = accountId
                    sharedPreferences.edit().putInt("ACCOUNT_ID", accountId).apply()
                    navController.navigate("home") {
                        popUpTo("login") { inclusive = true }
                    }
                }
            )
        }

        composable("register") {
            RegisterScreen(onNavigateBackToLogin = { navController.popBackStack() })
        }

        composable("forgot_password") {
            ForgotPasswordScreen(
                onNavigateBackToLogin = { navController.popBackStack() },
                onResetSuccess = { navController.popBackStack() }
            )
        }

        composable("home") {
            ElderlyDashboard(
                accountId = globalAccountId,
                isSurveyComplete = userViewModel.isSurveyComplete,
                userLevel = userViewModel.userLevel,
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToSurvey = { navController.navigate("survey") },
                onStartTraining = { exerciseId ->
                    val intent = Intent(context, CameraActivity::class.java)
                    val targetFragment = exerciseId?.let(::resolveCameraFragment)
                    if (targetFragment != null) {
                        intent.putExtra(CameraActivity.EXTRA_TARGET_FRAGMENT, targetFragment)
                    }
                    intent.putExtra(CameraActivity.EXTRA_ACCOUNT_ID, globalAccountId)
                    intent.putExtra(CameraActivity.EXTRA_USER_LEVEL, userViewModel.userLevel)
                    context.startActivity(intent)
                }
            )
        }

        composable("survey") {
            val coroutineScope = rememberCoroutineScope()
            val localContext = LocalContext.current

            SurveyScreen(
                onComplete = { grade, score, hasFallRisk ->
                    coroutineScope.launch {
                        try {
                            val request = com.example.graduationproject.DataClass.SaveAssessmentRequest(
                                account_id = globalAccountId,
                                sppb_score = score,
                                grade = grade,
                                has_fall_risk = hasFallRisk
                            )

                            val response = ApiClient.apiService.saveAssessment(request)

                            if (response.isSuccessful && response.body()?.success == true) {
                                Toast.makeText(localContext, "評估結果已成功紀錄", Toast.LENGTH_SHORT).show()
                                userViewModel.completeSurvey(grade)
                                navController.popBackStack()
                            } else {
                                Toast.makeText(localContext, "儲存失敗：${response.body()?.message}", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(localContext, "網路連線異常：${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onLogout = {
                    sharedPreferences.edit().remove("ACCOUNT_ID").apply()
                    globalAccountId = -1
                    navController.navigate("login") {
                        popUpTo("home") { inclusive = true }
                    }
                }
            )
        }
    }
}
private const val CAMERA_FRAGMENT_CAMERA = "camera_fragment"
private const val CAMERA_FRAGMENT_HOME = "home_fragment"
private const val CAMERA_FRAGMENT_STRETCH = "stretch_fragment"
private const val CAMERA_FRAGMENT_CHAIR_STAND = "chair_stand_fragment"
private const val CAMERA_FRAGMENT_WALKING = "walking_fragment"
private const val CAMERA_FRAGMENT_SIMULATED_SITTING = "simulated_sitting_fragment"
private const val CAMERA_FRAGMENT_TOE_HEEL_WALKING = "toe_heel_walking_fragment"
private const val CAMERA_FRAGMENT_CHAIR_ARM_STRETCH = "chair_arm_stretch_fragment"
private const val CAMERA_FRAGMENT_OBSTACLE_CROSSING = "obstacle_crossing_fragment"
private const val CAMERA_FRAGMENT_BOTTLE_LIFT = "bottle_lift_fragment"
private const val CAMERA_FRAGMENT_SQUEEZE_BALL = "squeeze_ball_fragment"
private const val CAMERA_FRAGMENT_WRING_TOWEL = "wring_towel_fragment"
private const val CAMERA_FRAGMENT_BALANCE_TEST = "balance_test_fragment"
private const val CAMERA_FRAGMENT_FIGURE8_WALKING = "figure8_walking_fragment"
private const val CAMERA_FRAGMENT_LEG_STRETCH = "leg_stretch_fragment"
private const val CAMERA_FRAGMENT_WEIGHTED_LEG_STRETCH = "weighted_leg_stretch_fragment"
private const val CAMERA_FRAGMENT_STAIR_CLIMBING = "stair_climbing_fragment"

private fun resolveCameraFragment(exerciseId: String): String? {
    return when (exerciseId) {
        "A1", "B7", "C8", "D9" -> CAMERA_FRAGMENT_WALKING
        "A2", "B2" -> CAMERA_FRAGMENT_SQUEEZE_BALL
        "A3", "B1", "C2", "D2" -> CAMERA_FRAGMENT_BOTTLE_LIFT
        "A4" -> CAMERA_FRAGMENT_WEIGHTED_LEG_STRETCH
        "A5", "C3", "D3" -> CAMERA_FRAGMENT_CHAIR_STAND
        "A6" -> CAMERA_FRAGMENT_CAMERA
        "A7", "B6", "C7", "D7" -> CAMERA_FRAGMENT_STRETCH
        "B3" -> CAMERA_FRAGMENT_SIMULATED_SITTING
        "B4" -> CAMERA_FRAGMENT_TOE_HEEL_WALKING
        "B5" -> CAMERA_FRAGMENT_CHAIR_ARM_STRETCH
        "C1", "D1" -> CAMERA_FRAGMENT_WRING_TOWEL
        "C4" -> CAMERA_FRAGMENT_OBSTACLE_CROSSING
        "C5", "D6" -> CAMERA_FRAGMENT_FIGURE8_WALKING
        "C6", "D8" -> CAMERA_FRAGMENT_LEG_STRETCH
        "D4" -> CAMERA_FRAGMENT_STAIR_CLIMBING
        "D5" -> CAMERA_FRAGMENT_WALKING
        else -> CAMERA_FRAGMENT_HOME
    }
}