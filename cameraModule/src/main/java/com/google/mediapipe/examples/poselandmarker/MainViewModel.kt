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

import androidx.lifecycle.ViewModel

/**
 *  This ViewModel is used to store pose landmarker helper settings
 */
data class ExerciseResult(
    val exerciseName: String,     // 運動名稱 (例如: "水瓶舉重")
    val exerciseId: String = "",  // 運動 ID (例如: "B1")
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
class MainViewModel : ViewModel() {
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

    private val _lastResult = androidx.lifecycle.MutableLiveData<ExerciseResult>()
    val lastResult: androidx.lifecycle.LiveData<ExerciseResult> get() = _lastResult

    fun postResult(result: ExerciseResult) {
        _lastResult.postValue(result)
        // 這裡未來可以加入直接呼叫 API 存檔的邏輯
        android.util.Log.d("ExerciseData", "封包已發送: $result")
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