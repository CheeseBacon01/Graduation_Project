package com.google.mediapipe.examples.poselandmarker.fragment

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.mediapipe.examples.poselandmarker.PoseLandmarkerHelper
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentTimedUpAndGoBinding
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

// 定義 TUG 測試的自動化狀態流程
enum class TugAutoState {
    INPUT_HEIGHT,
    PREPARING_DISTANCE, // 退後 3 公尺
    WAITING_FOR_SIT,    // 等待坐下
    COUNTDOWN_TO_START, // 隨機 1~5 秒等待發出語音
    WALKING_FORWARD,    // 起步往前走
    TURNING_BACK,       // 走到靠近鏡頭後，準備折返
    FINISHED            // 走回椅子坐下完成
}

class TimedUpAndGoFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    private var _binding: FragmentTimedUpAndGoBinding? = null
    private val binding get() = _binding!!
    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private lateinit var backgroundExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_BACK

    private var startTime = 0L
    private var isTesting = false
    private var currentSeconds = 0f

    private var currentState = TugAutoState.INPUT_HEIGHT
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var tts: TextToSpeech
    private var isTtsReady = false
    private var lastSpeakTime = 0L
    private var lastSpeechText = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTimedUpAndGoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 初始化語音引擎
        tts = TextToSpeech(requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.TAIWAN
                isTtsReady = true
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

        showHeightDialog()

        binding.btnReset.setOnClickListener {
            resetForNextTrial(true)
        }

        binding.btnUnable.setOnClickListener {
            finishTest(isUnable = true)
        }

        binding.btnDialogOk.setOnClickListener {
            binding.dialogLayout.visibility = View.GONE
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

    private fun speakOut(text: String, throttleMs: Long = 0L) {
        val currentTime = SystemClock.uptimeMillis()
        if (isTtsReady && (text != lastSpeechText || (currentTime - lastSpeakTime > throttleMs))) {
            if (::tts.isInitialized) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            }
            lastSpeakTime = currentTime
            lastSpeechText = text
        }
    }


    private fun showHeightDialog() {
        currentState = TugAutoState.INPUT_HEIGHT
        val input = EditText(requireContext())
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.hint = "例如: 165"

        AlertDialog.Builder(requireContext())
            .setTitle("準備開始")
            .setMessage("請輸入受測者的身高 (公分)：")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("確定") { _, _ ->
                val heightStr = input.text.toString()
                binding.overlay.userHeightCm = heightStr.toFloatOrNull() ?: 160f

                // 一開始隱藏終點線，等起步才畫
                binding.overlay.isTestingGait = false

                // 進入退後狀態，並顯示大型提示文字
                currentState = TugAutoState.PREPARING_DISTANCE
                binding.tvCenterStatus.text = "請退後至\n3公尺以上"
                binding.tvCenterStatus.textSize = 50f
            }
            .show()
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
        val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).setTargetRotation(binding.viewFinder.display.rotation).build()
        val imageAnalyzer = ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).setTargetRotation(binding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
            .also { it.setAnalyzer(backgroundExecutor) { image -> processImageProxy(image) } }
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
        preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        if (!::poseLandmarkerHelper.isInitialized) { imageProxy.close(); return }
        poseLandmarkerHelper.detectLiveStream(imageProxy, cameraFacing == CameraSelector.LENS_FACING_FRONT)
    }

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        activity?.runOnUiThread {
            if (_binding != null) {
                val results = resultBundle.results.firstOrNull() ?: return@runOnUiThread
                if (results.landmarks().isEmpty()) return@runOnUiThread

                val landmarks = results.landmarks()[0]
                val currentDist = binding.overlay.currentDistance
                var statusMsg = ""

                // === 自動化 TUG 狀態機 ===
                when (currentState) {
                    TugAutoState.PREPARING_DISTANCE -> {
                        statusMsg = String.format(Locale.TRADITIONAL_CHINESE, "目前距離: %.1fm", currentDist)
                        if (currentDist >= 3.0f) {
                            currentState = TugAutoState.WAITING_FOR_SIT
                            // 距離大於 3m 後，改變中央大字體提示
                            binding.tvCenterStatus.text = "請坐下"
                            speakOut("請坐下")
                        } else {
                            speakOut("請退後至三公尺處", 3000L)
                        }
                    }
                    TugAutoState.WAITING_FOR_SIT -> {
                        statusMsg = String.format(Locale.TRADITIONAL_CHINESE, "目前距離: %.1fm", currentDist)
                        if (isSitting(landmarks)) {
                            currentState = TugAutoState.COUNTDOWN_TO_START
                            // 偵測到坐下後，顯示準備起步
                            binding.tvCenterStatus.text = "準備起步..."
                            // 觸發隨機等待語音 (傳入當下距離作為計算終點線的基準)
                            startRandomCountdown(currentDist)
                        } else {
                            speakOut("請坐下", 3000L)
                        }
                    }
                    TugAutoState.COUNTDOWN_TO_START -> {
                        statusMsg = "等待語音提示..."
                    }
                    TugAutoState.WALKING_FORWARD -> {
                        isTesting = true
                        currentSeconds = (SystemClock.elapsedRealtime() - startTime) / 1000f
                        statusMsg = "請向前走 3 公尺 (走到紅線處折返)"

                        // 走到終點線附近 (容錯 0.6m) 視為完成去程
                        if (currentDist <= binding.overlay.targetDistance + 0.6f && currentDist > 0) {
                            currentState = TugAutoState.TURNING_BACK
                            binding.overlay.isTestingGait = false
                            speakOut("回原位坐好")
                        }
                    }
                    TugAutoState.TURNING_BACK -> {
                        currentSeconds = (SystemClock.elapsedRealtime() - startTime) / 1000f
                        statusMsg = "請轉身走回椅子並坐下"
                        speakOut("回原位坐好",3000L)

                        // 距離大於 2.5m 且再次偵測到坐下姿勢，即完成測試
                        if (currentDist >= 2.5f && isSitting(landmarks)) {
                            finishTest(isUnable = false)
                        }
                    }
                    else -> {
                        statusMsg = if (isTesting) "測試中" else "請輸入身高"
                    }
                }

                binding.overlay.setPoseResults(results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM)

                binding.overlay.updateTestInfo(
                    count = 0,
                    sets = 1,
                    message = statusMsg,
                    accuracy = 0f,
                    label = "計時起身行走測試",
                    maxSets = 0,
                    setLabel = "",
                    time = String.format(Locale.US, "%.2f", currentSeconds),
                    showAccuracy = false
                )
            }
        }
    }

    // 判斷是否為坐下姿勢
    private fun isSitting(landmarks: List<NormalizedLandmark>): Boolean {
        val leftHip = landmarks[23]
        val leftKnee = landmarks[25]
        val leftAnkle = landmarks[27]

        if (leftHip.visibility().orElse(0f) < 0.5f || leftKnee.visibility().orElse(0f) < 0.5f) {
            return false
        }

        val hipKneeY = abs(leftHip.y() - leftKnee.y())
        val kneeAnkleY = abs(leftKnee.y() - leftAnkle.y())

        // 坐下時大腿與地板平行，因此大腿在畫面 Y 軸的長度會縮短
        return hipKneeY < (kneeAnkleY * 0.6f)
    }

    // 執行 1~5 秒隨機等待並用語音觸發開始
    private fun startRandomCountdown(chairDistance: Float) {
        val delayMs = (1000..5000).random().toLong()
        mainHandler.postDelayed({
            if (currentState == TugAutoState.COUNTDOWN_TO_START) {
                // 1. 發出起步走聲音
                speakOut("起步走")

                // 2. 畫出 3m 終點折返線
                binding.overlay.targetDistance = max(0.2f, chairDistance - 3.0f)
                binding.overlay.isTestingGait = true

                // 3. 清空中央大字體
                binding.tvCenterStatus.text = ""

                // 4. 開始計時並切換狀態
                startTime = SystemClock.elapsedRealtime()
                currentState = TugAutoState.WALKING_FORWARD
            }
        }, delayMs)
    }

    private fun finishTest(isUnable: Boolean) {
        currentState = TugAutoState.FINISHED
        isTesting = false
        binding.overlay.isTestingGait = false

        if (isUnable) {
            speakOut("無法執行")
            binding.dialogLayout.visibility = View.VISIBLE
            binding.tvDialogResult.text = "無法執行 (未獲得分數)"
            return
        }
        speakOut("測驗完成")
        val duration = (SystemClock.elapsedRealtime() - startTime) / 1000f
        val resultText = if (duration > 20f) "超過 20 秒" else "未超過 20 秒"

        binding.dialogLayout.visibility = View.VISIBLE
        binding.tvDialogResult.text = String.format(Locale.US, "執行時間: %.2fs\n結果: %s", duration, resultText)
    }

    private fun resetForNextTrial(askHeight: Boolean) {
        // 清除尚未發生的語音事件與狀態
        mainHandler.removeCallbacksAndMessages(null)

        binding.tvCenterStatus.text = ""
        binding.overlay.isTestingGait = false
        currentSeconds = 0f
        isTesting = false
        lastSpeechText = ""

        if (askHeight) {
            showHeightDialog()
        } else {
            currentState = TugAutoState.PREPARING_DISTANCE
            binding.tvCenterStatus.text = "請退後至\n3公尺以上"
            binding.tvCenterStatus.textSize = 50f
        }
    }

    override fun onError(error: String, errorCode: Int) { Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show() }

    override fun onDestroyView() {
        mainHandler.removeCallbacksAndMessages(null)
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        isTtsReady = false
        _binding = null
        super.onDestroyView()
        backgroundExecutor.shutdown()
    }
}