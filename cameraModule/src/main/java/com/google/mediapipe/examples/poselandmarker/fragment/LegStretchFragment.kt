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
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentLegStretchBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import com.google.mediapipe.examples.poselandmarker.ExerciseResult
class LegStretchFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    companion object {
        private const val TAG = "LegStretch"
        private const val HOLD_TIME_MS = 10000L // 維持 12 秒
        private const val RELAX_TIME_MS = 5000L // 放鬆 5 秒
        private const val REPS_PER_LEG_PER_SET = 6
        private const val TOTAL_REPS_PER_SET = REPS_PER_LEG_PER_SET * 2 // 兩腿輪流，共12次
        private const val TOTAL_SETS = 3
        private const val SET_REST_TIME_MS = 60000L // 組間休息 60 秒
        private const val VISIBILITY_THRESHOLD = 0.5f
        private const val EMA_ALPHA = 0.25 // 角度平滑係數（越小越平滑，避免準確率與姿勢抖動）
    }

    private var _binding: FragmentLegStretchBinding? = null
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
    private var isHolding = false
    private var isRelaxing = false
    private var isRestingBetweenSets = false
    private var isTestCompleted = false
    private var isTrainingStarted = false

    private var timer: CountDownTimer? = null
    private var totalAccuracyAccumulated = 0f
    private var accuracyTicks = 0
    private var currentTimerStatusText = ""
    private var millisRemaining: Long = 0L
    private var isPaused: Boolean = false

    // 用來追蹤當前應該伸展哪一隻腳 (0: 左腳, 1: 右腳)
    private var targetLeg = 0

    // 平滑化角度暫存
    private var smoothedLeftKneeAngle = -1.0
    private var smoothedRightKneeAngle = -1.0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLegStretchBinding.inflate(inflater, container, false)
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
        if (isTestCompleted || results.landmarks().isEmpty()) return

        // 1. 休息期間不要求全身入鏡，直接更新 UI 倒數狀態並提早返回
        if (isRelaxing || isRestingBetweenSets) {
            // 已修正：將 TOTAL_REPS_PER_SET 替換為 TOTAL_SETS
            binding.overlay.updateTestInfo(currentRep, currentSet, currentTimerStatusText, calculateAvgAccuracy(), isTestCompleted, "次數", TOTAL_SETS)
            return
        }

        val landmarks = results.landmarks()[0]
        val requiredIndices = intArrayOf(23, 24, 25, 26, 27, 28)
        val isVisible = requiredIndices.all { landmarks[it].visibility().orElse(0f) > VISIBILITY_THRESHOLD }

        // 2. 只有在維持姿勢或準備階段，才需要嚴格要求入鏡
        if (!isVisible) {
            if (!isPaused && isHolding) {
                timer?.cancel()
                isPaused = true
            }
            // 已修正：將 TOTAL_REPS_PER_SET 替換為 TOTAL_SETS
            binding.overlay.updateTestInfo(currentRep, currentSet, "請將全身放入畫面中", calculateAvgAccuracy(), isTestCompleted, "次數", TOTAL_SETS)
            return
        } else if (isPaused) {
            isPaused = false
            if (isHolding) startHoldingTimer(millisRemaining) // 恢復先前的倒數
        }

        // 取得關鍵點
        val leftHip = landmarks[23]; val rightHip = landmarks[24]
        val leftKnee = landmarks[25]; val rightKnee = landmarks[26]
        val leftAnkle = landmarks[27]; val rightAnkle = landmarks[28]
        val leftWrist = landmarks[15]; val rightWrist = landmarks[16]

        // 3. 計算角度並加入 EMA 平滑濾波 (解決數值與判定抖動)
        val rawLeftKneeAngle = calculateAngle(leftHip, leftKnee, leftAnkle)
        val rawRightKneeAngle = calculateAngle(rightHip, rightKnee, rightAnkle)

        smoothedLeftKneeAngle = if (smoothedLeftKneeAngle < 0) rawLeftKneeAngle else (EMA_ALPHA * rawLeftKneeAngle) + ((1 - EMA_ALPHA) * smoothedLeftKneeAngle)
        smoothedRightKneeAngle = if (smoothedRightKneeAngle < 0) rawRightKneeAngle else (EMA_ALPHA * rawRightKneeAngle) + ((1 - EMA_ALPHA) * smoothedRightKneeAngle)

        // 4. 寬容度機制 (Hysteresis)：如果已經在維持姿勢中，稍微放寬角度要求，避免輕微晃動就判定失敗重來
        val extendThreshold = if (isHolding) 145.0 else 160.0
        val bentThreshold = if (isHolding) 135.0 else 120.0
        val handNearThreshold = if (isHolding) 0.22 else 0.15

        val isLeftExtended = smoothedLeftKneeAngle > extendThreshold
        val isRightExtended = smoothedRightKneeAngle > extendThreshold
        val isLeftBent = smoothedLeftKneeAngle < bentThreshold
        val isRightBent = smoothedRightKneeAngle < bentThreshold

        val handsOnLeftKnee = isPointNear(leftWrist, leftKnee, handNearThreshold) && isPointNear(rightWrist, leftKnee, handNearThreshold)
        val handsOnRightKnee = isPointNear(leftWrist, rightKnee, handNearThreshold) && isPointNear(rightWrist, rightKnee, handNearThreshold)

        // 當前目標判斷
        val isPostureCorrect = if (targetLeg == 0) { // 目標：伸左腿
            isLeftExtended && isRightBent && handsOnRightKnee
        } else { // 目標：伸右腿
            isRightExtended && isLeftBent && handsOnLeftKnee
        }

        if (!isHolding && isPostureCorrect) {
            startHoldingTimer()
        }

        // 5. 準確率計算：同樣使用平滑後的角度計算，讓準確率跳動趨緩
        if (isHolding) {
            if (isPostureCorrect) {
                val kneeScore = if (targetLeg == 0) {
                    (smoothedLeftKneeAngle - 120f) / 60f
                } else {
                    (smoothedRightKneeAngle - 120f) / 60f
                }
                val frameAccuracy = (kneeScore * 100.0).coerceIn(0.0, 100.0)
                totalAccuracyAccumulated += frameAccuracy.toFloat()
            }
            accuracyTicks++
        }

        // 6. UI 狀態更新 (顯示剩餘秒數與動作引導)
        val legText = if (targetLeg == 0) "左腿" else "右腿"
        val status = when {
            isHolding -> if (isPostureCorrect) "維持姿勢中 ($legText) 剩餘 ${millisRemaining/1000} 秒" else "請保持姿勢不要動！(剩餘 ${millisRemaining/1000} 秒)"
            else -> "伸直$legText 雙手扶另一膝 身體前傾"
        }

        // 已修正：將 TOTAL_REPS_PER_SET 替換為 TOTAL_SETS
        binding.overlay.updateTestInfo(currentRep, currentSet, status, calculateAvgAccuracy(), isTestCompleted, "次數", TOTAL_SETS)
    }

    private fun calculateAngle(first: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
                               mid: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
                               last: com.google.mediapipe.tasks.components.containers.NormalizedLandmark): Double {
        var result = Math.toDegrees(
            (atan2(last.y() - mid.y(), last.x() - mid.x()) -
                    atan2(first.y() - mid.y(), first.x() - mid.x())).toDouble()
        )
        result = abs(result)
        if (result > 180) {
            result = 360.0 - result
        }
        return result
    }

    // 加入動態門檻值，方便實現判定寬容度
    private fun isPointNear(point: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
                            target: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
                            threshold: Double): Boolean {
        val dist = Math.sqrt(Math.pow((point.x() - target.x()).toDouble(), 2.0) + Math.pow((point.y() - target.y()).toDouble(), 2.0))
        return dist < threshold
    }

    private fun calculateAvgAccuracy() = if (accuracyTicks == 0) 0f else (totalAccuracyAccumulated / accuracyTicks).coerceIn(0f, 100f)

    private fun startHoldingTimer(durationMs: Long = HOLD_TIME_MS) {
        isHolding = true
        timer?.cancel()
        timer = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(ms: Long) {
                millisRemaining = ms
                currentTimerStatusText = "伸展維持中 (${ms/1000}s)"
            }
            override fun onFinish() {
                isHolding = false
                startRelaxingTimer()
            }
        }.start()
    }

    private fun startRelaxingTimer(durationMs: Long = RELAX_TIME_MS) {
        isRelaxing = true
        currentRep++
        // 切換目標腿
        targetLeg = 1 - targetLeg

        timer?.cancel()
        timer = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(ms: Long) {
                millisRemaining = ms
                currentTimerStatusText = "請放鬆休息 (${ms/1000}s)"
            }
            override fun onFinish() {
                isRelaxing = false
                if (currentRep >= TOTAL_REPS_PER_SET) {
                    if (currentSet < TOTAL_SETS) startSetRestTimer() else completeTest()
                }
            }
        }.start()
    }

    private fun startSetRestTimer(durationMs: Long = SET_REST_TIME_MS) {
        isRestingBetweenSets = true
        timer?.cancel()
        timer = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(ms: Long) {
                millisRemaining = ms
                currentTimerStatusText = "組間休息 (${ms/1000}s)"
            }
            override fun onFinish() {
                isRestingBetweenSets = false
                currentSet++
                currentRep = 0
                targetLeg = 0 // 每組開始從左腿開始
            }
        }.start()
    }

    private fun completeTest() {
        isTestCompleted = true
        val finalAccuracy = calculateAvgAccuracy()
        // --- 封包傳送 ---
        val result = ExerciseResult(
            exerciseName = "腿部伸展",
            exerciseId = "C-7,D-9",
            accuracy = finalAccuracy,
        )
        viewModel.postResult(result)
        // 已修正：將 TOTAL_REPS_PER_SET 替換為 TOTAL_SETS
        binding.overlay.updateTestInfo(currentRep, currentSet, "測試完成！", finalAccuracy, true, "次數", TOTAL_SETS)

        binding.resultPanel.visibility = View.VISIBLE
        binding.tvFinalResult.text = String.format(Locale.US, "總平均準確率: %.1f%%", finalAccuracy)
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

    private lateinit var backgroundExecutor: ExecutorService
}