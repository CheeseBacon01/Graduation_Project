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
import android.os.CountDownTimer
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentStairClimbingBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import com.google.mediapipe.examples.poselandmarker.ExerciseResult
class StairClimbingFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    enum class State {
        PREPARING, CLIMBING, RESTING, COMPLETED
    }

    companion object {
        private const val TAG = "StairClimbingFragment"
        private const val VISIBILITY_THRESHOLD = 0.5f
        private const val STEPS_PER_SET = 20
        private const val MAX_SETS = 3
        private const val REST_DURATION_MS = 60000L
    }

    private var _binding: FragmentStairClimbingBinding? = null
    private val binding get() = _binding!!

    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    private var currentState = State.PREPARING
    private var currentSet = 1
    private var currentStepCount = 0
    private var totalStepCount = 0
    private var lastLeadingFoot = -1 // -1: None, 0: Left, 1: Right
    private var restTimer: CountDownTimer? = null
    
    // Accuracy tracking
    private var setAccuracies = mutableListOf<Float>()
    private var lastStepTime = 0L
    private var stepIntervals = mutableListOf<Long>()

    private var isTrainingStarted = false

    private lateinit var backgroundExecutor: ExecutorService

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStairClimbingBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        backgroundExecutor = Executors.newSingleThreadExecutor()

        binding.btnStartTraining.setOnClickListener {
            isTrainingStarted = true
            binding.setupPanel.visibility = View.GONE
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
            // 如果相機已經初始化，則重新綁定以切換鏡頭
            if (cameraProvider != null) {
                bindCameraUseCases()
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
        if (!isTrainingStarted) return
        activity?.runOnUiThread {
            if (_binding != null) {
                val results = resultBundle.results.first()
                processStairLogic(results)
                binding.overlay.setPoseResults(
                    results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM
                )
            }
        }
    }

    private fun processStairLogic(results: PoseLandmarkerResult) {
        if (currentState == State.COMPLETED || results.landmarks().isEmpty() || currentState == State.RESTING) return

        val landmarks = results.landmarks()[0]
        val isVisible = intArrayOf(23, 24, 25, 26, 27, 28).all { landmarks[it].visibility().orElse(0f) > VISIBILITY_THRESHOLD }
        
        if (!isVisible) {
            binding.tvLargeStatus.text = "請將下半身\n放入畫面"
            binding.tvLargeStatus.visibility = View.VISIBLE
            binding.overlay.updateTestInfo(currentStepCount, currentSet, "請確保下半身可見", 0f, false, "目前步數", MAX_SETS)
            return
        }

        val leftAnkle = landmarks[27]; val rightAnkle = landmarks[28]
        val leftHip = landmarks[23]; val rightHip = landmarks[24]

        // 計算參考長度（腿長），以應對使用者距離相機遠近產生的縮放問題
        val legLength = (abs(leftAnkle.y() - leftHip.y()) + abs(rightAnkle.y() - rightHip.y())) / 2f
        // 動態門檻值：將門檻設為腿長的 12%（可視情況調整比例，例如 0.1f ~ 0.15f）
        val dynamicThreshold = (legLength * 0.12f).coerceAtLeast(0.01f)

        // 偵測步數：利用兩腳高度差，改用 dynamicThreshold 替代原本固定的 MIN_STEP_Y_DIFF
        val ankleYDiff = leftAnkle.y() - rightAnkle.y()
        val currentLeadingFoot = when {
            ankleYDiff > dynamicThreshold -> 0 // 左腳較低 (踩在下一階或準備抬起)
            ankleYDiff < -dynamicThreshold -> 1 // 右腳較低
            else -> lastLeadingFoot
        }

        if (currentState == State.PREPARING) {
            currentState = State.CLIMBING
            lastStepTime = System.currentTimeMillis()
        }

        if (currentState == State.CLIMBING) {
            if (lastLeadingFoot != -1 && currentLeadingFoot != lastLeadingFoot) {
                val now = System.currentTimeMillis()
                if (now - lastStepTime > 300) { // 防止抖動誤判
                    currentStepCount++
                    totalStepCount++
                    stepIntervals.add(now - lastStepTime)
                    lastStepTime = now
                }
            }
            lastLeadingFoot = currentLeadingFoot

            if (currentStepCount >= STEPS_PER_SET) {
                finishSet()
            }
        }

        val statusText = "已走 $currentStepCount / $STEPS_PER_SET 步"
        binding.tvLargeStatus.text = "$currentStepCount / $STEPS_PER_SET"
        binding.tvLargeStatus.visibility = View.VISIBLE
        binding.overlay.updateTestInfo(currentStepCount, currentSet, statusText, calculateCurrentAccuracy(), false, "目前步數", MAX_SETS)
    }

    private fun finishSet() {
        setAccuracies.add(calculateCurrentAccuracy())
        stepIntervals.clear()
        
        if (currentSet >= MAX_SETS) {
            completeTraining()
        } else {
            startRest()
        }
    }

    private fun startRest() {
        currentState = State.RESTING
        binding.tvLargeStatus.visibility = View.GONE
        binding.tvRestTimer.visibility = View.VISIBLE
        restTimer = object : CountDownTimer(REST_DURATION_MS, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsRemaining = millisUntilFinished / 1000
                binding.tvRestTimer.text = String.format(Locale.getDefault(), "休息中: %ds", secondsRemaining)
            }
            override fun onFinish() {
                binding.tvRestTimer.visibility = View.GONE
                currentSet++
                currentStepCount = 0
                currentState = State.PREPARING
                lastLeadingFoot = -1
            }
        }.start()
    }

    private fun calculateCurrentAccuracy(): Float {
        if (stepIntervals.isEmpty()) return 100f
        
        // 準確率計算：基於節奏的穩定性 (變異係數)
        val avgInterval = stepIntervals.average()
        val variance = stepIntervals.map { Math.pow(it - avgInterval, 2.0) }.average()
        val stdDev = Math.sqrt(variance)
        
        // 變異係數 CV = stdDev / avg
        // CV 越小表示節奏越穩，準確率越高
        val cv = if (avgInterval > 0) (stdDev / avgInterval).toFloat() else 0f
        val accuracy = (1.0f - cv) * 100f
        return accuracy.coerceIn(0f, 100f)
    }

    private fun completeTraining() {
        currentState = State.COMPLETED
        binding.tvLargeStatus.visibility = View.GONE
        val finalAccuracy = if (setAccuracies.isNotEmpty()) setAccuracies.average().toFloat() else 0f
        // --- 封包傳送 ---
        val result = ExerciseResult(
            exerciseName = "上下樓梯",
            exerciseId = "D-5",
            accuracy = finalAccuracy,
        )
        viewModel.postResult(result)
        binding.resultPanel.visibility = View.VISIBLE
        binding.tvFinalResult.text = String.format(Locale.US, "總平均準確率: %.1f%%\n總步數: %d 步", finalAccuracy, totalStepCount)
        binding.overlay.updateTestInfo(currentStepCount, currentSet, "訓練完成！", finalAccuracy, true, "目前步數", MAX_SETS)
    }

    override fun onResume() {
        super.onResume()
        if (isTrainingStarted) {
            backgroundExecutor.execute {
                if (this::poseLandmarkerHelper.isInitialized && poseLandmarkerHelper.isClose()) {
                    poseLandmarkerHelper.setupPoseLandmarker()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        restTimer?.cancel()
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
