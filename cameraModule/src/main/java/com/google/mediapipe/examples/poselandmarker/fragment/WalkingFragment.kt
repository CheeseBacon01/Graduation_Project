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
package com.google.mediapipe.examples.poselandmarker.fragment

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Camera
import androidx.camera.core.AspectRatio
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.mediapipe.examples.poselandmarker.PoseLandmarkerHelper
import com.google.mediapipe.examples.poselandmarker.MainViewModel
import com.google.mediapipe.examples.poselandmarker.R
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentWalkingBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import com.google.mediapipe.examples.poselandmarker.ExerciseResult

class WalkingFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    enum class State {
        SITTING, STANDING_UP, WALKING, RESTING
    }

    companion object {
        private const val TAG = "WalkingFragment"
        private const val VISIBILITY_THRESHOLD = 0.5f
        private const val TARGET_STEPS_PER_SEC = 0.8f // 每秒目標步數 (用於評分)
        private const val MIN_STEP_DISTANCE = 0.02f // 判定領先腳的最小位移差
    }

    private var _binding: FragmentWalkingBinding? = null
    private val binding get() = _binding!!

    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    // Walking Variables
    private var currentState = State.SITTING
    private var totalWalkingTimeMs = 0L
    private var currentSessionWalkTimeMs = 0L
    private var lastFrameTime = 0L
    private var isTestCompleted = false
    private var isTrainingStarted = false

    // User Settings
    private var singleWalkLimitSec = 10
    private var totalTargetSec = 60
    private var currentSet = 1
    private var maxSets = 6

    // Gait analysis for accuracy (Step Count Based)
    private var totalStepCount = 0
    private var lastLeadingFoot = -1 // -1: None, 0: Left, 1: Right
    private var lastKneeY = 0f

    private lateinit var backgroundExecutor: ExecutorService

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWalkingBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        backgroundExecutor = Executors.newSingleThreadExecutor()

        setupSpinners()

        binding.btnStartTraining.setOnClickListener {
            singleWalkLimitSec = binding.spinnerWalkTime.selectedItem.toString().toInt()
            totalTargetSec = binding.spinnerTotalTime.selectedItem.toString().toInt()
            maxSets = totalTargetSec / singleWalkLimitSec

            // 立即更新 UI，顯示正確的總組數
            binding.overlay.updateTestInfo(0, 1, "請坐在椅子上準備", 0f, false, "總步數", maxSets)

            binding.setupPanel.visibility = View.GONE
            isTrainingStarted = true
            binding.viewFinder.post { setUpCamera() }
            backgroundExecutor.execute {
                poseLandmarkerHelper = PoseLandmarkerHelper(
                    context = requireContext(),
                    runningMode = RunningMode.LIVE_STREAM,
                    minPoseDetectionConfidence = viewModel.currentMinPoseDetectionConfidence,
                    minPoseTrackingConfidence = viewModel.currentMinPoseTrackingConfidence,
                    minPosePresenceConfidence = viewModel.currentMinPosePresenceConfidence,
                    currentDelegate = viewModel.currentDelegate,
                    poseLandmarkerHelperListener = this
                )
            }
        }

        binding.btnRestDone.setOnClickListener {
            binding.btnRestDone.visibility = View.GONE
            currentSessionWalkTimeMs = 0L
            currentSet++
            currentState = State.STANDING_UP // 休息完繼續，不需要坐下
            lastLeadingFoot = -1 // 重置領先腳判定
            lastFrameTime = 0L
        }

        binding.btnFinish.setOnClickListener {
            requireActivity().finish()
        }

        binding.fabSwitchCamera.setOnClickListener {
            cameraFacing = if (cameraFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            bindCameraUseCases()
        }
    }

    private fun setupSpinners() {
        val walkTimes = (5..10).toList().map { it.toString() }
        val walkAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, walkTimes)
        binding.spinnerWalkTime.adapter = walkAdapter
        binding.spinnerWalkTime.setSelection(5)

        val totalTimes = listOf(60, 70, 80, 90, 100, 110, 120).map { it.toString() }
        val totalAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, totalTimes)
        binding.spinnerTotalTime.adapter = totalAdapter
    }

    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")
        val cameraSelector = CameraSelector.Builder().requireLensFacing(cameraFacing).build()
        preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation).build()
        imageAnalyzer = ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
            .also { it.setAnalyzer(backgroundExecutor) { image -> detectPose(image) } }
        cameraProvider.unbindAll()
        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) { Log.e(TAG, "Use case binding failed", exc) }
    }

    private fun detectPose(imageProxy: ImageProxy) {
        if(this::poseLandmarkerHelper.isInitialized) {
            poseLandmarkerHelper.detectLiveStream(
                imageProxy = imageProxy,
                isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
            )
        }
    }

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        activity?.runOnUiThread {
            if (_binding != null && isTrainingStarted) {
                val results = resultBundle.results.first()
                processWalkingLogic(results)
                binding.overlay.setPoseResults(
                    results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM
                )
            }
        }
    }

    private fun processWalkingLogic(results: PoseLandmarkerResult) {
        if (isTestCompleted || results.landmarks().isEmpty() || currentState == State.RESTING) return

        val landmarks = results.landmarks()[0]

        val isVisible = intArrayOf(11, 12, 23, 24).all { landmarks[it].visibility().orElse(0f) > VISIBILITY_THRESHOLD }
        if (!isVisible) {
            binding.overlay.updateTestInfo(totalStepCount, currentSet, "請將全身放入畫面", calculateAccuracy(), isTestCompleted, "總步數", maxSets)
            lastFrameTime = 0
            return
        }

        val currentTime = System.currentTimeMillis()
        if (lastFrameTime == 0L) lastFrameTime = currentTime
        val deltaTime = currentTime - lastFrameTime
        lastFrameTime = currentTime

        val leftHip = landmarks[23]; val rightHip = landmarks[24]
        val leftKnee = landmarks[25]; val rightKnee = landmarks[26]
        val leftHeel = landmarks[29]; val rightHeel = landmarks[30]

        val hipKneeDist = (abs(leftHip.y() - leftKnee.y()) + abs(rightHip.y() - rightKnee.y())) / 2f
        val currentKneeY = (leftKnee.y() + rightKnee.y()) / 2f
        val kneeMovement = abs(currentKneeY - lastKneeY)
        lastKneeY = currentKneeY

        // 步數偵測 (透過領先腳變換)
        val yDiff = leftHeel.y() - rightHeel.y()
        val currentLeadingFoot = when {
            yDiff > MIN_STEP_DISTANCE -> 0 // 左腳在前 (靠近畫面底部)
            yDiff < -MIN_STEP_DISTANCE -> 1 // 右腳在前
            else -> lastLeadingFoot
        }

        if (currentState == State.WALKING) {
            if (lastLeadingFoot != -1 && currentLeadingFoot != lastLeadingFoot) {
                totalStepCount++
            }
        }
        lastLeadingFoot = currentLeadingFoot

        when (currentState) {
            State.SITTING -> {
                if (hipKneeDist > 0.12f) currentState = State.STANDING_UP
            }
            State.STANDING_UP -> {
                if (kneeMovement > 0.005f) currentState = State.WALKING
            }
            State.WALKING -> {
                totalWalkingTimeMs += deltaTime
                currentSessionWalkTimeMs += deltaTime

                if (currentSessionWalkTimeMs >= singleWalkLimitSec * 1000L) {
                    pauseForRest()
                }

                if (totalWalkingTimeMs >= totalTargetSec * 1000L) {
                    completeTest()
                }
            }
            State.RESTING -> { }
        }

        val statusText = when (currentState) {
            State.SITTING -> "第 $currentSet 組：請坐在椅子上準備"
            State.STANDING_UP -> if (currentSet == 1) "起身中..." else "第 $currentSet 組：請開始行走"
            State.WALKING -> "步行中 (本組已走: ${currentSessionWalkTimeMs/1000}s)"
            State.RESTING -> "請站立休息"
        }

        binding.overlay.updateTestInfo(totalStepCount, currentSet, statusText, calculateAccuracy(), isTestCompleted, "總步數", maxSets)
    }

    private fun pauseForRest() {
        currentState = State.RESTING
        activity?.runOnUiThread {
            binding.btnRestDone.visibility = View.VISIBLE
        }
    }

    private fun calculateAccuracy(): Float {
        val totalSec = totalWalkingTimeMs / 1000f
        if (totalSec <= 0) return 0f

        // 評分公式：(實際步數 / (已走秒數 * 目標步頻)) * 100
        val targetSteps = totalSec * TARGET_STEPS_PER_SEC
        val score = (totalStepCount.toFloat() / targetSteps * 100f).coerceIn(0f, 100f)
        return score
    }

    private fun completeTest() {
        isTestCompleted = true
        val finalAccuracy = calculateAccuracy()
        val totalSeconds = maxSets * singleWalkLimitSec

        // --- 封包傳送 ---
        val result = ExerciseResult(
            exerciseName = "步行訓練(A級)",
            exerciseId = "A-1",
            steps = totalStepCount,
            sets = maxSets,
            accuracy = finalAccuracy,
            durationSeconds = totalSeconds
        )
        viewModel.postResult(result)
        binding.overlay.updateTestInfo(totalStepCount, currentSet, "訓練完成！", calculateAccuracy(), true, "總步數", maxSets)
        binding.resultPanel.visibility = View.VISIBLE
        binding.btnRestDone.visibility = View.GONE
        binding.tvFinalResult.text = String.format(Locale.US, "總平均準確率: %.1f%%\n累計步數: %d 步\n累計時間: %d 秒", calculateAccuracy(), totalStepCount, totalTargetSec)
    }

    override fun onPause() {
        super.onPause()
        backgroundExecutor.execute { if(this::poseLandmarkerHelper.isInitialized) poseLandmarkerHelper.clearPoseLandmarker() }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
        backgroundExecutor.shutdown()
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread { Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show() }
    }
}
