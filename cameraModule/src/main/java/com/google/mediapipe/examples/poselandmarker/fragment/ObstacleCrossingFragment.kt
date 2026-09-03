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
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentObstacleCrossingBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import com.google.mediapipe.examples.poselandmarker.ExerciseResult

class ObstacleCrossingFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    companion object {
        private const val TAG = "ObstacleCrossing"
        private const val TOTAL_REPS_PER_SET = 5
        private const val TOTAL_SETS = 8
        private const val SET_REST_TIME_MS = 60000L // 60秒休息
        private const val VISIBILITY_THRESHOLD = 0.5f
        private const val LIFT_THRESHOLD = 0.08f // 膝蓋抬起的高度閾值
    }

    private var _binding: FragmentObstacleCrossingBinding? = null
    private val binding get() = _binding!!

    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    // Test Variables
    private var currentRep = 0
    private var currentSet = 1
    private var isLifting = false
    private var activeLeg = -1 // 0: Left, 1: Right
    private var isLeftLegCrossed = false
    private var isRightLegCrossed = false
    
    private var isRestingBetweenSets = false
    private var isTestCompleted = false
    private var isTrainingStarted = false
    
    private var timer: CountDownTimer? = null
    private var currentTimerStatusText = ""
    private var totalAccuracyAccumulated = 0f
    private var accuracyTicks = 0

    private lateinit var backgroundExecutor: ExecutorService

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentObstacleCrossingBinding.inflate(inflater, container, false)
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
            bindCameraUseCases()
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
                processLogic(results)
                binding.overlay.setPoseResults(
                    results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM
                )
            }
        }
    }

    private fun processLogic(results: PoseLandmarkerResult) {
        if (isTestCompleted || results.landmarks().isEmpty() || isRestingBetweenSets) return

        val landmarks = results.landmarks()[0]
        
        // 1. 可見度檢查
        val requiredIndices = intArrayOf(11, 12, 23, 24, 25, 26, 27, 28)
        val isVisible = requiredIndices.all { landmarks[it].visibility().orElse(0f) > VISIBILITY_THRESHOLD }
        
        if (!isVisible) {
            binding.overlay.updateTestInfo(currentRep, currentSet, "請將全身放入畫面", calculateAvgAccuracy(), isTestCompleted, "次數", TOTAL_SETS)
            return
        }

        // 2. 跨越動作判定邏輯 (移除 Z 軸判定)
        val leftLift = landmarks[23].y() - landmarks[25].y()
        val rightLift = landmarks[24].y() - landmarks[26].y()

        if (!isLifting) {
            if (leftLift > LIFT_THRESHOLD && !isLeftLegCrossed) {
                isLifting = true
                activeLeg = 0
            } else if (rightLift > LIFT_THRESHOLD && !isRightLegCrossed) {
                isLifting = true
                activeLeg = 1
            }
        } else {
            val currentLift = if (activeLeg == 0) leftLift else rightLift
            if (currentLift < LIFT_THRESHOLD / 2) {
                if (activeLeg == 0) isLeftLegCrossed = true else isRightLegCrossed = true
                
                if (isLeftLegCrossed && isRightLegCrossed) {
                    currentRep++
                    isLeftLegCrossed = false
                    isRightLegCrossed = false
                    if (currentRep >= TOTAL_REPS_PER_SET) {
                        if (currentSet < TOTAL_SETS) startSetRestTimer() else completeTest()
                    }
                }
                isLifting = false
            }
        }

        // 3. 準確率計算 (肩膀平衡)
        val shoulderBalance = 1.0f - abs(landmarks[11].y() - landmarks[12].y())
        totalAccuracyAccumulated += (shoulderBalance * 100f).coerceIn(0f, 100f)
        accuracyTicks++

        val status = if (isRestingBetweenSets) {
            currentTimerStatusText
        } else {
            when {
                isLifting -> "正在跨越..."
                isLeftLegCrossed && !isRightLegCrossed -> "左腳已完成，請跨越右腳"
                !isLeftLegCrossed && isRightLegCrossed -> "右腳已完成，請跨越左腳"
                else -> "請跨越障礙物 (雙腳)"
            }
        }
        
        binding.overlay.updateTestInfo(currentRep, currentSet, status, calculateAvgAccuracy(), isTestCompleted, "次數", TOTAL_SETS)
    }

    private fun calculateAvgAccuracy() = if (accuracyTicks == 0) 0f else (totalAccuracyAccumulated / accuracyTicks).coerceIn(0f, 100f)

    private fun startSetRestTimer() {
        isRestingBetweenSets = true
        timer?.cancel()
        timer = object : CountDownTimer(SET_REST_TIME_MS, 1000) {
            override fun onTick(ms: Long) {
                currentTimerStatusText = "組間休息 (${ms/1000}s)"
                binding.overlay.updateTestInfo(currentRep, currentSet, currentTimerStatusText, calculateAvgAccuracy(), isTestCompleted, "次數", TOTAL_SETS)
            }
            override fun onFinish() {
                isRestingBetweenSets = false
                currentSet++
                currentRep = 0
                isLeftLegCrossed = false
                isRightLegCrossed = false
            }
        }.start()
    }

    private fun completeTest() {
        isTestCompleted = true
        val finalAccuracy = calculateAvgAccuracy()
        // --- 封包傳送 ---
        val result = ExerciseResult(
            exerciseName = "跨越障礙物",
            exerciseId = "C-5",
            accuracy = finalAccuracy,
        )
        viewModel.postResult(result)
        binding.overlay.updateTestInfo(currentRep, currentSet, "測試完成！", finalAccuracy, true, "次數", TOTAL_SETS)
        binding.resultPanel.visibility = View.VISIBLE
        binding.tvFinalResult.text = String.format(Locale.US, "總平均準確率: %.1f%%", finalAccuracy)
    }

    override fun onResume() {
        super.onResume()
        // Start the PoseLandmarkerHelper again when users come back
        // to the foreground.
        if (isTrainingStarted) {
            backgroundExecutor.execute {
                if (poseLandmarkerHelper.isClose()) {
                    poseLandmarkerHelper.setupPoseLandmarker()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        timer?.cancel()
        if (this::poseLandmarkerHelper.isInitialized) {
            backgroundExecutor.execute { poseLandmarkerHelper.clearPoseLandmarker() }
        }
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
