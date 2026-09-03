package com.google.mediapipe.examples.poselandmarker.fragment

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentBalloonWalkingBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

import com.google.mediapipe.examples.objectdetection.ObjectDetectorHelper
import android.graphics.Bitmap
import android.graphics.Matrix
import android.text.InputType
import androidx.appcompat.app.AlertDialog
import com.google.mediapipe.framework.image.BitmapImageBuilder
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.google.mediapipe.examples.poselandmarker.ExerciseResult
class BalloonWalkingFragment : Fragment() {

    // 定義狀態
    enum class State { INPUT_HEIGHT, WAITING_FOR_DISTANCE, COUNTDOWN, WALKING, RESTING_SET, COMPLETED }

    private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private var currentState = State.INPUT_HEIGHT
    private var currentScore = 100f // 從 100 分開始
    private val BALLOON_FLOOR_Y = 0.85f // 氣球落地 Y 座標閾值 (0.0~1.0)

    private val poseListener = object : PoseLandmarkerHelper.LandmarkerListener {
        override fun onError(error: String, errorCode: Int) {
            this@BalloonWalkingFragment.showError(error)
        }

        override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
            activity?.runOnUiThread {
                val results = resultBundle.results.firstOrNull() ?: return@runOnUiThread
                val dist = binding.overlay.currentDistance

                when (currentState) {
                    State.WAITING_FOR_DISTANCE -> {
                        if (dist >= 5.0f) {
                            startCountdown()
                        } else {
                            binding.tvCenterStatus.text = "請繼續退後...\n(目前: %.1fm)".format(dist)
                        }
                    }
                    State.WALKING -> processLogic(results)
                    else -> {}
                }
                binding.overlay.setPoseResults(results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM)
            }
        }
    }

    // 2. 定義 Object 偵測的 Listener
    private val objectListener = object : ObjectDetectorHelper.DetectorListener {
        override fun onError(error: String, errorCode: Int) {
            this@BalloonWalkingFragment.showError(error)
        }

        override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle) {
            activity?.runOnUiThread {
                val results = resultBundle.results.firstOrNull() ?: return@runOnUiThread
                binding.overlay.setObjectResults(results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM)

                if (currentState == State.WALKING) {
                    val balloon = results.detections().find { it.categories().firstOrNull()?.categoryName() == "sports ball" }
                    if (balloon != null) {
                        val bottomY = balloon.boundingBox().bottom / resultBundle.inputImageHeight
                        if (bottomY > BALLOON_FLOOR_Y) {
                            currentScore = (currentScore - 0.5f).coerceAtLeast(0f)
                        }
                    }
                }
            }
        }
    }

    // 統一處理錯誤
    private fun showError(error: String) {
        activity?.runOnUiThread { Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show() }
    }
    companion object {
        private const val TAG = "BalloonWalkingFragment"
        private const val STEPS_PER_SET = 10
        private const val TOTAL_SETS = 3
        private const val REST_TIME_MS = 30000L
        private const val VISIBILITY_THRESHOLD = 0.5f
        private const val MIN_STEP_DISTANCE = 0.0008f
        private const val HAND_HIT_THRESHOLD = 0.1f // 判定手部拍打動作的閾值
    }

    private var _binding: FragmentBalloonWalkingBinding? = null
    private val binding get() = _binding!!

    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    // 訓練變數
    private var currentSet = 1
    private var currentSteps = 0
    private var lastLeadingFoot = -1 // -1: None, 0: Left, 1: Right

    // 手部動作追蹤
    private var lastActiveHand = -1 // 0: Left, 1: Right
    private var handHitCount = 0

    private var totalBalanceScore = 0f
    private var balanceTicks = 0
    private var isTestCompleted = false
    private var isTrainingStarted = false
    private var timer: CountDownTimer? = null

    private lateinit var backgroundExecutor: ExecutorService

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBalloonWalkingBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        backgroundExecutor = Executors.newSingleThreadExecutor()

        binding.btnStartTraining.setOnClickListener {
            binding.setupPanel.visibility = View.GONE
            showHeightDialog()
        }

        binding.btnFinish.setOnClickListener {
            findNavController().navigate(R.id.home_fragment)
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

    private fun showHeightDialog() {
        val input = EditText(requireContext())
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        AlertDialog.Builder(requireContext())
            .setTitle("準備開始")
            .setMessage("請輸入受測者的身高 (公分)：")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("確定") { _, _ ->
                binding.overlay.userHeightCm = input.text.toString().toFloatOrNull() ?: 165f
                startPreparation()
            }.show()
    }

    private fun startPreparation() {
        isTrainingStarted = true
        currentState = State.WAITING_FOR_DISTANCE
        binding.tvCenterStatus.text = "請退後至\n大於 5 公尺處"
        binding.tvCenterStatus.textSize = 50f

        binding.viewFinder.post { setUpCamera() }
        backgroundExecutor.execute {
            poseLandmarkerHelper = PoseLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minPoseDetectionConfidence = viewModel.currentMinPoseDetectionConfidence,
                minPoseTrackingConfidence = viewModel.currentMinPoseTrackingConfidence,
                minPosePresenceConfidence = viewModel.currentMinPosePresenceConfidence,
                currentDelegate = viewModel.currentDelegate,
                poseLandmarkerHelperListener = poseListener
            )
            objectDetectorHelper = ObjectDetectorHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                threshold = 0.008f,
                currentModel = ObjectDetectorHelper.MODEL_EFFICIENTDETV2, // 切換為 Lite2
                objectDetectorListener = objectListener
            )
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

        imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
            .also { it.setAnalyzer(backgroundExecutor) { image ->
                val frameTime = SystemClock.uptimeMillis()
                val bitmapBuffer = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                image.use { bitmapBuffer.copyPixelsFromBuffer(image.planes[0].buffer) }

                val matrix = Matrix().apply {
                    postRotate(image.imageInfo.rotationDegrees.toFloat())
                    if (cameraFacing == CameraSelector.LENS_FACING_FRONT) postScale(-1f, 1f, image.width.toFloat(), image.height.toFloat())
                }
                val rotatedBitmap = Bitmap.createBitmap(bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true)

                if (this::poseLandmarkerHelper.isInitialized) {
                    poseLandmarkerHelper.detectAsync(BitmapImageBuilder(rotatedBitmap).build(), frameTime)
                }
                if (this::objectDetectorHelper.isInitialized) {
                    objectDetectorHelper.detectAsync(BitmapImageBuilder(rotatedBitmap).build(), frameTime)
                }
                image.close()
            }}

        preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation).build()

        cameraProvider.unbindAll()
        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) { Log.e(TAG, "Use case binding failed", exc) }
    }

    private fun startCountdown() {
        currentState = State.COUNTDOWN
        lifecycleScope.launch(Dispatchers.Main) {
            binding.tvCenterStatus.textSize = 100f
            for (i in 3 downTo 1) {
                binding.tvCenterStatus.text = i.toString()
                delay(1000)
            }
            binding.tvCenterStatus.text = "GO!"
            delay(1000)
            binding.tvCenterStatus.text = ""
            currentState = State.WALKING
        }
    }

    private fun processLogic(results: PoseLandmarkerResult) {
        if (isTestCompleted || results.landmarks().isEmpty() || currentState != State.WALKING) return

        val landmarks = results.landmarks()[0]
        val requiredIndices = intArrayOf(11, 12, 15, 16, 23, 24, 27, 28)
        val isVisible = requiredIndices.all { landmarks[it].visibility().orElse(0f) > VISIBILITY_THRESHOLD }

        if (!isVisible) {
            binding.overlay.updateTestInfo(currentSteps, currentSet, "請將全身放入畫面", currentScore, isTestCompleted, "步數", TOTAL_SETS)
            // 新增：大字顯示提示
            binding.tvCenterStatus.textSize = 60f
            binding.tvCenterStatus.text = "請將全身放入畫面"
            return
        }

        // 步數偵測
        val leftAnkle = landmarks[27]; val rightAnkle = landmarks[28]
        val yDiff = leftAnkle.y() - rightAnkle.y()
        val currentLeadingFoot = when {
            yDiff > MIN_STEP_DISTANCE -> 0
            yDiff < -MIN_STEP_DISTANCE -> 1
            else -> lastLeadingFoot
        }

        if (currentLeadingFoot != lastLeadingFoot) {
            currentSteps++
            if (currentSteps >= STEPS_PER_SET) {
                moveToNextSet()
            }
        }
        lastLeadingFoot = currentLeadingFoot

        // 更新 UI 狀態
        val statusText = "正在拍氣球前進... ($currentSteps/$STEPS_PER_SET 步)"
        binding.overlay.updateTestInfo(currentSteps, currentSet, statusText, currentScore, isTestCompleted, "步數", TOTAL_SETS)

        // 新增：大字顯示目前的步數
        binding.tvCenterStatus.textSize = 150f
        binding.tvCenterStatus.text = "$currentSteps"
    }

    private fun moveToNextSet() {
        lastLeadingFoot = -1
        lastActiveHand = -1
        if (currentSet < TOTAL_SETS) {
            startTimer(REST_TIME_MS, State.RESTING_SET, "組間休息中")
        } else {
            completeTest()
        }
    }

    private fun startTimer(durationMs: Long, targetState: State, message: String) {
        currentState = targetState
        timer?.cancel()
        // 設定大字尺寸
        binding.tvCenterStatus.textSize = 80f
        timer = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(ms: Long) {
                val secondsLeft = ms / 1000
                val statusText = "$message (${secondsLeft}s)"
                binding.overlay.updateTestInfo(currentSteps, currentSet, statusText, currentScore, isTestCompleted, "步數", TOTAL_SETS)

                // 新增：大字顯示休息中與倒數
                binding.tvCenterStatus.text = "休息中\n${secondsLeft}s"
            }
            override fun onFinish() {
                currentSteps = 0
                currentSet++
                // 修改：休息完後重新進入「等待距離」狀態，要求使用者退後
                currentState = State.WAITING_FOR_DISTANCE
                binding.tvCenterStatus.textSize = 50f
                binding.tvCenterStatus.text = "請退後至\n大於 5 公尺處"
            }
        }.start()
    }

    private fun completeTest() {
        isTestCompleted = true
        // --- 封包傳送 ---
        val result = ExerciseResult(
            exerciseName = "邊拍氣球邊走路",
            exerciseId = "D-6",
            accuracy = currentScore,
        )
        viewModel.postResult(result)
        binding.overlay.updateTestInfo(currentSteps, currentSet, "測試完成！", currentScore, true, "步數", TOTAL_SETS)
        binding.resultPanel.visibility = View.VISIBLE
        binding.tvFinalResult.text = String.format(Locale.US, "最終評分: %.1f", currentScore)
    }

    override fun onResume() {
        super.onResume()
        if (isTrainingStarted) {
            backgroundExecutor.execute {
                if (this::poseLandmarkerHelper.isInitialized && poseLandmarkerHelper.isClose()) poseLandmarkerHelper.setupPoseLandmarker()
                if (this::objectDetectorHelper.isInitialized && objectDetectorHelper.isClosed()) objectDetectorHelper.setupObjectDetector()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        timer?.cancel()
        backgroundExecutor.execute {
            if(this::poseLandmarkerHelper.isInitialized) poseLandmarkerHelper.clearPoseLandmarker()
            if(this::objectDetectorHelper.isInitialized) objectDetectorHelper.clearObjectDetector()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
        backgroundExecutor.shutdown()
    }


}