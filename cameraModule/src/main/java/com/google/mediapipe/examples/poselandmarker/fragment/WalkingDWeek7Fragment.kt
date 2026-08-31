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
import android.widget.TextView
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

class WalkingDWeek7Fragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    enum class State {
        SITTING, STANDING_UP, WALKING, COOL_DOWN
    }

    companion object {
        private const val TAG = "WalkingDWeek7Fragment"
        private const val VISIBILITY_THRESHOLD = 0.5f
        private const val TARGET_STEPS_PER_SEC = 1.0f // 準確率判定基準 (參考 D 級)
        private const val MIN_STEP_DISTANCE = 0.02f
        private const val COOL_DOWN_TIME_MS = 120000L // 結束後慢走 120 秒
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

    private var currentState = State.SITTING
    private var walkTimeLimitMs = 0L
    private var coolDownTimeLeftMs = COOL_DOWN_TIME_MS
    private var lastFrameTime = 0L
    private var isTestCompleted = false
    private var isTrainingStarted = false

    private var totalStepCount = 0
    private var lastLeadingFoot = -1
    private var lastKneeY = 0f
    private var currentWalkTimeMs = 0L

    private lateinit var backgroundExecutor: ExecutorService

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWalkingBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        backgroundExecutor = Executors.newSingleThreadExecutor()

        setupUI()

        binding.btnStartTraining.setOnClickListener {
            val selectedMinutes = binding.spinnerWalkTime.selectedItem.toString().replace(" 分鐘", "").toInt()
            walkTimeLimitMs = selectedMinutes * 60 * 1000L
            
            binding.overlay.updateTestInfo(0, 1, "請坐在椅子上準備", 0f, false, "總步數", 1)
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

    private fun setupUI() {
        // 設定步行時間選單 30, 35, 40, 45 分鐘
        val walkTimes = arrayOf("30 分鐘", "35 分鐘", "40 分鐘", "45 分鐘")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, walkTimes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerWalkTime.adapter = adapter
        
        binding.spinnerTotalTime.visibility = View.GONE
        
        val parent = binding.setupPanel.getChildAt(0) as ViewGroup
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child is TextView) {
                val text = child.text.toString()
                if (text.contains("設定")) {
                    child.text = "步行訓練"
                } else if (text.contains("單次")) {
                    child.text = "請選擇步行時間:"
                } else if (text.contains("累計")) {
                    child.visibility = View.GONE
                }
            }
        }
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
                
                // 冷卻階段不顯示骨架
                if (currentState != State.COOL_DOWN) {
                    binding.overlay.setPoseResults(
                        results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM
                    )
                } else {
                    binding.overlay.clear()
                }
            }
        }
    }

    private fun processWalkingLogic(results: PoseLandmarkerResult) {
        val currentTime = System.currentTimeMillis()
        if (lastFrameTime == 0L) lastFrameTime = currentTime
        val deltaTime = currentTime - lastFrameTime
        lastFrameTime = currentTime

        if (isTestCompleted) return

        // 1. 冷卻階段邏輯 (不須偵測使用者是否在畫面)
        if (currentState == State.COOL_DOWN) {
            coolDownTimeLeftMs -= deltaTime
            if (coolDownTimeLeftMs <= 0) {
                completeTest()
            } else {
                binding.overlay.updateTestInfo(totalStepCount, 1, "訓練完成！慢走冷卻中 (${coolDownTimeLeftMs/1000}s)", calculateAccuracy(), false, "總步數", 1, time = (coolDownTimeLeftMs/1000).toString(), showAccuracy = false)
            }
            return
        }

        // 2. 步行偵測邏輯
        val landmarks = if (results.landmarks().isNotEmpty()) results.landmarks()[0] else null
        
        if (landmarks == null) {
            binding.overlay.updateTestInfo(totalStepCount, 1, "請將全身放入畫面", calculateAccuracy(), isTestCompleted, "總步數", 1)
            return
        }

        val isVisible = intArrayOf(11, 12, 23, 24).all { landmarks[it].visibility().orElse(0f) > VISIBILITY_THRESHOLD }
        if (!isVisible) {
            binding.overlay.updateTestInfo(totalStepCount, 1, "請將全身放入畫面", calculateAccuracy(), isTestCompleted, "總步數", 1)
            return
        }

        val leftHip = landmarks[23]; val rightHip = landmarks[24]
        val leftKnee = landmarks[25]; val rightKnee = landmarks[26]
        val leftAnkle = landmarks[27]; val rightAnkle = landmarks[28]

        val hipKneeDist = (abs(leftHip.y() - leftKnee.y()) + abs(rightHip.y() - rightKnee.y())) / 2f
        val currentKneeY = (leftKnee.y() + rightKnee.y()) / 2f
        val kneeMovement = abs(currentKneeY - lastKneeY)
        lastKneeY = currentKneeY

        val yDiff = leftAnkle.y() - rightAnkle.y()
        val currentLeadingFoot = when {
            yDiff > MIN_STEP_DISTANCE -> 0 
            yDiff < -MIN_STEP_DISTANCE -> 1 
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
                currentWalkTimeMs += deltaTime
                if (currentWalkTimeMs >= walkTimeLimitMs) {
                    startCoolDown()
                }
            }
            else -> {}
        }

        val statusText = when (currentState) {
            State.SITTING -> "請坐在椅子上準備"
            State.STANDING_UP -> "起身中..."
            State.WALKING -> {
                val remainingSec = (walkTimeLimitMs - currentWalkTimeMs) / 1000
                String.format(Locale.US, "步行中 (剩餘: %d分%02d秒)", remainingSec / 60, remainingSec % 60)
            }
            else -> ""
        }

        if (statusText.isNotEmpty()) {
            binding.overlay.updateTestInfo(totalStepCount, 1, statusText, calculateAccuracy(), isTestCompleted, "總步數", 1)
        }
    }

    private fun startCoolDown() {
        currentState = State.COOL_DOWN
        coolDownTimeLeftMs = COOL_DOWN_TIME_MS
    }

    private fun calculateAccuracy(): Float {
        val totalSec = currentWalkTimeMs / 1000f
        if (totalSec <= 0) return 0f
        val targetSteps = totalSec * TARGET_STEPS_PER_SEC
        return (totalStepCount.toFloat() / targetSteps * 100f).coerceIn(0f, 100f)
    }

    private fun completeTest() {
        isTestCompleted = true
        val finalAccuracy = calculateAccuracy()
        val totalSeconds = (walkTimeLimitMs / 1000).toInt()

        // --- 封包傳送 ---
        val result = ExerciseResult(
            exerciseName = "步行訓練(D級 WEEK7)",
            exerciseId = "D-1",
            steps = totalStepCount,
            sets = 2,
            accuracy = finalAccuracy,
            durationSeconds = totalSeconds
        )
        viewModel.postResult(result)
        binding.overlay.updateTestInfo(totalStepCount, 1, "訓練完成！", finalAccuracy, true, "總步數", 1)
        binding.resultPanel.visibility = View.VISIBLE
        
        binding.tvFinalResult.text = String.format(Locale.US, "級別: 步行 (D級 Week7)\n總平均準確率: %.1f%%\n累計步數: %d 步\n訓練時間: %d 分鐘", finalAccuracy, totalStepCount, walkTimeLimitMs / 60000)
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
