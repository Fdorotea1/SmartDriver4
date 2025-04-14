package com.example.smartdriver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
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
import android.os.Process // << IMPORT NECESSÁRIO para prioridade da thread
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.smartdriver.utils.ImageAnalysisUtils
import com.example.smartdriver.utils.OfferData // Import necessário
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * Serviço de Foreground que gerencia a captura de tela usando MediaProjection,
 * redimensiona a imagem, processa com OCR (ML Kit) e envia os dados da oferta para o OfferManager.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val CHANNEL_NAME = "Screen Capture Service"

        const val ACTION_START_CAPTURE = "com.example.smartdriver.screen_capture.START"
        const val ACTION_STOP_CAPTURE = "com.example.smartdriver.screen_capture.STOP"
        const val ACTION_CAPTURE_NOW = "com.example.smartdriver.screen_capture.CAPTURE_NOW"
        const val ACTION_UPDATE_SETTINGS = "com.example.smartdriver.screen_capture.UPDATE_SETTINGS"

        const val PREFS_NAME = "SmartDriverPrefs"
        const val KEY_SAVE_IMAGES = "save_images"

        var mediaProjectionData: MediaProjectionData? = null

        // Fator de escala para redimensionar imagem antes do OCR (0.75 = 75%)
        // Ajustar este valor pode equilibrar performance vs precisão do OCR
        private const val OCR_IMAGE_SCALE_FACTOR = 0.75f
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private val isCapturingActive = AtomicBoolean(false)
    private val isProcessingImage = AtomicBoolean(false)

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var processingThread: HandlerThread
    private lateinit var processingHandler: Handler

    private lateinit var preferences: SharedPreferences
    private var shouldSaveScreenshots = false

    private val imageAnalysisUtils = ImageAnalysisUtils()
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var lastOfferDetectedTime = 0L
    private val offerProcessingLockPeriodMs = 4000L
    private var lastDetectedOfferSignature: String? = null

    private var lastScreenHash: Int? = null
    private var lastHashTime = 0L
    private val hashCacheDurationMs = 1000L

    private var screenshotCounter = 0

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Serviço de Captura CRIADO")
        processingThread = HandlerThread("ImageProcessingThread", Process.THREAD_PRIORITY_BACKGROUND).apply { // Define prioridade aqui
            start()
            // Log.d(TAG, "Prioridade da processingThread definida para BACKGROUND.") // Log opcional
        }
        processingHandler = Handler(processingThread.looper)

        getScreenMetrics()
        preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        shouldSaveScreenshots = preferences.getBoolean(KEY_SAVE_IMAGES, false)
        Log.d(TAG, "Configuração inicial: Salvar screenshots = $shouldSaveScreenshots")
        startForeground(NOTIFICATION_ID, createNotification("SmartDriver ativo"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val timeStartCommand = System.currentTimeMillis()
        Log.d(TAG, "[TIME] onStartCommand recebido com ação: ${intent?.action} at $timeStartCommand")

        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                if (!isCapturingActive.get()) {
                    setupMediaProjection()
                    startScreenCaptureInternal()
                } else { Log.d(TAG, "Comando START ignorado: Captura já está ativa.") }
            }
            ACTION_STOP_CAPTURE -> {
                stopScreenCaptureInternal()
                stopSelf()
            }
            ACTION_CAPTURE_NOW -> {
                Log.d(TAG, "[TIME] ACTION_CAPTURE_NOW recebido.")
                if (mediaProjection != null && imageReader != null && isCapturingActive.get()) {
                    captureAndProcessScreenshot()
                } else {
                    Log.w(TAG, "Comando CAPTURE_NOW ignorado: Serviço não pronto ou inativo (MP: ${mediaProjection!=null}, IR: ${imageReader!=null}, Active: ${isCapturingActive.get()})")
                    if (!isCapturingActive.get()) {
                        Log.d(TAG,"Tentando reiniciar captura após CAPTURE_NOW falhar...")
                        setupMediaProjection(); startScreenCaptureInternal()
                    }
                }
            }
            ACTION_UPDATE_SETTINGS -> {
                val saveImages = intent.getBooleanExtra(KEY_SAVE_IMAGES, false)
                if (saveImages != shouldSaveScreenshots) {
                    shouldSaveScreenshots = saveImages
                    preferences.edit().putBoolean(KEY_SAVE_IMAGES, shouldSaveScreenshots).apply()
                    Log.i(TAG, "Configuração atualizada: Salvar screenshots = $shouldSaveScreenshots")
                }
            }
            else -> { Log.w(TAG, "Ação desconhecida ou nula no Intent: ${intent?.action}") }
        }
        return START_STICKY
    }

    private fun getScreenMetrics() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            screenWidth = metrics.bounds.width(); screenHeight = metrics.bounds.height()
            screenDensity = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION") val display = windowManager.defaultDisplay
            @Suppress("DEPRECATION") display.getMetrics(displayMetrics)
            screenWidth = displayMetrics.widthPixels; screenHeight = displayMetrics.heightPixels
            screenDensity = displayMetrics.densityDpi
        }
        Log.i(TAG, "Screen Metrics: $screenWidth x $screenHeight @ $screenDensity dpi")
        if (screenWidth <= 0 || screenHeight <= 0) {
            Log.e(TAG, "Métricas de tela inválidas! Usando fallback (1080x1920).")
            screenWidth = 1080; screenHeight = 1920; screenDensity = DisplayMetrics.DENSITY_DEFAULT
        }
    }

    private fun setupMediaProjection() {
        if (mediaProjection != null) { Log.d(TAG, "MediaProjection já está configurado."); return }
        val data = mediaProjectionData
        if (data == null) { Log.e(TAG, "Falha ao configurar MediaProjection: Dados não disponíveis."); stopSelf(); return }
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            mediaProjection = projectionManager.getMediaProjection(data.resultCode, data.data)
            if (mediaProjection == null) { Log.e(TAG, "Falha ao obter MediaProjection."); stopSelf(); return }
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { Log.w(TAG, "MediaProjection PARADO."); stopScreenCaptureInternal(); stopSelf() }
            }, mainHandler)
            Log.i(TAG, "Media Projection configurada com SUCESSO.")
            setupImageReader()
        } catch (e: Exception) { Log.e(TAG, "Erro EXCEPCIONAL ao configurar Media Projection: ${e.message}"); mediaProjection = null; stopSelf() }
    }

    private fun setupImageReader() {
        if (imageReader != null) { Log.d(TAG, "ImageReader já configurado."); return }
        if (screenWidth <= 0 || screenHeight <= 0) { Log.e(TAG,"Não é possível configurar ImageReader: Dimensões inválidas."); return }
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        Log.d(TAG, "ImageReader configurado: ${screenWidth}x$screenHeight, Format=RGBA_8888, MaxImages=2")
        imageReader?.setOnImageAvailableListener({ reader -> processingHandler.post { processAvailableImage(reader) } }, processingHandler)
    }

    private fun startScreenCaptureInternal() {
        if (mediaProjection == null) { Log.e(TAG, "Falha ao iniciar captura: MP nulo."); setupMediaProjection(); if(mediaProjection == null) return }
        if (imageReader == null) { Log.e(TAG, "Falha ao iniciar captura: IR nulo."); setupImageReader(); if(imageReader == null) return }
        if (isCapturingActive.get()) { Log.d(TAG, "Captura já está ativa."); return }
        Log.i(TAG, "Iniciando VirtualDisplay...")
        try {
            virtualDisplay = mediaProjection!!.createVirtualDisplay("ScreenCapture", screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, processingHandler)
            isCapturingActive.set(true); updateNotification("SmartDriver monitorando..."); Log.i(TAG, "Captura INICIADA.")
        } catch (e: SecurityException) { Log.e(TAG, "Erro de segurança VD: ${e.message}"); isCapturingActive.set(false); stopSelf() }
        catch (e: Exception) { isCapturingActive.set(false); Log.e(TAG, "Erro ao criar VD: ${e.message}") }
    }

    private fun stopScreenCaptureInternal() {
        if (!isCapturingActive.getAndSet(false)) { Log.d(TAG, "Captura já estava inativa."); return }
        Log.w(TAG, "Parando captura e liberando recursos...")
        try { virtualDisplay?.release() } catch (e: Exception) { Log.e(TAG, "Erro release VD: ${e.message}") } finally { virtualDisplay = null }
        try { imageReader?.close() } catch (e: Exception) { Log.e(TAG, "Erro close IR: ${e.message}") } finally { imageReader = null }
        try { mediaProjection?.stop() } catch (e: Exception) { Log.e(TAG, "Erro stop MP: ${e.message}") } finally { mediaProjection = null }
        mediaProjectionData = null; isProcessingImage.set(false); lastScreenHash = null
        Log.i(TAG, "Captura PARADA."); updateNotification("SmartDriver inativo")
    }

    private fun captureAndProcessScreenshot() {
        val timeStartCapture = System.currentTimeMillis()
        Log.d(TAG, "[TIME] captureAndProcessScreenshot solicitado at $timeStartCapture")
        if (isProcessingImage.get()) { Log.d(TAG, "CAPTURE_NOW ignorado: Processamento em andamento."); return }
        processingHandler.post {
            val timeProcessingStart = System.currentTimeMillis()
            Log.d(TAG, "[TIME] processAvailableImage (background thread) iniciado at $timeProcessingStart (Atraso solicitação: ${timeProcessingStart-timeStartCapture}ms)")
            processAvailableImage(imageReader)
        }
    }

    private fun processAvailableImage(reader: ImageReader?) {
        if (reader == null) { Log.w(TAG, "ImageReader nulo."); return }
        if (!isProcessingImage.compareAndSet(false, true)) { Log.d(TAG, "Processamento pulado, outro em andamento."); return }

        var image: Image? = null
        var originalBitmap: Bitmap? = null // Renomeado para clareza
        val processingStartTime = System.currentTimeMillis()

        try {
            val timeAcquireStart = System.currentTimeMillis()
            image = reader.acquireLatestImage()
            val timeAcquired = System.currentTimeMillis()
            if(image != null) { Log.d(TAG, "[TIME] acquireLatestImage demorou ${timeAcquired - timeAcquireStart}ms") }
            else { Log.w(TAG, "acquireLatestImage retornou null."); isProcessingImage.set(false); return }

            val timeBitmapStart = System.currentTimeMillis()
            originalBitmap = imageToBitmap(image) // Converte para o bitmap original
            val timeBitmapEnd = System.currentTimeMillis()
            if(originalBitmap != null) { Log.d(TAG, "[TIME] imageToBitmap demorou ${timeBitmapEnd - timeBitmapStart}ms") }
            else { Log.e(TAG, "Falha ao converter Image para Bitmap."); isProcessingImage.set(false); return }

            val currentTime = System.currentTimeMillis()
            val currentHash = calculateImageHash(originalBitmap)
            if (currentTime - lastHashTime > hashCacheDurationMs) { lastScreenHash = null }
            if (currentHash == lastScreenHash) {
                Log.d(TAG, "Imagem idêntica (hash match). Pulando OCR.");
                originalBitmap.recycle(); isProcessingImage.set(false); return
            }
            lastScreenHash = currentHash; lastHashTime = currentTime
            Log.d(TAG, "Nova imagem detectada (hash: $currentHash).")

            if (shouldSaveScreenshots) { saveScreenshotToGallery(originalBitmap.copy(originalBitmap.config, true), "SCREEN") }

            val timeCropStart = System.currentTimeMillis()
            val roi = imageAnalysisUtils.getRegionsOfInterest(originalBitmap.width, originalBitmap.height).firstOrNull()
            var bitmapToAnalyze: Bitmap? = null // Bitmap que será enviado para análise (pode ser o original ou o recortado)
            var regionTag = "UNKNOWN"

            if (roi == null || roi.isEmpty) {
                Log.e(TAG, "Região inválida. Usando tela inteira."); bitmapToAnalyze = originalBitmap; regionTag = "FULL_ROI_FAIL"
            } else {
                bitmapToAnalyze = imageAnalysisUtils.cropToRegion(originalBitmap, roi)
                val timeCropEnd = System.currentTimeMillis(); Log.d(TAG, "[TIME] cropToRegion demorou ${timeCropEnd - timeCropStart}ms")
                if (bitmapToAnalyze != null) {
                    Log.d(TAG, "Processando região: ${roi.flattenToString()}");
                    originalBitmap.recycle() // Recicla o original pois temos o recortado
                    regionTag = "ROI"
                } else {
                    Log.e(TAG, "Falha ao recortar. Usando tela inteira."); bitmapToAnalyze = originalBitmap; regionTag = "FULL_CROP_FAIL"
                }
            }

            if (bitmapToAnalyze != null) {
                processBitmapRegion(bitmapToAnalyze, screenWidth, screenHeight, regionTag) // Passa o bitmap correto
            } else {
                Log.e(TAG,"bitmapToAnalyze ficou nulo."); isProcessingImage.set(false)
                // Se bitmapToAnalyze é nulo, mas originalBitmap não, recicla o original
                if (originalBitmap != null && !originalBitmap.isRecycled) { originalBitmap.recycle() }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Erro durante processamento da imagem: ${e.message}", e); isProcessingImage.set(false)
            if (originalBitmap != null && !originalBitmap.isRecycled) { originalBitmap.recycle() } // Garante recycle em caso de erro
        } finally {
            image?.close() // Fecha o Image do ImageReader
            val processingTotalTime = System.currentTimeMillis() - processingStartTime
            Log.d(TAG,"[TIME] processAvailableImage (total no background) demorou ${processingTotalTime}ms")
            // A flag isProcessingImage é resetada dentro do completeListener do OCR
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        // ... (código inalterado) ...
        if (image.format != PixelFormat.RGBA_8888) {
            Log.e(TAG, "Formato de imagem inesperado: ${image.format}.")
            return null
        }
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width
        try {
            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            if (rowPadding == 0) { return bitmap }
            else { val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height); bitmap.recycle(); return cropped }
        } catch (e: Exception) { Log.e(TAG, "Erro imageToBitmap: ${e.message}"); return null }
    }

    private fun calculateImageHash(bitmap: Bitmap): Int {
        // ... (código inalterado) ...
        val sampleSize = 64
        try {
            val scaled = Bitmap.createScaledBitmap(bitmap, sampleSize, sampleSize, true)
            val pixels = IntArray(sampleSize * sampleSize)
            scaled.getPixels(pixels, 0, sampleSize, 0, 0, sampleSize, sampleSize)
            scaled.recycle(); return pixels.contentHashCode()
        } catch (e: Exception) { Log.e(TAG, "Erro calculateImageHash: ${e.message}"); return bitmap.hashCode() }
    }

    /** Executa redimensionamento, OCR e análise no bitmap fornecido */
    private fun processBitmapRegion(bitmapToProcess: Bitmap, originalWidth: Int, originalHeight: Int, regionTag: String) {
        var bitmapForOcr: Bitmap? = null // O bitmap que realmente vai para o OCR (pode ser o original ou redimensionado)
        val scaleFactor = OCR_IMAGE_SCALE_FACTOR
        val newWidth = (bitmapToProcess.width * scaleFactor).toInt()
        val newHeight = (bitmapToProcess.height * scaleFactor).toInt()
        val timeResizeStart = System.currentTimeMillis()

        try {
            // Tenta redimensionar
            if (newWidth > 0 && newHeight > 0) {
                bitmapForOcr = Bitmap.createScaledBitmap(bitmapToProcess, newWidth, newHeight, true)
                val timeResizeEnd = System.currentTimeMillis()
                Log.d(TAG, "[TIME] Redimensionamento para ${newWidth}x$newHeight demorou ${timeResizeEnd - timeResizeStart}ms")
                // IMPORTANTE: Recicla o bitmap MAIOR (bitmapToProcess) que veio da captura/recorte
                bitmapToProcess.recycle()
                Log.d(TAG, "Bitmap original ($regionTag) reciclado após redimensionamento.")
            } else {
                Log.w(TAG, "Dimensões redimensionadas inválidas, usando bitmap original para OCR.")
                bitmapForOcr = bitmapToProcess // Usa o original se o redimensionamento falhar
            }

            // Continua com o OCR no bitmapForOcr (que é o redimensionado ou o original)
            if (bitmapForOcr == null) {
                Log.e(TAG, "bitmapForOcr é nulo, não é possível executar OCR.")
                isProcessingImage.set(false); return // Libera o lock e sai
            }

            val timeOcrStart = System.currentTimeMillis()
            Log.d(TAG, "[TIME] Iniciando OCR ($regionTag - ${bitmapForOcr.width}x${bitmapForOcr.height}) at $timeOcrStart")
            val inputImage = InputImage.fromBitmap(bitmapForOcr, 0)

            // Variável final para usar dentro dos listeners
            val finalBitmapForOcr = bitmapForOcr

            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val timeOcrEnd = System.currentTimeMillis()
                    Log.i(TAG, "[TIME] OCR ($regionTag) SUCESSO. Demorou ${timeOcrEnd - timeOcrStart}ms")
                    val extractedText = visionText.text
                    if (extractedText.isNotBlank()) {
                        Log.i(TAG, "Texto extraído ($regionTag - Redimensionado):\n--- INÍCIO ---\n$extractedText\n--- FIM ---")
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastOfferDetectedTime < offerProcessingLockPeriodMs) {
                            Log.d(TAG, "Processamento bloqueado: Oferta detectada há ${currentTime - lastOfferDetectedTime}ms.")
                        } else {
                            val timeAnalysisStart = System.currentTimeMillis()
                            val offerData = imageAnalysisUtils.analyzeTextForOffer(extractedText)
                            val timeAnalysisEnd = System.currentTimeMillis()
                            Log.d(TAG, "[TIME] Análise do texto demorou ${timeAnalysisEnd-timeAnalysisStart}ms")

                            if (offerData != null && offerData.isValid()) {
                                val offerSignature = createOfferSignature(offerData)
                                if (offerSignature == lastDetectedOfferSignature && currentTime - lastOfferDetectedTime < offerProcessingLockPeriodMs * 2) {
                                    Log.d(TAG, "Oferta ignorada: Assinatura idêntica.")
                                } else {
                                    Log.i(TAG, "!!!! OFERTA VÁLIDA DETECTADA ($regionTag) !!!!")
                                    Log.i(TAG, "   Valor: ${offerData.value}€, Dist: ${offerData.distance}km, Tempo: ${offerData.duration}min")
                                    lastOfferDetectedTime = currentTime; lastDetectedOfferSignature = offerSignature
                                    val timeOfferManagerStart = System.currentTimeMillis()
                                    mainHandler.post {
                                        Log.d(TAG, "[TIME] Enviando para OfferManager at $timeOfferManagerStart")
                                        OfferManager.getInstance(applicationContext).processOffer(offerData)
                                    }
                                    if (shouldSaveScreenshots) {
                                        // Salva a versão REDIMENSIONADA que gerou o OCR (para debug do OCR)
                                        saveScreenshotToGallery(finalBitmapForOcr.copy(finalBitmapForOcr.config, false), "OFERTA_${regionTag}_OCRInput")
                                    }
                                }
                            } else { Log.d(TAG, "Nenhuma oferta válida encontrada no texto ($regionTag).") }
                        }
                    } else { Log.d(TAG, "Nenhum texto extraído da região ($regionTag).") }
                }
                .addOnFailureListener { e ->
                    val timeOcrFail = System.currentTimeMillis()
                    Log.e(TAG, "[TIME] Falha no OCR ($regionTag) após ${timeOcrFail - timeOcrStart}ms: ${e.message}")
                }
                .addOnCompleteListener {
                    // Garante o recycle do bitmap que foi para o OCR e libera o lock
                    if (!finalBitmapForOcr.isRecycled) {
                        finalBitmapForOcr.recycle()
                        Log.d(TAG,"BitmapForOcr ($regionTag) reciclado no completeListener.")
                    }
                    isProcessingImage.compareAndSet(true, false) // Libera o lock
                    Log.d(TAG,"[TIME] Processamento OCR ($regionTag) concluído (sucesso ou falha).")
                }

        } catch (e: Exception) {
            Log.e(TAG, "Erro durante redimensionamento ou setup OCR: ${e.message}")
            // Tenta reciclar ambos os bitmaps em caso de erro
            if (bitmapForOcr != null && !bitmapForOcr.isRecycled) bitmapForOcr.recycle()
            if (bitmapToProcess != null && !bitmapToProcess.isRecycled && bitmapToProcess != bitmapForOcr) bitmapToProcess.recycle()
            isProcessingImage.set(false) // Libera o lock
        }
    }

    private fun createOfferSignature(offerData: OfferData): String {
        // ... (código inalterado) ...
        val value = offerData.value.replace(",",".")
        val pDist = offerData.pickupDistance.ifBlank { "0" }
        val tDist = offerData.tripDistance.ifBlank { "0" }
        val pDur = offerData.pickupDuration.ifBlank { "0" }
        val tDur = offerData.tripDuration.ifBlank { "0" }
        return "v:$value|pd:$pDist|td:$tDist|pt:$pDur|tt:$tDur"
    }

    private fun saveScreenshotToGallery(bitmap: Bitmap, prefix: String) {
        // ... (código inalterado) ...
        if (!shouldSaveScreenshots) { bitmap.recycle(); return }
        processingHandler.post {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
                val filename = "SmartDriver_${prefix}_${timestamp}_${screenshotCounter++}.jpg"
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis()/1000); put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SmartDriverDebug"); put(MediaStore.Images.Media.IS_PENDING, 1)
                    } else { Log.w(TAG,"Salvamento < Q não suportado."); bitmap.recycle(); return@post }
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri == null) { Log.e(TAG, "Falha URI save."); bitmap.recycle(); return@post }
                contentResolver.openOutputStream(uri)?.use { it.run { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, this); Log.d(TAG, "Imagem salva: $filename") } }
                    ?: Log.e(TAG, "Falha OutputStream: $uri")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear(); contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    try { contentResolver.update(uri, contentValues, null, null) } catch (e: Exception) { Log.e(TAG, "Erro IS_PENDING 0: ${e.message}") }
                }
            } catch (e: Exception) { Log.e(TAG, "Erro saveScreenshot: ${e.message}", e)
            } finally { if (!bitmap.isRecycled) bitmap.recycle() } // Garante recycle aqui
        }
    }

    private fun createNotification(contentText: String): Notification {
        // ... (código inalterado) ...
        createNotificationChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SmartDriver").setContentText(contentText).setSmallIcon(R.drawable.ic_stat_name)
            .setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    private fun updateNotification(contentText: String) {
        // ... (código inalterado) ...
        val notification = createNotification(contentText); val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager; nm.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        // ... (código inalterado) ...
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "Serviço SmartDriver"; enableLights(false); enableVibration(false); setShowBadge(false) }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager; nm.createNotificationChannel(channel)
            Log.d(TAG, "Canal Notificação '$CHANNEL_ID' OK.")
        }
    }

    override fun onBind(intent: Intent?): IBinder? { return null }

    override fun onDestroy() {
        Log.w(TAG, "Serviço de Captura DESTRUÍDO")
        stopScreenCaptureInternal()
        if (::processingThread.isInitialized) { processingThread.quitSafely() }
        super.onDestroy()
    }
}