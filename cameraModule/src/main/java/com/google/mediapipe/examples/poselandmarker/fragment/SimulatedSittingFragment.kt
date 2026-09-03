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
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentSimulatedSittingBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import com.google.mediapipe.examples.poselandmarker.ExerciseResult
class SimulatedSittingFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    companion object {
        private const val TAG = "SimulatedSitting"
        private const val TOTAL_REPS_PER_SET = 12
        private const val TOTAL_SETS = 3
        private const val SET_REST_TIME_MS = 60000L // 1分鐘休息
        private const val VISIBILITY_THRESHOLD = 0.5f
        private const val SQUAT_THRESHOLD = 0.10f // 判定下蹲的髖膝高度差
        private const val STAND_THRESHOLD = 0.18f // 判定回正的髖膝高度差
    }

    private var _binding: FragmentSimulatedSittingBinding? = null
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
    private var isSquatting = false
    private var isRestingBetweenSets = false
    private var isTestCompleted = false
    private var isTrainingStarted = false
    
    private var timer: CountDownTimer? = null
    private var currentTimerStatusText = ""
    private var totalAccuracyAccumulated = 0f
    private var accuracyTicks = 0

    private lateinit var backgroundExecutor: ExecutorService

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSimulatedSittingBinding.inflate(inflater, container, false)
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
        
        // 1. 可見度檢查 (肩膀、髖部、膝蓋)
        val requiredIndices = intArrayOf(11, 12, 23, 24, 25, 26)
        val isVisible = requiredIndices.all { landmarks[it].visibility().orElse(0f) > VISIBILITY_THRESHOLD }
        
        if (!isVisible) {
            binding.overlay.updateTestInfo(currentRep, currentSet, "請將全身放入畫面", calculateAvgAccuracy(), isTestCompleted, "次數")
            return
        }

        // 判定動作邏輯：髖部 (Hip) 與 膝蓋 (Knee) 的 Y 座標差
        val leftHip = landmarks[23]; val leftKnee = landmarks[25]
        val rightHip = landmarks[24]; val rightKnee = landmarks[26]
        
        val hipKneeDist = (abs(leftHip.y() - leftKnee.y()) + abs(rightHip.y() - rightKnee.y())) / 2f
        
        if (!isSquatting && hipKneeDist < SQUAT_THRESHOLD) {
            // 偵測到開始蹲下
            isSquatting = true
        } else if (isSquatting && hipKneeDist > STAND_THRESHOLD) {
            // 偵測到回到站立
            isSquatting = false
            currentRep++
            if (currentRep >= TOTAL_REPS_PER_SET) {
                if (currentSet < TOTAL_SETS) startSetRestTimer() else completeTest()
            }
        }

        // 準確率計算：穩定度 (肩膀平衡度)
        val shoulderBalance = 1.0f - abs(landmarks[11].y() - landmarks[12].y())
        totalAccuracyAccumulated += (shoulderBalance * 100f).coerceIn(0f, 100f)
        accuracyTicks++

        val status = if (isRestingBetweenSets) {
            currentTimerStatusText
        } else {
            if (isSquatting) "請慢慢站起來" else "請模擬坐下動作"
        }
        
        binding.overlay.updateTestInfo(currentRep, currentSet, status, calculateAvgAccuracy(), isTestCompleted, "次數")
    }

    private fun calculateAvgAccuracy() = if (accuracyTicks == 0) 0f else (totalAccuracyAccumulated / accuracyTicks).coerceIn(0f, 100f)

    private fun startSetRestTimer() {
        isRestingBetweenSets = true
        timer?.cancel()
        timer = object : CountDownTimer(SET_REST_TIME_MS, 1000) {
            override fun onTick(ms: Long) {
                currentTimerStatusText = "組間休息 (${ms/1000}s)"
                binding.overlay.updateTestInfo(currentRep, currentSet, currentTimerStatusText, calculateAvgAccuracy(), isTestCompleted, "次數")
            }
            override fun onFinish() {
                isRestingBetweenSets = false
                currentSet++
                currentRep = 0
            }
        }.start()
    }

    private fun completeTest() {
        isTestCompleted = true
        val finalAccuracy = calculateAvgAccuracy()
        // --- 封包傳送 ---
        val result = ExerciseResult(
            exerciseName = "模擬坐下",
            exerciseId = "B-4",
            accuracy = finalAccuracy,
        )
        viewModel.postResult(result)
        binding.overlay.updateTestInfo(currentRep, currentSet, "測試完成！", finalAccuracy, true, "次數")
        binding.resultPanel.visibility = View.VISIBLE
        binding.tvFinalResult.text = String.format(Locale.US, "總平均準確率: %.1f%%", finalAccuracy)
    }

    override fun onResume() {
        super.onResume()
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
