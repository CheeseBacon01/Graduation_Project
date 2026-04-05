package com.example.graduationproject

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

class UserViewModel : ViewModel() {
    var isSurveyComplete by mutableStateOf(false)
        private set

    var surveyProgress by mutableStateOf(0) // 追蹤回答到第幾題
        private set

    fun updateSurveyProgress(index: Int) {
        surveyProgress = index
    }

    fun completeSurvey() {
        isSurveyComplete = true
    }
}
