/*
 * Copyright 2023 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.mediapipe.examples.poselandmarker

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 *  This ViewModel is used to store pose landmarker helper settings
 */
data class ExerciseResult(
    val exerciseName: String,     // 運動名稱 (例如: "水瓶舉重")
    val exerciseId: String = "",  // 運動 ID
    val reps: Int = 0,            // 總次數
    val sets: Int = 0,            // 總組數
    val steps: Int = 0,           // 總步數 (步行類運動用)
    val accuracy: Float = 0f,     // 平均準確率
    val durationSeconds: Int = 0, // 總運動時長 (秒)
    val timestamp: Long = System.currentTimeMillis()
)
enum class ExerciseMode {
    LEG_LIFT,      // 抬腿 (使用 Pose)
    SQUEEZE_BALL,  // 捏球 (使用 Hand + Object)
    IDLE
}

enum class SaveStatus {
    IDLE,
    SAVING,
    SUCCESS,
    FAILED
}

class MainViewModel : ViewModel() {

    companion object {
        private const val TAG = "ExerciseData"

        private const val BASE_URL = "http://192.168.0.10/xampp/Graduation_Project/"
    }

    private var _model = PoseLandmarkerHelper.MODEL_POSE_LANDMARKER_FULL
    private var _delegate: Int = PoseLandmarkerHelper.DELEGATE_CPU
    private var _minPoseDetectionConfidence: Float =
        PoseLandmarkerHelper.DEFAULT_POSE_DETECTION_CONFIDENCE
    private var _minPoseTrackingConfidence: Float = PoseLandmarkerHelper
        .DEFAULT_POSE_TRACKING_CONFIDENCE
    private var _minPosePresenceConfidence: Float = PoseLandmarkerHelper
        .DEFAULT_POSE_PRESENCE_CONFIDENCE

    val currentDelegate: Int get() = _delegate
    val currentModel: Int get() = _model
    val currentMinPoseDetectionConfidence: Float
        get() =
            _minPoseDetectionConfidence
    val currentMinPoseTrackingConfidence: Float
        get() =
            _minPoseTrackingConfidence
    val currentMinPosePresenceConfidence: Float
        get() =
            _minPosePresenceConfidence

    private var accountId: Int = -1
    private var userLevel: String = ""
    private var expectedExerciseCode: String? = null

    @Volatile private var saving = false
    val isSaving: Boolean get() = saving

    private var submittedResult: ExerciseResult? = null
    private var uploadRequestId: String? = null
    var savedExerciseCode: String? = null
        private set

    fun setAccountInfo(accountId: Int, userLevel: String, expectedCode: String?) {
        this.accountId = accountId
        this.userLevel = userLevel.trim().uppercase().take(1)
        this.expectedExerciseCode = expectedCode
    }

    private val _lastResult = MutableLiveData<ExerciseResult>()
    val lastResult: LiveData<ExerciseResult> get() = _lastResult

    private val _saveStatus = MutableLiveData<SaveStatus>(SaveStatus.IDLE)
    val saveStatus: LiveData<SaveStatus> get() = _saveStatus

    @Synchronized
    fun postResult(result: ExerciseResult) {
        if (submittedResult != null) return
        submittedResult = result
        uploadRequestId = UUID.randomUUID().toString()
        _lastResult.postValue(result)
        retrySave()
    }

    fun retrySave() {
        val result = submittedResult ?: return
        if (saving || _saveStatus.value == SaveStatus.SUCCESS) return
        val exerciseCode = resolveExerciseCode(result.exerciseId, userLevel, expectedExerciseCode)
        if (accountId <= 0 || exerciseCode == null) {
            android.util.Log.e(TAG, "無法儲存：帳號/等級/運動代碼不吻合 (accountId=$accountId, level=$userLevel, result=${result.exerciseId}, expected=$expectedExerciseCode)")
            _saveStatus.postValue(SaveStatus.FAILED)
            return
        }
        val requestId = uploadRequestId ?: return
        saving = true
        savedExerciseCode = null
        _saveStatus.postValue(SaveStatus.SAVING)

        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                uploadExerciseResult(result, exerciseCode, requestId)
            }
            saving = false
            if (success) savedExerciseCode = exerciseCode
            _saveStatus.value = if (success) SaveStatus.SUCCESS else SaveStatus.FAILED
        }
    }

    private fun uploadExerciseResult(result: ExerciseResult, exerciseCode: String, requestId: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(BASE_URL + "api/save_exercise_record.php").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connectTimeout = 8000
                readTimeout = 8000
            }
            val json = JSONObject().apply {
                put("account_id", accountId)
                put("exercise_code", exerciseCode)
                put("accuracy", result.accuracy)
                put("duration_seconds", result.durationSeconds)
                put("request_id", requestId)
            }
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(json.toString())
                writer.flush()
            }
            val httpStatus = connection.responseCode
            val responseText = (if (httpStatus in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val responseJson = JSONObject(responseText)
            val saved = httpStatus in 200..299 && responseJson.optBoolean("success", false)
                    && responseJson.optString("request_id") == requestId
            if (!saved) android.util.Log.w(TAG, "API 未確認儲存成功: HTTP $httpStatus, $responseText")
            saved
        } catch (e: Exception) {
            android.util.Log.e(TAG, "上傳訓練紀錄失敗", e)
            false
        } finally {
            connection?.disconnect()
        }
    }

    /** B+、C+ 都必須使用 B、C 的運動代碼；同時檢查畫面實際要求的動作。 */
    private fun resolveExerciseCode(resultCodes: String, level: String, expected: String?): String? {
        if (level.isBlank()) return null
        val validCodes = resultCodes.split(",").map { it.trim() }
        return if (expected != null) {
            expected.takeIf { it.startsWith("$level-") && it in validCodes }
        } else {
            validCodes.firstOrNull { it.startsWith("$level-") }
        }
    }

    fun setDelegate(delegate: Int) {
        _delegate = delegate
    }

    fun setMinPoseDetectionConfidence(confidence: Float) {
        _minPoseDetectionConfidence = confidence
    }

    fun setMinPoseTrackingConfidence(confidence: Float) {
        _minPoseTrackingConfidence = confidence
    }

    fun setMinPosePresenceConfidence(confidence: Float) {
        _minPosePresenceConfidence = confidence
    }

    fun setModel(model: Int) {
        _model = model
    }

    private var _currentExerciseMode = ExerciseMode.IDLE
    val currentExerciseMode: ExerciseMode get() = _currentExerciseMode

    fun setExerciseMode(mode: ExerciseMode) {
        _currentExerciseMode = mode
    }
}