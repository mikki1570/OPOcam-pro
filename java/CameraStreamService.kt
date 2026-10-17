package com.my.remotewebcam

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.graphics.*
import android.media.*
import android.os.*
import android.util.Base64
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import fi.iki.elonen.NanoHTTPD
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class CameraStreamService : LifecycleService() {

    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var webServer: CamHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var latestJpegBytes: ByteArray? = null
    @Volatile private var useFrontCamera = false
    @Volatile private var motionDetectionEnabled = false
    private var previousMotionGrid: IntArray? = null
    private var lastMotionTime = 0L

    @Volatile private var minZoomRatio = 1.0f
    @Volatile private var maxZoomRatio = 8.0f
    @Volatile private var minExposure = -4
    @Volatile private var maxExposure = 4

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val cleanupScheduler = Executors.newSingleThreadScheduledExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var videoDir: File
    private var lowBatteryAlertSent = false
    private var overheatAlertSent = false

    override fun onCreate() {
        super.onCreate()
        videoDir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
        startForegroundServiceNotification()
        acquireWakeLock()
        startCamera()
        webServer = CamHttpServer(8080).apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }

        cleanupScheduler.scheduleAtFixedRate({ cleanOldRecordings() }, 1, 60, TimeUnit.MINUTES)

        sendTelegramMessage("🟢 Сервер PRO запущен!\n${getBatteryAndTempInfo()}")
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteWebcam::WakeLock").apply {
            acquire()
        }
    }

    private fun startForegroundServiceNotification() {
        val channelId = "cam_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Camera Stream", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Remote Webcam PRO 🎥")
            .setContentText("Сервер активен на порту :8080")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processFrame(imageProxy)
                imageProxy.close()
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, selector, imageAnalysis, videoCapture)

                camera?.cameraInfo?.zoomState?.value?.let {
                    minZoomRatio = it.minZoomRatio
                    maxZoomRatio = it.maxZoomRatio
                }
                camera?.cameraInfo?.exposureState?.let {
                    minExposure = it.exposureCompensationRange.lower
                    maxExposure = it.exposureCompensationRange.upper
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(image: ImageProxy) {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        if (motionDetectionEnabled) {
            detectMotion(yBuffer, image.width, image.height)
            yBuffer.rewind()
        }

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 75, out)
        latestJpegBytes = out.toByteArray()
    }

    private fun detectMotion(yBuffer: ByteBuffer, width: Int, height: Int) {
        val step = 32
        val cols = width / step
        val rows = height / step
        val currentGrid = IntArray(cols * rows)
        var changedBlocks = 0

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val idx = (r * step) * width + (c * step)
                if (idx < yBuffer.limit()) {
                    val lum = yBuffer.get(idx).toInt() and 0xFF
                    val gridIdx = r * cols + c
                    currentGrid[gridIdx] = lum
                    previousMotionGrid?.let { prev ->
                        if (gridIdx < prev.size && abs(lum - prev[gridIdx]) > 28) {
                            changedBlocks++
                        }
                    }
                }
            }
        }
        previousMotionGrid = currentGrid

        val threshold = (cols * rows) * 0.06
        val now = System.currentTimeMillis()
        if (changedBlocks > threshold && (now - lastMotionTime) > 20_000) {
            lastMotionTime = now
            sendTelegramPhoto("🚨 ОБНАРУЖЕНО ДВИЖЕНИЕ В КАДРЕ! Включаю автозапись на 15 сек...")
            mainHandler.post {
                if (activeRecording == null) {
                    toggleRecording()
                    mainHandler.postDelayed({
                        if (activeRecording != null) toggleRecording()
                    }, 15_000)
                }
            }
        }
    }

    fun switchCamera() {
        useFrontCamera = !useFrontCamera
        previousMotionGrid = null
        startCamera()
    }

    fun setZoom(zoomRatio: Float) {
        ContextCompat.getMainExecutor(this).execute {
            camera?.cameraControl?.setZoomRatio(zoomRatio.coerceIn(minZoomRatio, maxZoomRatio))
        }
    }

    fun setExposure(index: Int) {
        ContextCompat.getMainExecutor(this).execute {
            camera?.cameraControl?.setExposureCompensationIndex(index.coerceIn(minExposure, maxExposure))
        }
    }

    fun toggleTorch(enable: Boolean) {
        ContextCompat.getMainExecutor(this).execute {
            camera?.cameraControl?.enableTorch(enable)
        }
    }

    fun triggerSiren() {
        networkExecutor.execute {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
                val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                repeat(6) {
                    toneGen.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 450)
                    Thread.sleep(500)
                }
                toneGen.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun cleanOldRecordings(): Int {
        val prefs = getSharedPreferences("cam_prefs", Context.MODE_PRIVATE)
        val days = prefs.getInt("keep_days", 7)
        val cutoffTime = System.currentTimeMillis() - (days * 24L * 3600L * 1000L)
        var deletedCount = 0

        val files = videoDir.listFiles()?.sortedBy { it.lastModified() } ?: return 0
        for (file in files) {
            if (file.lastModified() < cutoffTime) {
                if (file.delete()) deletedCount++
            }
        }

        val remainingFiles = videoDir.listFiles()?.sortedBy { it.lastModified() }?.toMutableList() ?: mutableListOf()
        while (videoDir.usableSpace < 1_500_000_000L && remainingFiles.isNotEmpty()) {
            val oldest = remainingFiles.removeAt(0)
            if (oldest.delete()) deletedCount++
        }
        return deletedCount
    }

    fun getBatteryAndTempInfo(): String {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempC = tempTenths / 10.0

        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0

        if (pct in 1..15 && !isCharging && !lowBatteryAlertSent) {
            lowBatteryAlertSent = true
            sendTelegramMessage("⚠️ Внимание! Низкий заряд батареи: $pct%")
        } else if (pct > 20) lowBatteryAlertSent = false

        if (tempC >= 45.0 && !overheatAlertSent) {
            overheatAlertSent = true
            sendTelegramMessage("🔥 ВНИМАНИЕ! Перегрев телефона: $tempC°C!")
        } else if (tempC < 41.0) overheatAlertSent = false

        return "🔋 $pct% ${if (isCharging) "⚡" else ""} | 🌡️ ${tempC}°C"
    }

    fun sendTelegramMessage(text: String) {
        val prefs = getSharedPreferences("cam_prefs", Context.MODE_PRIVATE)
        val token = prefs.getString("tg_token", "") ?: ""
        val chatId = prefs.getString("tg_chat", "") ?: ""
        if (token.isEmpty() || chatId.isEmpty()) return

        networkExecutor.execute {
            try {
                val encoded = URLEncoder.encode(text, "UTF-8")
                val url = URL("https://api.telegram.org/bot$token/sendMessage?chat_id=$chatId&text=$encoded")
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    responseCode
                    disconnect()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun sendTelegramPhoto(captionText: String = "📸 Кадр с камеры (${getBatteryAndTempInfo()})") {
        val prefs = getSharedPreferences("cam_prefs", Context.MODE_PRIVATE)
        val token = prefs.getString("tg_token", "") ?: ""
        val chatId = prefs.getString("tg_chat", "") ?: ""
        val bytes = latestJpegBytes ?: return
        if (token.isEmpty() || chatId.isEmpty()) return

        networkExecutor.execute {
            try {
                val boundary = "----WebCamBoundary" + System.currentTimeMillis()
                val url = URL("https://api.telegram.org/bot$token/sendPhoto")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    doOutput = true
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                }
                val out = DataOutputStream(conn.outputStream)
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n$chatId\r\n")
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"caption\"\r\n\r\n")
                out.write(captionText.toByteArray(Charsets.UTF_8))
                out.writeBytes("\r\n--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"photo\"; filename=\"snap.jpg\"\r\n")
                out.writeBytes("Content-Type: image/jpeg\r\n\r\n")
                out.write(bytes)
                out.writeBytes("\r\n--$boundary--\r\n")
                out.flush()
                out.close()
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun toggleRecording(): String {
        val vc = videoCapture ?: return "Камера не готова"
        if (activeRecording != null) {
            activeRecording?.stop()
            activeRecording = null
            sendTelegramMessage("⏹ Запись видео сохранена в архив.")
            return "stopped"
        } else {
            cleanOldRecordings()
            val fileName = "VID_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val file = File(videoDir, fileName)
            val outputOptions = FileOutputOptions.Builder(file).build()
            activeRecording = vc.output
                .prepareRecording(this, outputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this)) {}
            sendTelegramMessage("🔴 Начата запись видео: $fileName")
            return "recording: $fileName"
        }
    }

    @SuppressLint("MissingPermission")
    inner class LiveAudioInputStream : InputStream() {
        private val sampleRate = 16000
        private val bufSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        private val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
        )
        private val header = createWavHeader(sampleRate)
        private var headerPos = 0

        init {
            recorder.startRecording()
        }

        private fun createWavHeader(rate: Int): ByteArray {
            val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put("RIFF".toByteArray())
            buffer.putInt(Int.MAX_VALUE)
            buffer.put("WAVEfmt ".toByteArray())
            buffer.putInt(16)
            buffer.putShort(1)
            buffer.putShort(1)
            buffer.putInt(rate)
            buffer.putInt(rate * 2)
            buffer.putShort(2)
            buffer.putShort(16)
            buffer.put("data".toByteArray())
            buffer.putInt(Int.MAX_VALUE)
            return buffer.array()
        }

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) > 0) b[0].toInt() and 0xFF else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (headerPos < header.size) {
                val toCopy = minOf(len, header.size - headerPos)
                System.arraycopy(header, headerPos, b, off, toCopy)
                headerPos += toCopy
                return toCopy
            }
            return recorder.read(b, off, len)
        }

        override fun close() {
            try {
                recorder.stop()
                recorder.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            super.close()
        }
    }

    inner class CamHttpServer(port: Int) : NanoHTTPD(port) {

        private fun isAuthorized(session: IHTTPSession): Boolean {
            val prefs = getSharedPreferences("cam_prefs", Context.MODE_PRIVATE)
            val pass = prefs.getString("auth_pass", "") ?: ""
            if (pass.isEmpty()) return true
            val user = prefs.getString("auth_user", "admin") ?: "admin"
            val authHeader = session.headers["authorization"] ?: return false
            if (!authHeader.startsWith("Basic ", ignoreCase = true)) return false
            val base64Creds = authHeader.substring(6).trim()
            val decoded = String(Base64.decode(base64Creds, Base64.DEFAULT), Charsets.UTF_8)
            return decoded == "$user:$pass"
        }

        override fun serve(session: IHTTPSession): Response {
            if (!isAuthorized(session)) {
                return newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED, "text/plain", "Требуется авторизация 🔐"
                ).apply {
                    addHeader("WWW-Authenticate", "Basic realm=\"Remote Webcam PRO\"")
                }
            }

            val prefs = getSharedPreferences("cam_prefs", Context.MODE_PRIVATE)

            return when (session.uri) {
                "/" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", getHtmlPage())

                "/zoom" -> {
                    val ratio = session.parms["val"]?.toFloatOrNull() ?: 1.0f
                    setZoom(ratio)
                    newFixedLengthResponse("OK")
                }

                "/exposure" -> {
                    val exp = session.parms["val"]?.toIntOrNull() ?: 0
                    setExposure(exp)
                    newFixedLengthResponse("OK")
                }

                "/switch_cam" -> {
                    switchCamera()
                    newFixedLengthResponse(if (useFrontCamera) "Фронтальная" else "Основная")
                }

                "/motion" -> {
                    motionDetectionEnabled = session.parms["on"] == "1"
                    previousMotionGrid = null
                    newFixedLengthResponse(if (motionDetectionEnabled) "ON" else "OFF")
                }

                "/torch" -> {
                    toggleTorch(session.parms["on"] == "1")
                    newFixedLengthResponse("OK")
                }

                "/siren" -> {
                    triggerSiren()
                    newFixedLengthResponse("🚨 Сирена включена!")
                }

                "/audio.wav" -> {
                    newChunkedResponse(Response.Status.OK, "audio/wav", LiveAudioInputStream())
                }

                "/retention" -> {
                    val days = session.parms["days"]?.toIntOrNull() ?: 7
                    prefs.edit().putInt("keep_days", days).apply()
                    val deleted = cleanOldRecordings()
                    newFixedLengthResponse("Хранение: $days дн. (Удалено старых: $deleted)")
                }

                "/delete_file" -> {
                    val name = session.parms["name"] ?: ""
                    val file = File(videoDir, name)
                    if (file.exists() && file.parentFile == videoDir) file.delete()
                    newFixedLengthResponse("OK")
                }

                "/status" -> {
                    val freeGb = String.format(Locale.US, "%.1f", videoDir.usableSpace / 1e9)
                    val keepDays = prefs.getInt("keep_days", 7)
                    val json = """{"bat":"${getBatteryAndTempInfo()} | 💾 Свободно: ${freeGb} ГБ","minZoom":$minZoomRatio,"maxZoom":$maxZoomRatio,"minExp":$minExposure,"maxExp":$maxExposure,"motion":$motionDetectionEnabled,"days":$keepDays}"""
                    newFixedLengthResponse(Response.Status.OK, "application/json", json)
                }

                "/record" -> newFixedLengthResponse(toggleRecording())

                "/tg_snap" -> {
                    sendTelegramPhoto()
                    newFixedLengthResponse("Фото отправлено в Telegram ✈️")
                }

                "/tg_config" -> {
                    val token = session.parms["token"] ?: ""
                    val chat = session.parms["chat"] ?: ""
                    prefs.edit().putString("tg_token", token).putString("tg_chat", chat).apply()
                    sendTelegramMessage("✅ Настройки Telegram успешно обновлены!")
                    newFixedLengthResponse("Сохранено!")
                }

                "/files" -> {
                    val files = videoDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
                    val dateFmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
                    val links = files.joinToString("") {
                        val sizeMb = String.format(Locale.US, "%.1f", it.length() / 1048576.0)
                        "<div style='display:flex; justify-content:space-between; align-items:center; background:#2a2a2a; padding:8px 12px; margin:5px 0; border-radius:6px;'>" +
                        "<a href='/download?name=${it.name}' style='color:#4CAF50; text-decoration:none; font-weight:bold;' target='_blank'>🎬 ${it.name} (${sizeMb} МБ — ${dateFmt.format(Date(it.lastModified()))})</a>" +
                        "<button onclick=\"delFile('${it.name}')\" style='background:#c62828; padding:5px 10px; font-size:13px;'>🗑️</button>" +
                        "</div>"
                    }
                    newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", links.ifEmpty { "Пока нет записей в архиве" })
                }

                "/download" -> {
                    val name = session.parms["name"] ?: return newFixedLengthResponse("No file")
                    val file = File(videoDir, name)
                    if (!file.exists()) return newFixedLengthResponse("Not found")
                    newChunkedResponse(Response.Status.OK, "video/mp4", FileInputStream(file))
                }

                "/frame.jpg" -> {
                    val bytes = latestJpegBytes ?: return newFixedLengthResponse("No frame")
                    newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong())
                }

                else -> newFixedLengthResponse("Not Found")
            }
        }

        private fun getHtmlPage(): String = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Remote Webcam PRO 🎥</title>
                <style>
                    body { background: #121212; color: white; font-family: system-ui, sans-serif; text-align: center; margin: 0; padding: 12px; }
                    .status-bar { background: #1e1e1e; padding: 10px 18px; border-radius: 10px; display: inline-block; margin-bottom: 10px; font-weight: bold; font-size: 14px; border: 1px solid #333; }
                    .video-box { width: 100%; max-width: 760px; height: 460px; margin: 0 auto; display: flex; align-items: center; justify-content: center; overflow: hidden; background: #000; border-radius: 12px; border: 2px solid #333; }
                    img { max-width: 100%; max-height: 100%; transition: transform 0.25s ease; }
                    .controls { margin: 12px auto; max-width: 720px; background: #1e1e1e; padding: 15px; border-radius: 12px; }
                    .slider-box { margin: 8px 0; background: #262626; padding: 10px; border-radius: 8px; }
                    button { padding: 10px 14px; margin: 4px; font-size: 14px; border-radius: 8px; border: none; cursor: pointer; background: #2196F3; color: white; font-weight: bold; }
                    button.rec { background: #e53935; }
                    button.tg { background: #0088cc; }
                    button.warn { background: #ff6f00; }
                    select, input[type=text] { padding: 8px; border-radius: 6px; border: 1px solid #444; background: #2c2c2c; color: white; margin: 4px; }
                    input[type=range] { width: 75%; vertical-align: middle; }
                </style>
            </head>
            <body>
                <div class="status-bar" id="bat">Загрузка параметров...</div>

                <div class="video-box">
                    <img id="cam" src="/frame.jpg" />
                </div>

                <div class="controls">
                    <div class="slider-box">
                        <label>🔍 Зум (Ширик ↔ Телевик): <b id="zval">1.0</b>x</label><br>
                        <button onclick="setQuickZoom(minZ)" style="padding:4px 8px; font-size:12px;">0.5x Ширик</button>
                        <input type="range" id="zoomRange" min="0.5" max="8" step="0.1" value="1" oninput="setZoom(this.value)">
                        <button onclick="setQuickZoom(1.0)" style="padding:4px 8px; font-size:12px;">1.0x Норм</button>
                    </div>

                    <div class="slider-box">
                        <label>🌙 Ночной режим (Яркость матрицы): <b id="eval">0</b></label><br>
                        <input type="range" id="expRange" min="-4" max="4" step="1" value="0" oninput="setExp(this.value)">
                    </div>

                    <button onclick="rotateCam()">🔄 Поворот 90°</button>
                    <button onclick="switchCam()">📷 Сменить камеру</button>
                    <button onclick="toggleTorch()" id="torchBtn">🔦 Вспышка</button>
                    <button class="rec" onclick="toggleRec()" id="recBtn">🔴 Запись</button>
                    <button onclick="toggleMotion()" id="motBtn" style="background:#455a64;">🛡️ Охрана (Движение): ВЫКЛ</button>
                    <button onclick="toggleAudio()" id="audBtn" style="background:#00897b;">🎙️ Слушать микрофон</button>
                    <button class="warn" onclick="siren()">🚨 Сирена</button>
                    <button class="tg" onclick="sendSnap()">✈️ Фото в ТГ</button>

                    <audio id="liveAudio" style="display:none;"></audio>

                    <hr style="border-color:#333; margin: 14px 0;">

                    <div>
                        <label>♻️ Автоочистка архива (хранить видео): </label>
                        <select id="keepDays" onchange="updateRetention(this.value)">
                            <option value="1">1 день</option>
                            <option value="3">3 дня</option>
                            <option value="5">5 дней</option>
                            <option value="7" selected>7 дней</option>
                            <option value="14">14 дней</option>
                            <option value="30">30 дней</option>
                        </select>
                        <button onclick="loadFiles()">📂 Открыть архив записей</button>
                    </div>

                    <div id="fileList" style="margin-top:10px; text-align:left;"></div>

                    <hr style="border-color:#333; margin: 14px 0;">
                    <div>
                        <small>⚙️ Настройки уведомлений Telegram:</small><br>
                        <input type="text" id="tgToken" placeholder="Bot Token" style="width:40%;">
                        <input type="text" id="tgChat" placeholder="Chat ID" style="width:35%;">
                        <button class="tg" onclick="saveTg()">💾</button>
                    </div>
                </div>

                <script>
                    const img = document.getElementById('cam');
                    let angle = 0, torch = false, motion = false, listening = false, minZ = 0.5;

                    setInterval(() => { img.src = '/frame.jpg?t=' + Date.now(); }, 90);

                    function syncStatus() {
                        fetch('/status').then(r => r.json()).then(d => {
                            document.getElementById('bat').innerText = d.bat;
                            minZ = d.minZoom;
                            const zr = document.getElementById('zoomRange');
                            zr.min = d.minZoom;
                            zr.max = d.maxZoom;
                            const er = document.getElementById('expRange');
                            er.min = d.minExp;
                            er.max = d.maxExp;
                            document.getElementById('keepDays').value = d.days;
                            motion = d.motion;
                            updateMotionBtn();
                        });
                    }
                    syncStatus();
                    setInterval(syncStatus, 12000);

                    function rotateCam() {
                        angle = (angle + 90) % 360;
                        img.style.transform = 'rotate(' + angle + 'deg)';
                    }
                    function switchCam() {
                        fetch('/switch_cam').then(() => setTimeout(syncStatus, 1000));
                    }
                    function setQuickZoom(val) {
                        document.getElementById('zoomRange').value = val;
                        setZoom(val);
                    }
                    function setZoom(val) {
                        document.getElementById('zval').innerText = Number(val).toFixed(1);
                        fetch('/zoom?val=' + val);
                    }
                    function setExp(val) {
                        document.getElementById('eval').innerText = val;
                        fetch('/exposure?val=' + val);
                    }
                    function toggleTorch() {
                        torch = !torch;
                        fetch('/torch?on=' + (torch ? '1' : '0'));
                        document.getElementById('torchBtn').style.background = torch ? '#FFA000' : '#2196F3';
                    }
                    function toggleMotion() {
                        motion = !motion;
                        fetch('/motion?on=' + (motion ? '1' : '0'));
                        updateMotionBtn();
                    }
                    function updateMotionBtn() {
                        const btn = document.getElementById('motBtn');
                        btn.innerText = motion ? '🛡️ Охрана (Движение): ВКЛ' : '🛡️ Охрана (Движение): ВЫКЛ';
                        btn.style.background = motion ? '#2e7d32' : '#455a64';
                    }
                    function toggleAudio() {
                        const aud = document.getElementById('liveAudio');
                        const btn = document.getElementById('audBtn');
                        listening = !listening;
                        if (listening) {
                            aud.src = '/audio.wav?t=' + Date.now();
                            aud.play();
                            btn.innerText = '🔇 Выкл. микрофон';
                            btn.style.background = '#e53935';
                        } else {
                            aud.pause();
                            aud.src = '';
                            btn.innerText = '🎙️ Слушать микрофон';
                            btn.style.background = '#00897b';
                        }
                    }
                    function siren() {
                        if (confirm('Включить громкую сирену на телефоне? 🚨')) {
                            fetch('/siren').then(r => r.text()).then(alert);
                        }
                    }
                    function updateRetention(days) {
                        fetch('/retention?days=' + days).then(r => r.text()).then(alert);
                    }
                    function toggleRec() {
                        fetch('/record').then(r => r.text()).then(txt => {
                            document.getElementById('recBtn').innerText = txt.startsWith('recording') ? '⏹ Стоп' : '🔴 Запись';
                        });
                    }
                    function sendSnap() {
                        fetch('/tg_snap').then(r => r.text()).then(alert);
                    }
                    function saveTg() {
                        const tk = encodeURIComponent(document.getElementById('tgToken').value);
                        const ch = encodeURIComponent(document.getElementById('tgChat').value);
                        fetch('/tg_config?token=' + tk + '&chat=' + ch).then(r => r.text()).then(alert);
                    }
                    function loadFiles() {
                        fetch('/files').then(r => r.text()).then(html => {
                            document.getElementById('fileList').innerHTML = html;
                        });
                    }
                    function delFile(name) {
                        fetch('/delete_file?name=' + encodeURIComponent(name)).then(() => loadFiles());
                    }
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    override fun onDestroy() {
        sendTelegramMessage("🔴 Сервер камеры остановлен.")
        webServer?.stop()
        wakeLock?.release()
        cleanupScheduler.shutdown()
        cameraExecutor.shutdown()
        networkExecutor.shutdown()
        super.onDestroy()
    }
}
