package com.google.mediapipe.examples.poselandmarker.fragment

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.mediapipe.examples.poselandmarker.MainViewModel
import com.google.mediapipe.examples.poselandmarker.PoseLandmarkerHelper
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentBalanceTestBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

abstract class BaseBalanceTestFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    protected lateinit var tts: TextToSpeech
    private var lastSpeakTime = 0L

    private var _binding: FragmentBalanceTestBinding? = null
    protected val binding get() = _binding!!

    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    private lateinit var backgroundExecutor: ExecutorService

    // 共用測試狀態
    protected var isTesting = false
    protected var timer: CountDownTimer? = null
    private var initialAnklePos: Pair<Float, Float>? = null
    private val MOVEMENT_THRESHOLD = 0.05f
    protected var currentSeconds = 0f
    protected var isTestFinished = false
    private var lastSpokenText = ""

    // 抽象屬性與方法，由各獨立動作 Fragment 實作
    abstract val stageName: String
    abstract val instructionMessage: String
    abstract val passScore: String
    abstract fun isCorrectStance(dx: Float, dy: Float): Boolean
    abstract fun calculateFailScore(elapsedSeconds: Long): String

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBalanceTestBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tts = TextToSpeech(requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.TAIWAN
            }
        }
        backgroundExecutor = Executors.newSingleThreadExecutor()
        binding.viewFinder.post { setUpCamera() }

        backgroundExecutor.execute {
            poseLandmarkerHelper = PoseLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                poseLandmarkerHelperListener = this
            )
        }

        binding.btnDialogOk.setOnClickListener {
            binding.dialogLayout.visibility = View.GONE
            // 因為是獨立動作，結束後直接返回上一頁 (或列表頁)
            findNavController().navigateUp()
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

    protected fun speakOut(text: String, throttleMs: Long = 0L) {
        val currentTime = SystemClock.uptimeMillis()
        if (text != lastSpokenText || currentTime - lastSpeakTime > throttleMs) {
            if (::tts.isInitialized) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            }
            lastSpeakTime = currentTime
            lastSpokenText = text
        }
    }

    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return
        val cameraSelector = CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(backgroundExecutor) { image -> processImageProxy(image) } }

        cameraProvider.unbindAll()
        camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
        preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        if (!::poseLandmarkerHelper.isInitialized) {
            imageProxy.close()
            return
        }
        val isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
        poseLandmarkerHelper.detectLiveStream(imageProxy, isFrontCamera)
    }

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread
            val results = resultBundle.results.firstOrNull() ?: return@runOnUiThread
            checkBalance(results)
            binding.overlay.setPoseResults(results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM)

            binding.overlay.updateTestInfo(
                count = 0,
                sets = 0,
                message = binding.tvStatus.text.toString(),
                accuracy = 0f,
                label = "",
                maxSets = -1,
                setLabel = "動作: $stageName", // 改為顯示當前獨立動作名稱
                time = String.format(Locale.US, "%.2f", currentSeconds),
                showAccuracy = false
            )
        }
    }

    private fun checkBalance(results: PoseLandmarkerResult) {
        if (isTestFinished) return

        val landmarks = results.landmarks().firstOrNull() ?: return
        val requiredIndices = intArrayOf(11, 12, 27, 28)
        val isVisible = requiredIndices.all { landmarks[it].visibility().orElse(0f) > 0.5f }

        if (!isVisible) {
            binding.tvStatus.text = "請全身放入畫面(偵測肩膀雙腳)"
            binding.tvCenterStatus.text = "請全身放入畫面"
            speakOut("請全身放入畫面", 2000L)

            if (isTesting) {
                timer?.cancel()
                isTesting = false
                binding.tvTimer.text = "偵測中斷"
                speakOut("偵測中斷")
            }
            return
        }

        val leftAnkle = landmarks[27]
        val rightAnkle = landmarks[28]
        val dx = abs(leftAnkle.x() - rightAnkle.x())
        val dy = abs(leftAnkle.y() - rightAnkle.y())

        if (!isTesting) {
            if (isCorrectStance(dx, dy)) {
                binding.tvStatus.text = "姿勢正確，開始測試"
                binding.tvCenterStatus.text = "姿勢正確\n開始測試"
                if (!binding.dialogLayout.isShown) startCountdown()
            } else {
                binding.tvStatus.text = instructionMessage
                binding.tvCenterStatus.text = instructionMessage
                speakOut(instructionMessage, 3000L)
            }
            return
        }

        if (initialAnklePos == null) {
            initialAnklePos = Pair((leftAnkle.x() + rightAnkle.x()) / 2, (leftAnkle.y() + rightAnkle.y()) / 2)
        }

        val currentAnklePos = (leftAnkle.x() + rightAnkle.x()) / 2
        if (abs(currentAnklePos - initialAnklePos!!.first) > MOVEMENT_THRESHOLD) {
            failTest()
        }
    }

    private fun startCountdown() {
        if (isTesting) return
        speakOut("動作正確開始測試")
        binding.tvCenterStatus.text = "測試中..."
        isTesting = true
        initialAnklePos = null
        currentSeconds = 0f
        val timeLimit = 10000L
        timer?.cancel()
        timer = object : CountDownTimer(timeLimit, 100) {
            override fun onTick(ms: Long) {
                binding.tvTimer.text = "倒數: ${(ms/1000) + 1}秒"
                currentSeconds = (10000L - ms) / 1000f
            }
            override fun onFinish() {
                currentSeconds = 10f
                if (isTesting) passTest()
            }
        }.start()
    }

    private fun failTest() {
        isTesting = false
        isTestFinished = true
        timer?.cancel()
        val timerText = binding.tvTimer.text.toString()
        val elapsed = 10 - (timerText.filter { it.isDigit() }.toLongOrNull() ?: 10L)
        showResult("獲得 ${calculateFailScore(elapsed)}")
    }

    private fun passTest() {
        isTesting = false
        isTestFinished = true
        showResult("獲得 $passScore")
    }

    private fun showResult(text: String) {
        binding.tvCenterStatus.text = ""
        binding.dialogLayout.visibility = View.VISIBLE
        binding.tvDialogResult.text = text
        speakOut(text)
    }

    override fun onError(error: String, errorCode: Int) {
        Log.e("Balance", error)
    }

    override fun onDestroyView() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        _binding = null
        super.onDestroyView()
        timer?.cancel()
        backgroundExecutor.shutdown()
    }
}