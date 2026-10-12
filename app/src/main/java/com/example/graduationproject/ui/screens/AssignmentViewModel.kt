package com.example.graduationproject.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.graduationproject.DataClass.DailyPlan
import com.example.graduationproject.DataClass.Exercise
import com.example.graduationproject.DataClass.ExerciseStatus
import com.example.graduationproject.DataClass.GetExerciseRecordsRequest
import com.example.graduationproject.api.ApiClient
import com.example.graduationproject.repository.VivifrailRepository
import com.example.graduationproject.resolveCameraExerciseRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AssignmentUiState(
    val dailyPlan: DailyPlan? = null,
    val exercises: List<Exercise> = emptyList(),
    val completedExerciseIds: Set<String> = emptySet(),
    val isLoadingRecords: Boolean = false,
    val loadError: String? = null
)

internal data class TrainingAttempt(
    val id: Long,
    val exerciseId: String,
    val accountId: Int,
    val level: String,
    val week: Int,
    val trainingDate: String
)

class AssignmentViewModel(
    private val repository: VivifrailRepository = VivifrailRepository()
) : ViewModel() {
    private var accountId: Int = -1
    private var level: String = ""
    private var day: Int = 1
    private var week: Int = 1
    private var trainingDate: String = ""
    private var programCompleted: Boolean = false
    private var completedIds: Set<String> = emptySet()
    private var isLoading = false
    private var loadError: String? = null
    private var loadJob: Job? = null
    private var loadVersion = 0

    private var nextTrainingAttemptId = 1L
    private var activeTrainingAttempt: TrainingAttempt? = null

    private val _uiState = MutableStateFlow(AssignmentUiState())
    val uiState: StateFlow<AssignmentUiState> = _uiState.asStateFlow()

    private fun currentPlan(): DailyPlan? {
        if (accountId <= 0 || level.isBlank() || programCompleted) return null
        return repository.getDailyPlan(level, day, week)
    }

    private fun refreshUiState() {
        val plan = currentPlan()
        var foundCurrent = false
        val exercises = plan?.exercises?.map { exercise ->
            when {
                exercise.id in completedIds -> exercise.copy(status = ExerciseStatus.COMPLETED)
                !foundCurrent -> {
                    foundCurrent = true
                    exercise.copy(status = ExerciseStatus.CURRENT)
                }
                else -> exercise.copy(status = ExerciseStatus.LOCKED)
            }
        } ?: emptyList()

        _uiState.value = AssignmentUiState(
            dailyPlan = plan,
            exercises = exercises,
            completedExerciseIds = completedIds,
            isLoadingRecords = isLoading,
            loadError = loadError
        )
    }

    /** 每當帳號、日期、評估等級或訓練週次改變，就重新讀取對應日期的完成紀錄。 */
    fun updateParams(
        accountId: Int,
        level: String,
        day: Int,
        week: Int,
        trainingDate: String,
        programCompleted: Boolean = false
    ) {
        val validLevel = level.trim().uppercase().takeIf { it.firstOrNull() in 'A'..'D' } ?: ""
        val validWeek = week.coerceIn(1, 12)
        val validDay = day.coerceIn(1, 7)
        if (this.accountId == accountId && this.level == validLevel &&
            this.day == validDay && this.week == validWeek &&
            this.trainingDate == trainingDate && this.programCompleted == programCompleted
        ) return

        loadJob?.cancel()
        loadVersion++
        activeTrainingAttempt = null
        completedIds = emptySet()
        this.accountId = accountId
        this.level = validLevel
        this.day = validDay
        this.week = validWeek
        this.trainingDate = trainingDate
        this.programCompleted = programCompleted
        loadError = null
        isLoading = false
        refreshUiState()
        reloadRecords()
    }

    /** 重新開啟任務頁、結束相機訓練後，都要向伺服器核對。 */
    fun reloadRecords() {
        loadJob?.cancel()
        val requestVersion = ++loadVersion
        val plan = currentPlan()
        if (plan == null || plan.exercises.isEmpty() || trainingDate.isEmpty()) {
            completedIds = emptySet()
            loadError = null
            isLoading = false
            refreshUiState()
            return
        }

        val requestedAccountId = accountId
        val requestedDate = trainingDate
        isLoading = true
        loadError = null
        refreshUiState()

        loadJob = viewModelScope.launch {
            try {
                val response = ApiClient.apiService.getExerciseRecords(
                    GetExerciseRecordsRequest(requestedAccountId, requestedDate)
                )
                val body = response.body()
                if (!response.isSuccessful || body?.success != true) {
                    throw IllegalStateException(body?.message ?: "HTTP ${response.code()}")
                }
                // 絕不跨帳號或日期套用舊請求的結果。
                if (requestVersion != loadVersion || requestedAccountId != accountId || requestedDate != trainingDate) return@launch
                val savedCodes = body.records.map { it.exerciseCode }.toSet()
                completedIds = plan.exercises.filter { exercise ->
                    resolveCameraExerciseRoute(exercise.id, week)?.resultCode in savedCodes
                }.map { it.id }.toSet()
                loadError = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestVersion != loadVersion || requestedAccountId != accountId || requestedDate != trainingDate) return@launch
                loadError = "訓練紀錄讀取失敗：${e.message ?: "請確認網路連線"}"
            } finally {
                if (requestVersion == loadVersion && requestedAccountId == accountId && requestedDate == trainingDate) {
                    isLoading = false
                    refreshUiState()
                }
            }
        }
    }

    internal fun beginTraining(exerciseId: String): TrainingAttempt? {
        if (isLoading || loadError != null || activeTrainingAttempt != null ||
            currentExerciseId() != exerciseId || accountId <= 0
        ) return null
        return TrainingAttempt(
            id = nextTrainingAttemptId++,
            exerciseId = exerciseId,
            accountId = accountId,
            level = level,
            week = week,
            trainingDate = trainingDate
        ).also { activeTrainingAttempt = it }
    }

    internal fun applyTrainingResult(attemptId: Long, exerciseId: String, completed: Boolean): Boolean {
        val attempt = activeTrainingAttempt
        if (attempt?.id != attemptId || attempt.exerciseId != exerciseId) return false
        activeTrainingAttempt = null
        val isSamePlan = attempt.accountId == accountId && attempt.trainingDate == trainingDate &&
                attempt.week == week && attempt.level == level
        if (completed && isSamePlan && currentExerciseId() == exerciseId) {
            completedIds = completedIds + exerciseId
            refreshUiState()
        }
        // 不論相機有沒有成功回傳，真正的完成紀錄仍以資料庫為準。
        reloadRecords()
        return completed && isSamePlan
    }

    internal fun cancelActiveTraining() {
        activeTrainingAttempt = null
        reloadRecords()
    }

    fun clear() {
        loadJob?.cancel()
        loadVersion++
        activeTrainingAttempt = null
        accountId = -1
        level = ""
        completedIds = emptySet()
        isLoading = false
        loadError = null
        refreshUiState()
    }

    private fun currentExerciseId(): String? = currentPlan()?.exercises
        ?.firstOrNull { it.id !in completedIds }?.id
}
