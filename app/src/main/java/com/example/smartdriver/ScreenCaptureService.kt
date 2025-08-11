package com.example.smartdriver

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.smartdriver.utils.ImageAnalysisUtils
import com.example.smartdriver.utils.OfferData
import com.example.smartdriver.utils.OcrTextRecognizer
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import java.util.ArrayDeque

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val CHANNEL_NAME = "Screen Capture Service"

        const val ACTION_STOP_CAPTURE = "com.example.smartdriver.screen_capture.STOP"
        const val ACTION_CAPTURE_NOW = "com.example.smartdriver.screen_capture.CAPTURE_NOW"
        const val ACTION_UPDATE_SETTINGS = "com.example.smartdriver.screen_capture.UPDATE_SETTINGS"
        const val ACTION_SAVE_LAST_VALID_OFFER_SCREENSHOT = "com.example.smartdriver.screen_capture.SAVE_LAST_VALID"
        const val ACTION_FREEZE_OCR = "com.example.smartdriver.screen_capture.FREEZE_OCR"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val KEY_SAVE_IMAGES = "save_images"

        @JvmStatic val isRunning = AtomicBoolean(false)

        // Controlo base
        private const val OCR_IMAGE_SCALE_FACTOR = 1.0f
        private const val OFFER_PROCESSING_LOCK_PERIOD_MS = 3000L
        private const val HASH_CACHE_DURATION_MS = 800L
    }

    // Projeção / Captura
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    // Estado/Threads
    private val isCapturingActive = AtomicBoolean(false)
    private val isProcessingImage = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var processingThread: HandlerThread
    private lateinit var processingHandler: Handler

    // Config/Análise
    private lateinit var preferences: SharedPreferences
    @Volatile private var shouldSaveScreenshots = false
    private val imageAnalysisUtils = ImageAnalysisUtils()
    private val ocr: OcrTextRecognizer = OcrTextRecognizer.getInstance()

    // Duplicação/recência
    @Volatile private var lastOfferDetectedTime = 0L
    private var lastDetectedOfferSignature: String? = null

    // Hash cache
    private var lastScreenHash: Int? = null
    private var lastHashTime: Long = 0L

    // Screenshot pendente
    @Volatile private var lastBitmapForPotentialSave: Bitmap? = null

    // Outros
    private var screenshotCounter = 0
    private var initialResultCode: Int = Activity.RESULT_CANCELED
    private var initialResultData: Intent? = null

    // Freeze ao toque
    @Volatile private var ocrFreezeUntil = 0L
    private fun isOcrAllowedNow(): Boolean = System.currentTimeMillis() >= ocrFreezeUntil
    fun freezeOcrFor(ms: Long = 900L) { ocrFreezeUntil = System.currentTimeMillis() + ms }

    // Estabilidade multi-frame
    private class StabilityWindow(
        private val windowMs: Long = 1200L,
        private val minVotes: Int = 3
    ) {
        private data class Item(val sig: String, val t: Long, val data: OfferData)
        private val items = ArrayDeque<Item>()
        fun add(candidate: OfferData): OfferData? {
            val now = System.currentTimeMillis()
            val sig = candidateSignature(candidate)
            items.addLast(Item(sig, now, candidate))
            while (items.isNotEmpty() && now - items.first.t > windowMs) items.removeFirst()
            val counts = mutableMapOf<String, Int>()
            for (it in items) counts[it.sig] = (counts[it.sig] ?: 0) + 1
            val bestSig = counts.maxByOrNull { it.value }?.key ?: return null
            val votes = counts[bestSig] ?: 0
            return if (votes >= minVotes) items.lastOrNull { it.sig == bestSig }?.data else null
        }
        private fun candidateSignature(o: OfferData): String {
            fun n(s: String?) = (s ?: "").replace(",", ".").trim()
            return "v:${n(o.value)}|pd:${n(o.pickupDistance)}|td:${n(o.tripDistance)}|pt:${n(o.pickupDuration)}|tt:${n(o.tripDuration)}"
        }
        fun clear() = items.clear()
    }
    private val stability = StabilityWindow(windowMs = 1200L, minVotes = 3)

    // Regras de coerência
    private data class BizRules(
        val minEurPerKm: Double = 0.38,
        val minMinutes: Int = 7,
        val lowFareThreshold: Double = 3.50,
        val shortTotalKm: Double = 8.0,
        val maxPickupRatio: Double = 0.80,
        val maxAbsurdPrice: Double = 100.0 // anti-lixo: >100€ é inválido
    )
    private val rules = BizRules()

    private enum class Verdict { VALID, SUSPECT, INVALID }

    private fun classify(stable: OfferData): Verdict {
        fun d(s: String?) = s?.replace(",", ".")?.toDoubleOrNull()
        fun i(s: String?) = s?.toIntOrNull()

        val price = d(stable.value) ?: return Verdict.INVALID
        if (price <= 0.2) return Verdict.INVALID
        if (price > rules.maxAbsurdPrice) return Verdict.INVALID

        val pd = d(stable.pickupDistance) ?: 0.0
        val td = d(stable.tripDistance) ?: 0.0
        val pt = i(stable.pickupDuration) ?: 0
        val tt = i(stable.tripDuration) ?: 0

        val totalKm = (pd + td).takeIf { it > 0 } ?: td
        val totalMin = (pt + tt).takeIf { it > 0 } ?: tt

        val isLowShort = price <= rules.lowFareThreshold && totalKm <= rules.shortTotalKm

        val eurKm = if (totalKm > 0.05) price / totalKm else Double.POSITIVE_INFINITY
        val priceOk = isLowShort || eurKm >= rules.minEurPerKm
        val timeOk  = isLowShort || totalMin >= rules.minMinutes
        val pickupOk = if (totalKm <= 0.1) true else (pd <= rules.maxPickupRatio * totalKm)

        val nearPrice = !priceOk && eurKm >= (rules.minEurPerKm * 0.9)
        val nearTime  = !timeOk  && totalMin >= (rules.minMinutes - 1)
        val nearPickup = !pickupOk && totalKm > 0.1 && pd <= (rules.maxPickupRatio * 1.1) * totalKm

        return when {
            priceOk && timeOk && pickupOk -> Verdict.VALID
            nearPrice || nearTime || nearPickup -> Verdict.SUSPECT
            else -> Verdict.INVALID
        }
    }

    // Controlo de recaptura curta
    @Volatile private var lastSuspectSignature: String? = null
    @Volatile private var lastSuspectAt: Long = 0L
    private val RECAPTURE_DELAY_MS = 600L
    private val RECAPTURE_COOLDOWN_MS = 2000L

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Serviço Criado")
        isRunning.set(true)
        processingThread = HandlerThread("ImageProcessingThread", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        processingHandler = Handler(processingThread.looper)
        getScreenMetrics()
        preferences = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        shouldSaveScreenshots = preferences.getBoolean(KEY_SAVE_IMAGES, false)
        startForeground(NOTIFICATION_ID, createNotification("SmartDriver ativo"))
        Log.d(TAG, "onCreate: Salvar screenshots = $shouldSaveScreenshots")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: Action=${intent?.action}")

        if (intent?.hasExtra(EXTRA_RESULT_CODE) == true && initialResultCode == Activity.RESULT_CANCELED) {
            initialResultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            initialResultData = getParcelableExtraCompat(intent, EXTRA_RESULT_DATA, Intent::class.java)?.clone() as? Intent
            Log.i(TAG, "Dados projeção recebidos: Code=$initialResultCode, Data=${initialResultData != null}")
            if (initialResultCode != Activity.RESULT_CANCELED && initialResultData != null) {
                if (!isCapturingActive.get()) {
                    MediaProjectionData.resultCode = initialResultCode
                    MediaProjectionData.resultData = initialResultData?.clone() as? Intent
                    setupMediaProjection(); startScreenCaptureInternal()
                }
            } else {
                Log.e(TAG, "Dados projeção inválidos recebidos! Parando.")
                stopSelf()
            }
        }

        when (intent?.action) {
            ACTION_STOP_CAPTURE -> {
                Log.i(TAG, "Ação STOP_CAPTURE recebida.")
                stopScreenCaptureInternal() // NOTE: não limpa autorização (ver método)
                stopSelf()
            }
            ACTION_CAPTURE_NOW -> {
                if (mediaProjection != null && imageReader != null && isCapturingActive.get()) {
                    processingHandler.post { processAvailableImage(imageReader) }
                } else {
                    Log.w(TAG, "CAPTURE_NOW ignorado: Serviço não pronto ou captura inativa.")
                }
            }
            ACTION_SAVE_LAST_VALID_OFFER_SCREENSHOT -> {
                Log.i(TAG, "Pedido para salvar último screenshot válido.")
                processingHandler.post {
                    val bitmapToSave = lastBitmapForPotentialSave
                    if (bitmapToSave != null && !bitmapToSave.isRecycled) {
                        saveScreenshotToGallery(bitmapToSave, "OFERTA_VALIDA")
                        lastBitmapForPotentialSave = null
                    } else {
                        Log.w(TAG, "Sem bitmap válido pendente para salvar.")
                        lastBitmapForPotentialSave = null
                    }
                }
            }
            ACTION_UPDATE_SETTINGS -> {
                Log.d(TAG, "Ação UPDATE_SETTINGS recebida.")
                if (intent.hasExtra(KEY_SAVE_IMAGES)) {
                    val save = intent.getBooleanExtra(KEY_SAVE_IMAGES, false)
                    if (save != shouldSaveScreenshots) {
                        shouldSaveScreenshots = save
                        preferences.edit().putBoolean(KEY_SAVE_IMAGES, save).apply()
                        Log.i(TAG, "'Salvar Screenshots' atualizado para: $save")
                        if (!save) processingHandler.post { recyclePotentialSaveBitmap("configuração desligada") }
                    }
                } else {
                    Log.w(TAG,"UPDATE_SETTINGS sem extra '$KEY_SAVE_IMAGES'")
                }
            }
            ACTION_FREEZE_OCR -> {
                Log.d(TAG, "FREEZE_OCR recebida. Pausa curta no OCR.")
                freezeOcrFor(900L)
            }
            null -> {
                if (initialResultCode != Activity.RESULT_CANCELED && !isCapturingActive.get()){
                    Log.i(TAG, "Sem ação. Tentar (re)iniciar com dados existentes…")
                    if (MediaProjectionData.resultCode == Activity.RESULT_CANCELED) {
                        MediaProjectionData.resultCode = initialResultCode
                        MediaProjectionData.resultData = initialResultData?.clone() as? Intent
                    }
                    setupMediaProjection(); startScreenCaptureInternal()
                } else if (!isCapturingActive.get()) {
                    Log.w(TAG, "Sem ação e sem dados válidos para iniciar.")
                }
            }
            else -> Log.w(TAG, "Ação desconhecida: ${intent.action}")
        }
        return START_STICKY
    }

    private fun <T : Any?> getParcelableExtraCompat(intent: Intent?, key: String, clazz: Class<T>): T? {
        return intent?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableExtra(key, clazz)
            } else {
                @Suppress("DEPRECATION") it.getParcelableExtra(key) as? T
            }
        }
    }

    private fun getScreenMetrics() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            screenWidth = metrics.bounds.width()
            screenHeight = metrics.bounds.height()
        } else {
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
        }
        screenDensity = resources.configuration.densityDpi
        Log.i(TAG, "Screen Metrics: $screenWidth x $screenHeight @ $screenDensity dpi")
        if (screenWidth <= 0 || screenHeight <= 0) {
            Log.w(TAG, "Falha ao obter métricas, fallback 1080x1920.")
            screenWidth = 1080; screenHeight = 1920; screenDensity = DisplayMetrics.DENSITY_DEFAULT
        }
    }

    private fun setupMediaProjection() {
        if (mediaProjection != null) { Log.d(TAG, "MediaProjection já configurado."); return }
        val code = MediaProjectionData.resultCode
        val data = MediaProjectionData.resultData
        if (code == Activity.RESULT_CANCELED || data == null) {
            Log.e(TAG, "Sem dados de MediaProjection (code=$code). Parando serviço.")
            stopSelf(); return
        }
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            mediaProjection = projectionManager.getMediaProjection(code, data)
            if (mediaProjection == null) {
                Log.e(TAG, "getMediaProjection devolveu null. Limpando dados.")
                // CHANGED: limpeza aqui porque não conseguimos criar a projeção
                MediaProjectionData.clear()
                stopSelf()
                return
            }
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection parado externamente!")
                    // CHANGED: se o sistema parar a projeção, limpamos a autorização
                    stopScreenCaptureInternal()
                    MediaProjectionData.clear() // <- manter limpo neste caso
                    initialResultCode = Activity.RESULT_CANCELED
                    initialResultData = null
                    stopSelf()
                }
            }, mainHandler)
            Log.i(TAG, "MediaProjection configurado com sucesso.")
            setupImageReader()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao configurar MediaProjection: ${e.message}", e)
            mediaProjection = null
            MediaProjectionData.clear() // CHANGED: falhou configuração → limpa
            stopSelf()
        }
    }

    private fun setupImageReader() {
        if (imageReader != null) { Log.d(TAG, "ImageReader já configurado."); return }
        if (screenWidth <= 0 || screenHeight <= 0) { Log.e(TAG,"Dimensões inválidas."); return }
        try {
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            imageReader?.setOnImageAvailableListener({ reader -> processAvailableImage(reader) }, processingHandler)
            Log.i(TAG, "ImageReader ok: ${screenWidth}x$screenHeight RGBA_8888")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Erro ImageReader: ${e.message}", e)
            imageReader = null; stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Erro genérico ImageReader: ${e.message}", e)
            imageReader = null; stopSelf()
        }
    }

    private fun startScreenCaptureInternal() {
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection nulo. Tentar configurar…")
            setupMediaProjection()
            if(mediaProjection == null) return
        }
        if (imageReader == null) {
            Log.e(TAG, "ImageReader nulo. Tentar configurar…")
            setupImageReader()
            if(imageReader == null) return
        }
        if (isCapturingActive.get()) { Log.d(TAG, "Captura já ativa."); return }

        Log.i(TAG, "Iniciando VirtualDisplay…")
        try {
            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "ScreenCapture", screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                processingHandler
            )
            isCapturingActive.set(true)
            updateNotification("SmartDriver a monitorizar…")
            Log.i(TAG, ">>> Captura de ecrã INICIADA <<<")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException VirtualDisplay: ${e.message}", e)
            isCapturingActive.set(false); stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Erro VirtualDisplay: ${e.message}", e)
            isCapturingActive.set(false); stopSelf()
        }
    }

    private fun stopScreenCaptureInternal() {
        if (!isCapturingActive.getAndSet(false)) {
            Log.d(TAG, "Captura já inativa.")
            // CHANGED: NÃO limpar autorização aqui. Mantemos para reusar no próximo start.
            // MediaProjectionData.clear()
            // initialResultCode = Activity.RESULT_CANCELED
            // initialResultData = null
            return
        }
        Log.w(TAG, "Parando captura de ecrã…")
        try { virtualDisplay?.release() } catch (_: Exception) {} finally { virtualDisplay = null }
        try { imageReader?.close() } catch (_: Exception) {} finally { imageReader = null }
        try { mediaProjection?.stop() } catch (_: Exception) {} finally { mediaProjection = null }

        // CHANGED: NÃO limpar MediaProjectionData/initialResult aqui.
        // Mantém autorização válida para próximo arranque sem prompt.

        isProcessingImage.set(false)
        lastScreenHash = null
        processingHandler.post { recyclePotentialSaveBitmap("captura parada") }

        Log.i(TAG, ">>> Captura de ecrã PARADA <<<")
        updateNotification("SmartDriver inativo")
    }

    private fun processAvailableImage(reader: ImageReader?) {
        if (reader == null || !isCapturingActive.get()) return

        val now = System.currentTimeMillis()
        if (now - lastOfferDetectedTime < OFFER_PROCESSING_LOCK_PERIOD_MS / 2) {
            reader.acquireLatestImage()?.close(); return
        }
        if (!isOcrAllowedNow()) { reader.acquireLatestImage()?.close(); return }
        if (!isProcessingImage.compareAndSet(false, true)) {
            reader.acquireLatestImage()?.close(); return
        }

        var image: Image? = null
        var originalBitmap: Bitmap? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) { isProcessingImage.set(false); return }
            originalBitmap = imageToBitmap(image)
            image.close(); image = null

            if (originalBitmap == null) {
                Log.e(TAG, "Falha a converter Image->Bitmap.")
                isProcessingImage.set(false); return
            }

            val hash = calculateImageHash(originalBitmap)
            val tHash = System.currentTimeMillis()
            if (tHash - lastHashTime < HASH_CACHE_DURATION_MS && hash == lastScreenHash) {
                originalBitmap.recycle(); originalBitmap = null
                isProcessingImage.set(false); return
            }
            lastScreenHash = hash; lastHashTime = tHash

            val roi = imageAnalysisUtils.getRegionsOfInterest(originalBitmap.width, originalBitmap.height).firstOrNull()
            var bitmapToAnalyze: Bitmap? = null
            var regionTag = "UNKNOWN_ROI"

            if (roi != null && !roi.isEmpty && roi.width() > 0 && roi.height() > 0) {
                bitmapToAnalyze = imageAnalysisUtils.cropToRegion(originalBitmap, roi)
                if (bitmapToAnalyze != null) {
                    if (!originalBitmap.isRecycled) originalBitmap.recycle(); originalBitmap = null
                    regionTag = "ROI_${roi.top}_${roi.height()}"
                } else {
                    Log.e(TAG, "Falha recorte ROI. Ecrã inteiro.")
                    bitmapToAnalyze = originalBitmap; originalBitmap = null
                    regionTag = "FULL_SCREEN_CROP_FAIL"
                }
            } else {
                Log.w(TAG, "ROI inválida. Ecrã inteiro.")
                bitmapToAnalyze = originalBitmap; originalBitmap = null
                regionTag = "FULL_SCREEN_ROI_FAIL"
            }

            if (bitmapToAnalyze != null && !bitmapToAnalyze.isRecycled) {
                processBitmapRegion(bitmapToAnalyze, screenWidth, screenHeight, regionTag)
            } else {
                Log.e(TAG,"Bitmap final nulo/reciclado.")
                originalBitmap?.recycle()
                isProcessingImage.set(false)
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "IllegalState em processAvailableImage: ${e.message}")
            originalBitmap?.recycle(); image?.close(); isProcessingImage.set(false)
        } catch (e: Exception) {
            Log.e(TAG, "Erro geral em processAvailableImage: ${e.message}", e)
            originalBitmap?.recycle(); image?.close(); isProcessingImage.set(false)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        if (image.format != PixelFormat.RGBA_8888) {
            Log.w(TAG,"Formato inesperado: ${image.format}. Esperado RGBA_8888.")
            return null
        }
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        return try {
            val bmpPad = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
            bmpPad.copyPixelsFromBuffer(buffer)
            if (rowPadding == 0) bmpPad
            else {
                val finalBmp = Bitmap.createBitmap(bmpPad, 0, 0, image.width, image.height)
                bmpPad.recycle(); finalBmp
            }
        } catch (e: Exception) {
            Log.e(TAG,"Erro Image->Bitmap: ${e.message}", e); null
        }
    }

    private fun calculateImageHash(bitmap: Bitmap): Int {
        val scaleSize = 64
        return try {
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaleSize, scaleSize, true)
            val pixels = IntArray(scaleSize * scaleSize)
            scaledBitmap.getPixels(pixels, 0, scaleSize, 0, 0, scaleSize, scaleSize)
            scaledBitmap.recycle()
            pixels.contentHashCode()
        } catch (e: Exception) {
            Log.w(TAG,"Erro hash: ${e.message}")
            bitmap.hashCode()
        }
    }

    private fun preprocessBitmapForOcr(originalBitmap: Bitmap?): Bitmap? {
        if (originalBitmap == null || originalBitmap.isRecycled) {
            Log.w(TAG, "Bitmap original nulo/reciclado.")
            return originalBitmap
        }
        var processedBitmap: Bitmap? = null
        return try {
            processedBitmap = Bitmap.createBitmap(originalBitmap.width, originalBitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(processedBitmap)
            val paint = Paint()
            val contrastValue = 1.5f
            val brightnessValue = -(128 * (contrastValue - 1))
            val colorMatrix = ColorMatrix(floatArrayOf(
                contrastValue, 0f, 0f, 0f, brightnessValue,
                0f, contrastValue, 0f, 0f, brightnessValue,
                0f, 0f, contrastValue, 0f, brightnessValue,
                0f, 0f, 0f, 1f, 0f
            ))
            paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
            canvas.drawBitmap(originalBitmap, 0f, 0f, paint)
            Log.d(TAG, "Pré-processamento contraste aplicado.")
            processedBitmap
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM pré-processamento!", e); processedBitmap?.recycle(); null
        } catch (e: Exception) {
            Log.e(TAG, "Erro pré-processamento: ${e.message}", e)
            processedBitmap?.recycle(); originalBitmap
        }
    }

    private fun processBitmapRegion(bitmapToProcess: Bitmap, originalWidth: Int, originalHeight: Int, regionTag: String) {
        var bitmapAfterResize: Bitmap? = null
        var bitmapPreprocessed: Bitmap? = null
        var bitmapCopyForListeners: Bitmap? = null
        val timeStart = System.currentTimeMillis()

        try {
            val scaleFactor = OCR_IMAGE_SCALE_FACTOR
            bitmapAfterResize = if (abs(scaleFactor - 1.0f) > 0.01f) {
                val newWidth = (bitmapToProcess.width * scaleFactor).toInt()
                val newHeight = (bitmapToProcess.height * scaleFactor).toInt()
                if (newWidth > 0 && newHeight > 0) {
                    Bitmap.createScaledBitmap(bitmapToProcess, newWidth, newHeight, true).also {
                        Log.v(TAG, "Redimensionado para OCR: ${newWidth}x${newHeight} ($regionTag)")
                    }
                } else bitmapToProcess
            } else bitmapToProcess

            if (bitmapAfterResize == null || bitmapAfterResize.isRecycled) {
                Log.e(TAG, "Bitmap pós-resize nulo/reciclado ($regionTag).")
                if (bitmapToProcess != bitmapAfterResize && bitmapToProcess.isRecycled.not()) bitmapToProcess.recycle()
                isProcessingImage.compareAndSet(true, false); return
            }

            bitmapPreprocessed = preprocessBitmapForOcr(bitmapAfterResize)
            if (bitmapPreprocessed == null || bitmapPreprocessed.isRecycled) {
                Log.e(TAG, "Bitmap OCR nulo/reciclado ($regionTag).")
                if (bitmapAfterResize != bitmapPreprocessed && bitmapAfterResize.isRecycled.not()) bitmapAfterResize.recycle()
                if (bitmapToProcess != bitmapAfterResize && bitmapToProcess.isRecycled.not()) bitmapToProcess.recycle()
                isProcessingImage.compareAndSet(true, false); return
            }

            try {
                bitmapCopyForListeners = bitmapPreprocessed.copy(bitmapPreprocessed.config ?: Bitmap.Config.ARGB_8888, false)
            } catch (e: Exception) {
                Log.e(TAG, "Erro a copiar bitmap para OCR ($regionTag): ${e.message}", e)
                bitmapCopyForListeners = null
            }

            if (bitmapPreprocessed != bitmapAfterResize && bitmapAfterResize.isRecycled.not()) bitmapAfterResize.recycle()
            if (bitmapPreprocessed != bitmapToProcess && bitmapToProcess.isRecycled.not()) bitmapToProcess.recycle()
            if (bitmapCopyForListeners != bitmapPreprocessed && bitmapPreprocessed.isRecycled.not()) bitmapPreprocessed.recycle()

            if (bitmapCopyForListeners == null || bitmapCopyForListeners.isRecycled) {
                Log.e(TAG, "Bitmap para OCR falhou ($regionTag).")
                isProcessingImage.compareAndSet(true, false)
                recyclePotentialSaveBitmap("falha na cópia para listener")
                return
            }

            try {
                ocr.processThrottled(bitmapCopyForListeners, 0, 350)
                    .addOnSuccessListener listener@{ visionText ->
                        val extractedText = visionText.text
                        Log.d(TAG,"OCR '$regionTag' OK (len=${extractedText.length})")

                        processingHandler.post { recyclePotentialSaveBitmap("novo resultado OCR") }
                        if (extractedText.isBlank()) { Log.v(TAG, "Texto OCR vazio '$regionTag'."); return@listener }

                        val offerData = imageAnalysisUtils.analyzeTextForOffer(visionText, bitmapCopyForListeners.width, bitmapCopyForListeners.height)
                        if (offerData == null || !offerData.isValid()) { Log.v(TAG,"'$regionTag': não é oferta válida."); return@listener }

                        val stable = stability.add(offerData)
                        if (stable == null) { Log.d(TAG, "Janela: sem consenso (mais amostras)."); return@listener }

                        when (val verdict = classify(stable)) {
                            Verdict.INVALID -> {
                                Log.d(TAG, "Rejeitado: inválido ($verdict).")
                                return@listener
                            }
                            Verdict.SUSPECT -> {
                                val sig = createOfferSignature(stable)
                                val now = System.currentTimeMillis()
                                val canRecapture = (sig != lastSuspectSignature) || (now - lastSuspectAt > RECAPTURE_COOLDOWN_MS)
                                if (canRecapture) {
                                    lastSuspectSignature = sig
                                    lastSuspectAt = now
                                    stability.clear()
                                    Log.i(TAG, "SUSPEITO -> recaptura curta em ${RECAPTURE_DELAY_MS}ms ($regionTag)")
                                    processingHandler.postDelayed({
                                        imageReader?.let { rdr -> processAvailableImage(rdr) }
                                    }, RECAPTURE_DELAY_MS)
                                } else {
                                    Log.d(TAG, "SUSPEITO repetido recente — ignorado.")
                                }
                                return@listener
                            }
                            Verdict.VALID -> {
                                val offerSignature = createOfferSignature(stable)
                                val timeSinceLastOfferProc = System.currentTimeMillis() - lastOfferDetectedTime
                                if (timeSinceLastOfferProc < OFFER_PROCESSING_LOCK_PERIOD_MS && offerSignature == lastDetectedOfferSignature) {
                                    Log.d(TAG, "Ignorado: repetido muito rápido.")
                                    return@listener
                                }
                                lastOfferDetectedTime = System.currentTimeMillis()
                                lastDetectedOfferSignature = offerSignature

                                if (shouldSaveScreenshots) {
                                    try {
                                        lastBitmapForPotentialSave = bitmapCopyForListeners.copy(bitmapCopyForListeners.config ?: Bitmap.Config.ARGB_8888, false)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Erro a copiar bitmap para salvar: ${e.message}", e)
                                        lastBitmapForPotentialSave = null
                                    }
                                } else {
                                    processingHandler.post { recyclePotentialSaveBitmap("save desligado") }
                                }

                                Log.i(TAG, "OFERTA OK ($regionTag) [$offerSignature] -> OfferManager")
                                mainHandler.post {
                                    OfferManager.getInstance(applicationContext).processOffer(stable)
                                }
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Falha OCR ($regionTag): ${e.message}")
                        processingHandler.post { recyclePotentialSaveBitmap("falha no OCR") }
                    }
                    .addOnCompleteListener {
                        if (bitmapCopyForListeners?.isRecycled == false) bitmapCopyForListeners.recycle()
                        isProcessingImage.compareAndSet(true, false)
                        val duration = System.currentTimeMillis() - timeStart
                        Log.d(TAG,"Processo '$regionTag' em ${duration}ms.")
                    }
            } catch (throttle: IllegalStateException) {
                Log.v(TAG, "OCR throttled '$regionTag': ${throttle.message}")
                if (bitmapCopyForListeners?.isRecycled == false) bitmapCopyForListeners.recycle()
                processingHandler.post { recyclePotentialSaveBitmap("ocr throttled") }
                isProcessingImage.compareAndSet(true, false)
                return
            }
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "OOM em processBitmapRegion ($regionTag)!", oom)
            bitmapCopyForListeners?.recycle(); bitmapPreprocessed?.recycle(); bitmapAfterResize?.recycle(); bitmapToProcess.recycle()
            processingHandler.post { recyclePotentialSaveBitmap("OOM") }
            isProcessingImage.compareAndSet(true, false)
        } catch (e: Exception) {
            Log.e(TAG, "Erro geral em processBitmapRegion ($regionTag): ${e.message}", e)
            bitmapCopyForListeners?.recycle(); bitmapPreprocessed?.recycle(); bitmapAfterResize?.recycle(); bitmapToProcess.recycle()
            processingHandler.post { recyclePotentialSaveBitmap("exceção geral") }
            isProcessingImage.compareAndSet(true, false)
        }
    }

    private fun createOfferSignature(offerData: OfferData): String {
        val v = offerData.value.replace(",",".")
        val pd = offerData.pickupDistance.replace(",", ".").toDoubleOrNull()?.let { String.format(Locale.US, "%.2f", it) } ?: "0"
        val td = offerData.tripDistance.replace(",", ".").toDoubleOrNull()?.let { String.format(Locale.US, "%.2f", it) } ?: "0"
        val pt = offerData.pickupDuration.toIntOrNull()?.toString() ?: "0"
        val tt = offerData.tripDuration.toIntOrNull()?.toString() ?: "0"
        return "v:$v|pd:$pd|td:$td|pt:$pt|tt:$tt"
    }

    private fun recyclePotentialSaveBitmap(reason: String = "") {
        val b = lastBitmapForPotentialSave
        if (b != null && !b.isRecycled) {
            Log.d(TAG, "Reciclando bitmap pendente. Razão: [$reason]")
            b.recycle()
        }
        lastBitmapForPotentialSave = null
    }

    private fun saveScreenshotToGallery(bitmapToSave: Bitmap?, prefix: String) {
        if (bitmapToSave == null || bitmapToSave.isRecycled) {
            Log.w(TAG, "Bitmap nulo/reciclado para saveScreenshotToGallery ($prefix).")
            return
        }

        val timeStamp = SimpleDateFormat("yyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
        val fileName = "SmartDriver_${prefix}_${timeStamp}_${screenshotCounter++}.jpg"
        Log.d(TAG, "Salvar: $fileName")

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SmartDriverDebug")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        var success = false

        if (imageUri != null) {
            try {
                contentResolver.openOutputStream(imageUri)?.use { outputStream ->
                    bitmapToSave.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                    success = true
                    Log.i(TAG, "Screenshot '$fileName' salvo (URI: $imageUri)")
                } ?: Log.e(TAG, "Falha a abrir OutputStream: $imageUri")
            } catch (e: IOException) {
                Log.e(TAG, "IO ao salvar '$fileName': ${e.message}", e)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao salvar '$fileName': ${e.message}", e)
            } finally {
                if (!success) {
                    try { contentResolver.delete(imageUri, null, null) } catch (_: Exception) {}
                }
            }
        } else {
            Log.e(TAG, "Falha a criar URI no MediaStore para '$fileName'.")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && imageUri != null && success) {
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            try { contentResolver.update(imageUri, contentValues, null, null) } catch (_: Exception) {}
        }

        if (!bitmapToSave.isRecycled) {
            bitmapToSave.recycle()
            Log.v(TAG,"Bitmap ($prefix) reciclado no final do save.")
        }
    }

    private fun createNotification(contentText: String): Notification {
        createNotificationChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SmartDriver")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_smartdriver)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            notificationManager.notify(NOTIFICATION_ID, createNotification(contentText))
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao atualizar notificação: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificação persistente do serviço de captura SmartDriver"
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            try { notificationManager.createNotificationChannel(channel) } catch (e: Exception) {
                Log.e(TAG, "Erro ao criar canal: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, ">>> Serviço de Captura DESTRUÍDO <<<")
        isRunning.set(false)

        if (::processingThread.isInitialized && processingThread.isAlive) {
            processingHandler.post { recyclePotentialSaveBitmap("onDestroy") }
        } else {
            recyclePotentialSaveBitmap("onDestroy - thread inativa")
        }

        stopScreenCaptureInternal()

        // CHANGED: aqui sim, limpeza total ao encerrar o serviço
        MediaProjectionData.clear()
        initialResultCode = Activity.RESULT_CANCELED
        initialResultData = null

        if (::processingThread.isInitialized && processingThread.isAlive){
            try {
                processingThread.quitSafely()
                processingThread.join(500)
                Log.d(TAG,"ProcessingThread finalizada.")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao finalizar processingThread: ${e.message}", e)
            }
        }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception){
            Log.e(TAG, "Erro ao remover notificação foreground: ${e.message}")
        }
    }
}
