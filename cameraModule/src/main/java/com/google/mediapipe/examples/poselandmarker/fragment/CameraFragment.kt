package com.google.mediapipe.examples.poselandmarker.fragment

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
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
import androidx.navigation.Navigation
import androidx.navigation.fragment.findNavController

import com.google.mediapipe.examples.poselandmarker.PoseLandmarkerHelper
import com.google.mediapipe.examples.handlandmarker.HandLandmarkerHelper
import com.google.mediapipe.examples.objectdetection.ObjectDetectorHelper
import com.google.mediapipe.examples.poselandmarker.MainViewModel
import com.google.mediapipe.examples.poselandmarker.ExerciseMode
import com.google.mediapipe.examples.poselandmarker.R
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentCameraBinding

import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.graphics.Color
import android.util.TypedValue
import com.google.mediapipe.examples.poselandmarker.ExerciseResult

class CameraFragment : Fragment() {
    enum class WalkingState {
        IDLE,
        WAITING_FOR_DISTANCE,
        COUNTDOWN,
        WALKING,
        FINISHED
    }

    private var walkingState = WalkingState.IDLE
    companion object {
        private const val TAG = "MultiModel Camera"
        private const val TOTAL_STEPS_PER_SET = 15
        private const val TOTAL_SETS = 3
        private const val REST_TIME_MS = 30000L
        private const val CONTACT_THRESHOLD = 0.12f
        private const val VISIBILITY_THRESHOLD = 0.3f
        private const val MIN_STEP_DISTANCE = 0.0008f
    }

    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private lateinit var handLandmarkerHelper: HandLandmarkerHelper
    private lateinit var objectDetectorHelper: ObjectDetectorHelper

    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    // Gait Test Variables
    private var currentStep = 0
    private var currentSet = 1
    private var isResting = false
    private var lastLeadingFoot = -1
    private var totalStepsAccumulated = 0
    private var validContactCount = 0
    private var totalShoulderBalance = 0f
    private var balanceTicks = 0
    private var restTimer: CountDownTimer? = null
    private var isTestCompleted = false

    private lateinit var backgroundExecutor: ExecutorService

    override fun onResume() {
        super.onResume()
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                .navigate(R.id.action_camera_to_permissions)
        }

        backgroundExecutor.execute {
            if(this::poseLandmarkerHelper.isInitialized && poseLandmarkerHelper.isClose()) {
                poseLandmarkerHelper.setupPoseLandmarker()
            }
            if(this::handLandmarkerHelper.isInitialized && handLandmarkerHelper.isClose()) {
                handLandmarkerHelper.setupHandLandmarker()
            }
            if(this::objectDetectorHelper.isInitialized && objectDetectorHelper.isClosed()) {
                objectDetectorHelper.setupObjectDetector()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        restTimer?.cancel()

        if(this::poseLandmarkerHelper.isInitialized) {
            viewModel.setMinPoseDetectionConfidence(poseLandmarkerHelper.minPoseDetectionConfidence)
            viewModel.setMinPoseTrackingConfidence(poseLandmarkerHelper.minPoseTrackingConfidence)
            viewModel.setMinPosePresenceConfidence(poseLandmarkerHelper.minPosePresenceConfidence)
            viewModel.setDelegate(poseLandmarkerHelper.currentDelegate)
            backgroundExecutor.execute { poseLandmarkerHelper.clearPoseLandmarker() }
        }
        if(this::handLandmarkerHelper.isInitialized) {
            backgroundExecutor.execute { handLandmarkerHelper.clearHandLandmarker() }
        }
        if(this::objectDetectorHelper.isInitialized) {
            backgroundExecutor.execute { objectDetectorHelper.clearObjectDetector() }
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
        backgroundExecutor.shutdown()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (viewModel.currentExerciseMode == ExerciseMode.IDLE) {
            viewModel.setExerciseMode(ExerciseMode.LEG_LIFT)
        }
        backgroundExecutor = Executors.newSingleThreadExecutor()

        // 1. 修改：按下「確認並開始訓練」後才啟動相機並隱藏說明視窗
        fragmentCameraBinding.btnStartTraining.setOnClickListener {
            fragmentCameraBinding.setupPanel.visibility = View.GONE
            showHeightDialog()
        }
        // 3. 修改：在這裡使用匿名物件 (object :) 來分別建立獨立的 Listener
        backgroundExecutor.execute {
            poseLandmarkerHelper = PoseLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minPoseDetectionConfidence = viewModel.currentMinPoseDetectionConfidence,
                minPoseTrackingConfidence = viewModel.currentMinPoseTrackingConfidence,
                minPosePresenceConfidence = viewModel.currentMinPosePresenceConfidence,
                currentDelegate = viewModel.currentDelegate,
                poseLandmarkerHelperListener = poseListener // 綁定下方建立的 Listener
            )
            handLandmarkerHelper = HandLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                currentDelegate = viewModel.currentDelegate,
                handLandmarkerHelperListener = handListener
            )
            objectDetectorHelper = ObjectDetectorHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                currentDelegate = viewModel.currentDelegate,
                objectDetectorListener = objectListener
            )
        }

        fragmentCameraBinding.btnFinish.setOnClickListener {
            requireActivity().finish()
        }

        fragmentCameraBinding.fabSwitchCamera.setOnClickListener {
            if (cameraProvider == null) return@setOnClickListener
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
        input.hint = "例如: 170"

        AlertDialog.Builder(requireContext())
            .setTitle("準備開始")
            .setMessage("請輸入受測者的身高 (公分)：")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("確定") { _, _ ->
                val heightStr = input.text.toString()
                // 將身高傳給 OverlayView 以計算距離
                fragmentCameraBinding.overlay.userHeightCm = heightStr.toFloatOrNull() ?: 160f

                // 啟動相機並進入等待距離狀態
                fragmentCameraBinding.viewFinder.post { setUpCamera() }
                walkingState = WalkingState.WAITING_FOR_DISTANCE
                fragmentCameraBinding.tvCenterStatus.text = "請退後至\n大於 6 公尺處"
                fragmentCameraBinding.tvCenterStatus.textSize = 50f
            }
            .show()
    }
    private fun startCountdownSequence() {
        walkingState = WalkingState.COUNTDOWN
        lifecycleScope.launch(Dispatchers.Main) {
            fragmentCameraBinding.tvCenterStatus.textSize = 100f
            fragmentCameraBinding.tvCenterStatus.text = "3"
            delay(1000)
            fragmentCameraBinding.tvCenterStatus.text = "2"
            delay(1000)
            fragmentCameraBinding.tvCenterStatus.text = "1"
            delay(1000)
            fragmentCameraBinding.tvCenterStatus.text = "GO!"

            delay(800)
            fragmentCameraBinding.tvCenterStatus.text = ""
            walkingState = WalkingState.WALKING // 切換狀態，開始計步
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
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation).build()

        imageAnalyzer = ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
            .also { it.setAnalyzer(backgroundExecutor) { image -> processImageProxy(image) } }

        cameraProvider.unbindAll()
        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) { Log.e(TAG, "Use case binding failed", exc) }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mode = viewModel.currentExerciseMode

        if (mode == ExerciseMode.IDLE) {
            imageProxy.close()
            return
        }

        val frameTime = SystemClock.uptimeMillis()
        val isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT

        val bitmapBuffer = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
        imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
        imageProxy.close()

        val matrix = Matrix().apply {
            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            if (isFrontCamera) {
                postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
            }
        }
        val rotatedBitmap = Bitmap.createBitmap(bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true)
        val mpImage = BitmapImageBuilder(rotatedBitmap).build()

        when (mode) {
            ExerciseMode.LEG_LIFT -> {
                if (this::poseLandmarkerHelper.isInitialized && !poseLandmarkerHelper.isClose()) {
                    poseLandmarkerHelper.detectAsync(mpImage, frameTime)
                }
            }
            ExerciseMode.SQUEEZE_BALL -> {
                if (this::handLandmarkerHelper.isInitialized && !handLandmarkerHelper.isClose()) {
                    handLandmarkerHelper.detectAsync(mpImage, frameTime)
                }
                if (this::objectDetectorHelper.isInitialized && !objectDetectorHelper.isClosed()) {
                    objectDetectorHelper.detectAsync(mpImage, frameTime)
                }
            }
            ExerciseMode.IDLE -> {}
        }
    }

    // --- 5. 修改：獨立宣告三個 Listener，處理各自的 Callback ---

    private val poseListener = object : PoseLandmarkerHelper.LandmarkerListener {
        override fun onError(error: String, errorCode: Int) {
            showErrorMsg(error)
        }

        override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
            activity?.runOnUiThread {
                if (_fragmentCameraBinding != null && viewModel.currentExerciseMode == ExerciseMode.LEG_LIFT) {
                    val results = resultBundle.results.firstOrNull() ?: return@runOnUiThread

                    // 取得 OverlayView 算出的目前距離
                    val currentDistance = fragmentCameraBinding.overlay.currentDistance

                    when (walkingState) {
                        WalkingState.WAITING_FOR_DISTANCE -> {
                            // 如果使用者退後超過 6 公尺，觸發倒數
                            if (currentDistance >= 6.0f) {
                                startCountdownSequence()
                            }
                        }
                        WalkingState.WALKING -> {
                            // 正式開始執行計步邏輯
                            processGaitLogic(results)
                        }
                        else -> {}
                    }

                    fragmentCameraBinding.overlay.setPoseResults(
                        results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM
                    )
                }
            }
        }
    }

    private val handListener = object : HandLandmarkerHelper.LandmarkerListener {
        override fun onError(error: String, errorCode: Int) {
            showErrorMsg(error)
        }

        override fun onResults(resultBundle: HandLandmarkerHelper.ResultBundle) {
            activity?.runOnUiThread {
                if (_fragmentCameraBinding != null && viewModel.currentExerciseMode == ExerciseMode.SQUEEZE_BALL) {
                    val results = resultBundle.results.firstOrNull() ?: return@runOnUiThread
                    fragmentCameraBinding.overlay.setHandResults(
                        results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM
                    )
                }
            }
        }
    }

    private val objectListener = object : ObjectDetectorHelper.DetectorListener {
        override fun onError(error: String, errorCode: Int) {
            showErrorMsg(error)
        }

        override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle) {
            activity?.runOnUiThread {
                if (_fragmentCameraBinding != null && viewModel.currentExerciseMode == ExerciseMode.SQUEEZE_BALL) {
                    val results = resultBundle.results.firstOrNull() ?: return@runOnUiThread
                    fragmentCameraBinding.overlay.setObjectResults(
                        results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM
                    )
                }
            }
        }
    }

    // 共用的錯誤處理函式
    private fun showErrorMsg(error: String) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
    }


    // --- 以下保留你原本的 Gait Test 邏輯 ---
    private fun processGaitLogic(results: PoseLandmarkerResult) {
        if (walkingState != WalkingState.WALKING || isResting || isTestCompleted || results.landmarks().isEmpty()) return

        val landmarks = results.landmarks()[0]

        // 檢查腳踝與腳尖可見度
        val leftFootVisible = landmarks[29].visibility().orElse(0f) > VISIBILITY_THRESHOLD &&
                landmarks[31].visibility().orElse(0f) > VISIBILITY_THRESHOLD
        val rightFootVisible = landmarks[30].visibility().orElse(0f) > VISIBILITY_THRESHOLD &&
                landmarks[32].visibility().orElse(0f) > VISIBILITY_THRESHOLD

        if (!leftFootVisible || !rightFootVisible) {
            fragmentCameraBinding.tvCenterStatus.text = "請確保雙腳入鏡"
            fragmentCameraBinding.tvCenterStatus.setTextColor(Color.RED)
            fragmentCameraBinding.tvCenterStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            return
        }

        // 平衡計算
        val shoulderBalance = 1.0f - abs(landmarks[11].y() - landmarks[12].y())
        totalShoulderBalance += (shoulderBalance * 100f).coerceIn(0f, 100f)
        balanceTicks++

        val leftHeel = landmarks[29]; val rightHeel = landmarks[30]
        val leftToe = landmarks[31]; val rightToe = landmarks[32]

        val yDiff = leftHeel.y() - rightHeel.y()
        val currentLeadingFoot = when {
            yDiff > MIN_STEP_DISTANCE -> 0 // 左腳在前
            yDiff < -MIN_STEP_DISTANCE -> 1 // 右腳在前
            else -> lastLeadingFoot
        }

        // 偵測步數變化
        if (currentLeadingFoot != -1 && currentLeadingFoot != lastLeadingFoot) {
            currentStep++
            totalStepsAccumulated++

            val contactDist = if (currentLeadingFoot == 0) abs(rightToe.y() - leftHeel.y()) else abs(leftToe.y() - rightHeel.y())
            if (contactDist < CONTACT_THRESHOLD) validContactCount++

            // 更新大字顯示步數 (修正 2)
            fragmentCameraBinding.tvCenterStatus.text = currentStep.toString()
            fragmentCameraBinding.tvCenterStatus.setTextColor(Color.WHITE)
            fragmentCameraBinding.tvCenterStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 120f)

            if (currentStep >= TOTAL_STEPS_PER_SET) {
                if (currentSet < TOTAL_SETS) startRestPeriod() else completeTest()
            }
        }

        lastLeadingFoot = currentLeadingFoot
        val accuracy = calculateAccuracy()
        val status = if (isResting) "休息中" else "測試中..."
        fragmentCameraBinding.overlay.updateTestInfo(currentStep, currentSet, status, accuracy, isTestCompleted)
    }

    private fun calculateAccuracy(): Float {
        if (totalStepsAccumulated == 0) return 0f
        val contactScore = (validContactCount.toFloat() / totalStepsAccumulated).coerceIn(0f, 1f) * 100f
        val balanceScore = if (balanceTicks == 0) 0f else totalShoulderBalance / balanceTicks
        return (contactScore * 0.5f + balanceScore * 0.5f)
    }

    private fun startRestPeriod() {
        isResting = true
        walkingState = WalkingState.IDLE
        restTimer = object : CountDownTimer(REST_TIME_MS, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = millisUntilFinished / 1000
                fragmentCameraBinding.overlay.updateTestInfo(
                    currentStep, currentSet, "休息 (${sec}s)", calculateAccuracy()
                )
                // 更新大字顯示休息 (修正 2)
                fragmentCameraBinding.tvCenterStatus.text = "休息\n$sec"
                fragmentCameraBinding.tvCenterStatus.setTextColor(Color.parseColor("#FBBC04"))
                fragmentCameraBinding.tvCenterStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 100f)
            }
            override fun onFinish() {
                isResting = false; currentSet++; currentStep = 0; lastLeadingFoot = -1
                walkingState = WalkingState.WAITING_FOR_DISTANCE

                // 提示使用者再次退後
                fragmentCameraBinding.tvCenterStatus.text = "請退後至\n大於 6 公尺處"
                fragmentCameraBinding.tvCenterStatus.setTextColor(Color.WHITE)
                fragmentCameraBinding.tvCenterStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 50f)
            }
        }.start()
    }

    private fun completeTest() {
        isTestCompleted = true
        val finalAccuracy = calculateAccuracy()
        // --- 封包傳送 ---
        val result = ExerciseResult(
            exerciseName = "直線走路",
            exerciseId = "A-6",
            accuracy = finalAccuracy,
        )
        viewModel.postResult(result)
        fragmentCameraBinding.overlay.updateTestInfo(currentStep, currentSet, "測試完成！", finalAccuracy, true)

        fragmentCameraBinding.resultPanel.visibility = View.VISIBLE
        fragmentCameraBinding.tvFinalResult.text = String.format(Locale.US, "總平均準確率: %.1f%%", finalAccuracy)
    }
}