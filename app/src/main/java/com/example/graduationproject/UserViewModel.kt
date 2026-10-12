package com.example.graduationproject

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

sealed interface AssessmentState {
    data object Loading : AssessmentState
    data object NotAssessed : AssessmentState

    data class Assessed(
        val accountId: Int,
        val grade: String,
        val sppbScore: Int
    ) : AssessmentState

    data class Inconsistent(
        val accountId: Int,
        val grade: String?,
        val sppbScore: Int?
    ) : AssessmentState

    data class Error(
        val accountId: Int?,
        val message: String
    ) : AssessmentState
}

private val supportedAssessmentGrades = setOf("A", "B", "B+", "C", "C+", "D")

internal fun resolveAssessmentState(
    accountId: Int,
    grade: String?,
    sppbScore: Int?
): AssessmentState {
    val normalizedGrade = grade?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        normalizedGrade == null && sppbScore == null -> AssessmentState.NotAssessed
        normalizedGrade != null && normalizedGrade in supportedAssessmentGrades &&
            sppbScore != null && sppbScore in 0..12 ->
            AssessmentState.Assessed(accountId, normalizedGrade, sppbScore)
        else -> AssessmentState.Inconsistent(accountId, normalizedGrade, sppbScore)
    }
}

class UserViewModel : ViewModel() {
    var currentAccountId by mutableStateOf<Int?>(null)
        private set

    var assessmentState by mutableStateOf<AssessmentState>(AssessmentState.NotAssessed)
        private set

    val isSurveyComplete: Boolean
        get() = assessmentState is AssessmentState.Assessed

    val currentGrade: String?
        get() = (assessmentState as? AssessmentState.Assessed)?.grade

    val currentSppbScore: Int?
        get() = (assessmentState as? AssessmentState.Assessed)?.sppbScore

    var surveyProgress by mutableStateOf(0) // 追蹤回答到第幾題
        private set

    fun updateSurveyProgress(index: Int) {
        surveyProgress = index
    }

    fun bindAccount(accountId: Int): Boolean {
        if (accountId <= 0) {
            val hadState = currentAccountId != null || assessmentState != AssessmentState.NotAssessed
            reset()
            return hadState
        }
        if (currentAccountId == accountId) return false

        currentAccountId = accountId
        assessmentState = AssessmentState.Loading
        surveyProgress = 0
        return true
    }

    fun updateAssessmentFromDashboard(
        accountId: Int,
        grade: String?,
        sppbScore: Int?
    ): Boolean {
        if (currentAccountId != accountId) return false
        assessmentState = resolveAssessmentState(accountId, grade, sppbScore)
        return true
    }

    fun updateAssessmentAfterSave(
        accountId: Int,
        grade: String,
        sppbScore: Int
    ): Boolean = updateAssessmentFromDashboard(accountId, grade, sppbScore)

    fun reportAssessmentLoadError(accountId: Int, message: String) {
        if (currentAccountId == accountId) {
            assessmentState = AssessmentState.Error(accountId, message)
        }
    }

    fun reset() {
        currentAccountId = null
        assessmentState = AssessmentState.NotAssessed
        surveyProgress = 0
    }
}
