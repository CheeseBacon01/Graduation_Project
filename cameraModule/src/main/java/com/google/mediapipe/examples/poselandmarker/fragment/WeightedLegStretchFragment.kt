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
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentWeightedLegStretchBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.google.mediapipe.examples.poselandmarker.ExerciseResult

class WeightedLegStretchFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    companion object {
        private const val TAG = "WeightedLegStretch"
        private const val REPS_PER_SET = 12
        private const val TOTAL_SETS = 3
        private const val SET_REST_TIME_MS = 60000L // 組間休息 60 秒
        private const val VISIBILITY_THRESHOLD = 0.2f
        private const val EMA_ALPHA = 0.25
        private const val EXTENSION_ANGLE_THRESHOLD = 120.0
        private const val BENT_ANGLE_THRESHOLD = 110.0
    }

    private var _binding: FragmentWeightedLegStretchBinding? = null
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
    private var isLegExtended = false
    private var isRestingBetweenSets = false
    private var isTestCompleted = false
    private var isTrainingStarted = false

    private var timer: CountDownTimer? = null
    private var totalAccuracyAccumulated = 0f
    private var accuracyTicks = 0
    private var currentTimerStatusText = ""
    private var millisRemaining: Long = 0L

    // 0: Left Leg, 1: Right Leg
    private var targetLeg = 0

    private var smoothedLeftKneeAngle = -1.0
    private var smoothedRightKneeAngle = -1.0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWeightedLegStretchBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        backgroundExecutor = Executors.newSingleThreadExecutor()

        binding.btnStartTraining.setOnClickListener {
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
                processLogic(results)
                binding.overlay.setPoseResults(
                    results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM
                )
            }
        }
    }

    private fun processLogic(results: PoseLandmarkerResult) {
        // 確認 3D worldLandmarks 存在且不為空
        if (isTestCompleted || results.landmarks().isEmpty() || results.worldLandmarks().isEmpty()) return

        if (isRestingBetweenSets) {
            binding.overlay.updateTestInfo(currentRep, currentSet, currentTimerStatusText, calculateAvgAccuracy(), isTestCompleted, "次數", TOTAL_SETS)
            return
        }

        val landmarks = results.landmarks()[0]
        val worldLandmarks = results.worldLandmarks()[0]

        // --- 關鍵修正：處理前鏡頭的鏡像翻轉 ---
        val isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT

        // 定義真實的物理左右腳 Index
        // 如果是前鏡頭，MediaPipe 的右側(24,26,28)其實是使用者的實體左腳
        val leftHipIdx = if (isFrontCamera) 24 else 23
        val leftKneeIdx = if (isFrontCamera) 26 else 25
        val leftAnkleIdx = if (isFrontCamera) 28 else 27

        val rightHipIdx = if (isFrontCamera) 23 else 24
        val rightKneeIdx = if (isFrontCamera) 25 else 26
        val rightAnkleIdx = if (isFrontCamera) 27 else 28
        // -------------------------------------

        // 獨立腿部可見度檢查 (使用映射後的真實 Index)
        val requiredIndices = if (targetLeg == 0) {
            intArrayOf(leftHipIdx, leftKneeIdx, leftAnkleIdx)
        } else {
            intArrayOf(rightHipIdx, rightKneeIdx, rightAnkleIdx)
        }

        val isVisible = requiredIndices.all { landmarks[it].visibility().orElse(0f) > VISIBILITY_THRESHOLD }

        if (!isVisible) {
            val legName = if (targetLeg == 0) "左腿" else "右腿"
            binding.overlay.updateTestInfo(currentRep, currentSet, "請確保$legName 清楚入鏡", calculateAvgAccuracy(), isTestCompleted, "次數", TOTAL_SETS)
            return
        }

        // 從 worldLandmarks 提取真實的 3D 關節點
        val physicalLeftHip = worldLandmarks[leftHipIdx]
        val physicalRightHip = worldLandmarks[rightHipIdx]

        val physicalLeftKnee = worldLandmarks[leftKneeIdx]
        val physicalRightKnee = worldLandmarks[rightKneeIdx]

        val physicalLeftAnkle = worldLandmarks[leftAnkleIdx]
        val physicalRightAnkle = worldLandmarks[rightAnkleIdx]

        // 呼叫 3D 角度計算函式 (現在傳入的絕對是真實的物理左/右腳)
        val rawLeftKneeAngle = calculateAngle3D(physicalLeftHip, physicalLeftKnee, physicalLeftAnkle)
        val rawRightKneeAngle = calculateAngle3D(physicalRightHip, physicalRightKnee, physicalRightAnkle)

        // 後續邏輯完全不用修改，因為源頭的左右腳已經被我們校正了
        smoothedLeftKneeAngle = if (smoothedLeftKneeAngle < 0) rawLeftKneeAngle else (EMA_ALPHA * rawLeftKneeAngle) + ((1 - EMA_ALPHA) * smoothedLeftKneeAngle)
        smoothedRightKneeAngle = if (smoothedRightKneeAngle < 0) rawRightKneeAngle else (EMA_ALPHA * rawRightKneeAngle) + ((1 - EMA_ALPHA) * smoothedRightKneeAngle)

        val currentAngle = if (targetLeg == 0) smoothedLeftKneeAngle else smoothedRightKneeAngle
        val legName = if (targetLeg == 0) "左腿" else "右腿"

        // Repetition logic: 使用 3D 角度判定
        if (!isLegExtended && currentAngle > EXTENSION_ANGLE_THRESHOLD) {
            isLegExtended = true
            val frameAccuracy = (currentAngle / 180.0 * 100.0).coerceIn(0.0, 100.0)
            totalAccuracyAccumulated += frameAccuracy.toFloat()
            accuracyTicks++
        } else if (isLegExtended && currentAngle < BENT_ANGLE_THRESHOLD) {
            isLegExtended = false
            currentRep++

            if (currentRep >= REPS_PER_SET) {
                if (targetLeg == 0) {
                    targetLeg = 1
                    currentRep = 0
                    Toast.makeText(requireContext(), "換右腿", Toast.LENGTH_SHORT).show()
                } else {
                    if (currentSet < TOTAL_SETS) {
                        startSetRestTimer()
                    } else {
                        completeTest()
                    }
                }
            }
        }

        val status = if (isLegExtended) {
            "請慢慢放下$legName\n(目前角度: ${currentAngle.toInt()}°)"
        } else {
            "請水平抬起$legName\n(目前角度: ${currentAngle.toInt()}°)"
        }
        binding.overlay.updateTestInfo(currentRep, currentSet, status, calculateAvgAccuracy(), isTestCompleted, "次數", TOTAL_SETS)
    }

    // 3D 向量內積角度計算
    private fun calculateAngle3D(
        first: com.google.mediapipe.tasks.components.containers.Landmark,
        mid: com.google.mediapipe.tasks.components.containers.Landmark,
        last: com.google.mediapipe.tasks.components.containers.Landmark
    ): Double {
        val v1x = first.x() - mid.x()
        val v1y = first.y() - mid.y()
        val v1z = first.z() - mid.z()

        val v2x = last.x() - mid.x()
        val v2y = last.y() - mid.y()
        val v2z = last.z() - mid.z()

        val dotProduct = (v1x * v2x) + (v1y * v2y) + (v1z * v2z)

        val magnitude1 = Math.sqrt((v1x * v1x + v1y * v1y + v1z * v1z).toDouble())
        val magnitude2 = Math.sqrt((v2x * v2x + v2y * v2y + v2z * v2z).toDouble())

        if (magnitude1 == 0.0 || magnitude2 == 0.0) return 0.0

        val cosTheta = (dotProduct / (magnitude1 * magnitude2)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(Math.acos(cosTheta))
    }

    private fun calculateAvgAccuracy() = if (accuracyTicks == 0) 0f else (totalAccuracyAccumulated / accuracyTicks).coerceIn(0f, 100f)

    private fun startSetRestTimer() {
        isRestingBetweenSets = true
        timer?.cancel()
        timer = object : CountDownTimer(SET_REST_TIME_MS, 1000) {
            override fun onTick(ms: Long) {
                millisRemaining = ms
                currentTimerStatusText = "組間休息 (${ms/1000}s)"
            }
            override fun onFinish() {
                isRestingBetweenSets = false
                currentSet++
                currentRep = 0
                targetLeg = 0
            }
        }.start()
    }

    private fun completeTest() {
        isTestCompleted = true
        val finalAccuracy = calculateAvgAccuracy()
        // --- 封包傳送 ---
        val result = ExerciseResult(
            exerciseName = "腳踝負重腿部訓練",
            exerciseId = "A-4",
            accuracy = finalAccuracy,
        )
        viewModel.postResult(result)

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