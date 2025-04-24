package com.example.smartdriver // <<< VERIFIQUE O PACKAGE

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.Bitmap // Import Bitmap
import android.graphics.Canvas // Import Canvas
import android.graphics.ColorMatrix // Import ColorMatrix
import android.graphics.ColorMatrixColorFilter // Import ColorMatrixColorFilter
import android.graphics.Paint // Import Paint
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001; private const val CHANNEL_ID = "screen_capture_channel"; private const val CHANNEL_NAME = "Screen Capture Service"
        const val ACTION_STOP_CAPTURE = "com.example.smartdriver.screen_capture.STOP"
        const val ACTION_CAPTURE_NOW = "com.example.smartdriver.screen_capture.CAPTURE_NOW"
        const val ACTION_UPDATE_SETTINGS = "com.example.smartdriver.screen_capture.UPDATE_SETTINGS"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val KEY_SAVE_IMAGES = "save_images"

        @JvmStatic val isRunning = AtomicBoolean(false)

        private const val OCR_IMAGE_SCALE_FACTOR = 1.0f // Sem escala por enquanto
        private const val OFFER_PROCESSING_LOCK_PERIOD_MS = 3000L // 3 segundos
        private const val HASH_CACHE_DURATION_MS = 800L
    }

    // Variáveis de Projeção/Captura
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0; private var screenHeight = 0; private var screenDensity = 0

    // Controle de Estado e Threads
    private val isCapturingActive = AtomicBoolean(false)
    private val isProcessingImage = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var processingThread: HandlerThread
    private lateinit var processingHandler: Handler

    // Configurações e Análise
    private lateinit var preferences: SharedPreferences
    @Volatile private var shouldSaveScreenshots = false
    private val imageAnalysisUtils = ImageAnalysisUtils()
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Controle de Duplicatas/Recência
    @Volatile private var lastOfferDetectedTime = 0L
    private var lastDetectedOfferSignature: String? = null
    private var lastScreenHash: Int? = null
    private var lastHashTime = 0L

    // Outras Variáveis
    private var screenshotCounter = 0
    private var initialResultCode: Int = Activity.RESULT_CANCELED
    private var initialResultData: Intent? = null

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
            initialResultData = getParcelableExtraCompat(intent, EXTRA_RESULT_DATA, Intent::class.java)
            Log.i(TAG, "Dados projeção: Code=$initialResultCode, Data=${initialResultData != null}")
            if (initialResultCode != Activity.RESULT_CANCELED && initialResultData != null) {
                if (!isCapturingActive.get()) {
                    MediaProjectionData.resultCode = initialResultCode
                    MediaProjectionData.resultData = initialResultData?.clone() as? Intent
                    setupMediaProjection(); startScreenCaptureInternal()
                }
            } else { Log.e(TAG, "Dados projeção inválidos! Parando."); stopSelf() }
        }

        when (intent?.action) {
            ACTION_STOP_CAPTURE -> { Log.i(TAG, "Ação STOP_CAPTURE."); stopScreenCaptureInternal(); stopSelf() }
            ACTION_CAPTURE_NOW -> {
                Log.d(TAG, "Ação CAPTURE_NOW.")
                if (mediaProjection != null && imageReader != null && isCapturingActive.get()) {
                    processingHandler.post { processAvailableImage(imageReader) }
                } else { Log.w(TAG, "CAPTURE_NOW ignorado: Serviço não pronto/ativo.") }
            }
            ACTION_UPDATE_SETTINGS -> {
                Log.d(TAG, "Ação UPDATE_SETTINGS.")
                if (intent.hasExtra(KEY_SAVE_IMAGES)) {
                    val save = intent.getBooleanExtra(KEY_SAVE_IMAGES, false)
                    if (save != shouldSaveScreenshots) {
                        shouldSaveScreenshots = save; preferences.edit().putBoolean(KEY_SAVE_IMAGES, save).apply()
                        Log.i(TAG, "'Salvar Screenshots' atualizado: $save")
                    }
                } else { Log.w(TAG,"UPDATE_SETTINGS sem extra '$KEY_SAVE_IMAGES'") }
            }
            null -> {
                if (initialResultCode != Activity.RESULT_CANCELED && !isCapturingActive.get()){ Log.i(TAG, "Re(Start) sem ação. Tentando iniciar..."); setupMediaProjection(); startScreenCaptureInternal() }
                else if (!isCapturingActive.get()) { Log.w(TAG, "Re(Start) sem dados válidos.") }
            }
            else -> { Log.w(TAG, "Ação desconhecida: ${intent.action}") }
        }
        return START_STICKY
    }

    private fun <T : Any?> getParcelableExtraCompat(intent: Intent?, key: String, clazz: Class<T>): T? {
        return intent?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { it.getParcelableExtra(key, clazz) } else { @Suppress("DEPRECATION") it.getParcelableExtra(key) as? T } }
    }

    private fun getScreenMetrics() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { val m = windowManager.currentWindowMetrics; screenWidth = m.bounds.width(); screenHeight = m.bounds.height() }
        else { val d = DisplayMetrics(); @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(d); screenWidth = d.widthPixels; screenHeight = d.heightPixels }
        screenDensity = resources.configuration.densityDpi
        Log.i(TAG, "Screen Metrics: $screenWidth x $screenHeight @ $screenDensity dpi")
        if (screenWidth <= 0 || screenHeight <= 0) { Log.w(TAG, "Falha métricas, fallback."); screenWidth = 1080; screenHeight = 1920; screenDensity = DisplayMetrics.DENSITY_DEFAULT }
    }

    private fun setupMediaProjection() {
        if (mediaProjection != null) { Log.d(TAG, "MP já config."); return }
        val code = initialResultCode; val data = initialResultData
        if (code == Activity.RESULT_CANCELED || data == null) { Log.e(TAG, "Não config MP: Dados inválidos."); stopSelf(); return }
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            mediaProjection = pm.getMediaProjection(code, data)
            if (mediaProjection == null) { Log.e(TAG, "Falha getMediaProjection."); MediaProjectionData.clear(); stopSelf(); return }
            mediaProjection?.registerCallback(object : MediaProjection.Callback() { override fun onStop() { Log.w(TAG, "MP parado externamente!"); stopScreenCaptureInternal(); stopSelf() } }, mainHandler)
            Log.i(TAG, "MP config."); setupImageReader()
        } catch (e: Exception) { Log.e(TAG, "Erro config MP: ${e.message}", e); mediaProjection = null; MediaProjectionData.clear(); stopSelf() }
    }

    private fun setupImageReader() {
        if (imageReader != null) { Log.d(TAG, "IR já config."); return }
        if (screenWidth <= 0 || screenHeight <= 0) { Log.e(TAG,"Não config IR: Dimensões inválidas."); return }
        try {
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            imageReader?.setOnImageAvailableListener({ reader -> processAvailableImage(reader) }, processingHandler)
            Log.d(TAG, "IR config: ${screenWidth}x$screenHeight")
        } catch (e: Exception) { Log.e(TAG, "Erro criar IR: ${e.message}", e); imageReader = null; stopSelf() }
    }

    private fun startScreenCaptureInternal() {
        if (mediaProjection == null) { Log.e(TAG, "Falha iniciar: MP nulo."); setupMediaProjection(); if(mediaProjection == null) return }
        if (imageReader == null) { Log.e(TAG, "Falha iniciar: IR nulo."); setupImageReader(); if(imageReader == null) return }
        if (isCapturingActive.get()) { Log.d(TAG, "Captura já ativa."); return }
        Log.i(TAG, "Iniciando VirtualDisplay...")
        try {
            virtualDisplay = mediaProjection!!.createVirtualDisplay("ScreenCapture", screenWidth, screenHeight, screenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, processingHandler)
            isCapturingActive.set(true); updateNotification("SmartDriver monitorando..."); Log.i(TAG, "Captura INICIADA.")
        } catch (e: Exception) { Log.e(TAG, "Erro criar VD: ${e.message}", e); isCapturingActive.set(false); stopSelf() }
    }

    private fun stopScreenCaptureInternal() {
        if (!isCapturingActive.getAndSet(false)) { Log.d(TAG, "Captura já inativa."); MediaProjectionData.clear(); initialResultCode = Activity.RESULT_CANCELED; initialResultData = null; return }
        Log.w(TAG, "Parando captura...")
        try { virtualDisplay?.release() } catch (_: Exception) {} finally { virtualDisplay = null }
        try { imageReader?.close() } catch (_: Exception) {} finally { imageReader = null }
        try { mediaProjection?.stop() } catch (_: Exception) {} finally { mediaProjection = null }
        initialResultCode = Activity.RESULT_CANCELED; initialResultData = null; MediaProjectionData.clear()
        isProcessingImage.set(false); lastScreenHash = null; Log.i(TAG, "Captura PARADA.")
        updateNotification("SmartDriver inativo")
    }

    private fun processAvailableImage(reader: ImageReader?) {
        if (reader == null || !isCapturingActive.get()) { return }
        val currentTimeForLockCheck = System.currentTimeMillis()
        if (currentTimeForLockCheck - lastOfferDetectedTime < OFFER_PROCESSING_LOCK_PERIOD_MS / 2) {
            Log.v(TAG, "Proc pulado: Pré-bloqueio recente."); reader.acquireLatestImage()?.close(); return
        }
        if (!isProcessingImage.compareAndSet(false, true)) {
            Log.v(TAG, "Proc pulado: Proc anterior ativo."); reader.acquireLatestImage()?.close(); return
        }

        var image: Image? = null; var originalBitmap: Bitmap? = null
        try {
            image = reader.acquireLatestImage(); if (image == null) { Log.v(TAG, "acquireLatestImage null."); isProcessingImage.set(false); return }
            originalBitmap = imageToBitmap(image); image.close(); image = null // Fecha image logo
            if (originalBitmap == null) { Log.e(TAG, "Falha converter Image->Bitmap."); isProcessingImage.set(false); return }

            val hash = calculateImageHash(originalBitmap); val currentTimeForHash = System.currentTimeMillis()
            if (currentTimeForHash - lastHashTime < HASH_CACHE_DURATION_MS && hash == lastScreenHash) {
                Log.v(TAG, "Hash igual recente. Pulando."); originalBitmap.recycle(); isProcessingImage.set(false); return
            }
            lastScreenHash = hash; lastHashTime = currentTimeForHash; Log.d(TAG, "Proc nova imagem (hash: $hash).")

            val roi = imageAnalysisUtils.getRegionsOfInterest(originalBitmap.width, originalBitmap.height).firstOrNull()
            var bitmapToAnalyze: Bitmap? = null; var regionTag = "UNKNOWN_ROI"
            if (roi != null && !roi.isEmpty && roi.width() > 0 && roi.height() > 0) {
                bitmapToAnalyze = imageAnalysisUtils.cropToRegion(originalBitmap, roi)
                if (bitmapToAnalyze != null) { Log.v(TAG, "Analisando ROI: ${roi.flattenToString()}"); if (!originalBitmap.isRecycled) originalBitmap.recycle(); originalBitmap = null; regionTag = "ROI_${roi.top}_${roi.height()}" }
                else { Log.e(TAG, "Falha recortar ROI. Usando tela inteira."); bitmapToAnalyze = originalBitmap; regionTag = "FULL_SCREEN_CROP_FAIL" }
            } else { Log.w(TAG, "ROI inválida/vazia, usando tela inteira."); bitmapToAnalyze = originalBitmap; regionTag = "FULL_SCREEN_ROI_FAIL" }

            if (bitmapToAnalyze != null && !bitmapToAnalyze.isRecycled) { processBitmapRegion(bitmapToAnalyze, screenWidth, screenHeight, regionTag) }
            else { Log.e(TAG,"Bitmap análise nulo/reciclado."); originalBitmap?.recycle(); isProcessingImage.set(false) }

        } catch (e: IllegalStateException) { Log.w(TAG, "Erro ISE em processAvailableImage: ${e.message}"); originalBitmap?.recycle(); isProcessingImage.set(false) }
        catch (e: Exception) { Log.e(TAG, "Erro GERAL processAvailableImage: ${e.message}", e); originalBitmap?.recycle(); isProcessingImage.set(false) }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        if (image.format != PixelFormat.RGBA_8888) { Log.w(TAG,"Formato inesperado: ${image.format}."); return null }
        val planes = image.planes; val buffer = planes[0].buffer; val pixelStride = planes[0].pixelStride; val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width
        var createdBitmap: Bitmap? = null
        try {
            val bitmapWithPadding = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
            bitmapWithPadding.copyPixelsFromBuffer(buffer)
            createdBitmap = if (rowPadding == 0) { bitmapWithPadding } else { val finalBitmap = Bitmap.createBitmap(bitmapWithPadding, 0, 0, image.width, image.height); bitmapWithPadding.recycle(); finalBitmap }
        } catch (e: Exception) { Log.e(TAG,"Erro converter Image->Bitmap: ${e.message}", e); createdBitmap = null }
        return createdBitmap
    }

    private fun calculateImageHash(bitmap: Bitmap): Int {
        val scaleSize = 64
        return try {
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaleSize, scaleSize, true)
            val pixels = IntArray(scaleSize * scaleSize); scaledBitmap.getPixels(pixels, 0, scaleSize, 0, 0, scaleSize, scaleSize)
            scaledBitmap.recycle(); pixels.contentHashCode()
        } catch (e: Exception) { Log.w(TAG,"Erro calcular hash: ${e.message}"); bitmap.hashCode() }
    }

    // --- Nova função de pré-processamento ---
    /**
     * Aplica pré-processamento básico (aumento de contraste) a um Bitmap.
     * Retorna um NOVO Bitmap pré-processado ou o original se ocorrer erro.
     * O Bitmap original NÃO é modificado nem reciclado por esta função.
     */
    private fun preprocessBitmapForOcr(originalBitmap: Bitmap?): Bitmap? {
        if (originalBitmap == null || originalBitmap.isRecycled) {
            Log.w(TAG, "Bitmap original nulo ou reciclado, não pode pré-processar.")
            return originalBitmap
        }

        Log.v(TAG, "Aplicando pré-processamento (contraste)...")
        val startTime = System.currentTimeMillis()

        return try { // Retorna o resultado do try
            val processedBitmap = Bitmap.createBitmap(originalBitmap.width, originalBitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(processedBitmap)
            val paint = Paint()

            val contrastValue = 1.5f // Ajuste este valor (ex: 1.2f - 2.0f)
            val brightnessValue = -(128 * (contrastValue - 1))
            val colorMatrix = ColorMatrix(floatArrayOf(
                contrastValue, 0f, 0f, 0f, brightnessValue,
                0f, contrastValue, 0f, 0f, brightnessValue,
                0f, 0f, contrastValue, 0f, brightnessValue,
                0f, 0f, 0f, 1f, 0f
            ))
            paint.colorFilter = ColorMatrixColorFilter(colorMatrix)

            canvas.drawBitmap(originalBitmap, 0f, 0f, paint) // Desenha com filtro

            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "Pré-processamento (contraste $contrastValue) aplicado em ${duration}ms.")
            processedBitmap // Retorna o bitmap processado

        } catch (e: Exception) {
            Log.e(TAG, "Erro durante pré-processamento do bitmap: ${e.message}", e)
            originalBitmap // Retorna o original em caso de erro
        }
    }
    // --- Fim da nova função ---

    // --- Função processBitmapRegion MODIFICADA para chamar preprocess ---
    private fun processBitmapRegion(bitmapToProcess: Bitmap, originalWidth: Int, originalHeight: Int, regionTag: String) {
        var bitmapAfterResize: Bitmap? = null
        var finalBitmapForOcr: Bitmap? = null // Bitmap que vai para o OCR
        val timeStart = System.currentTimeMillis()

        try {
            // 1. Redimensionamento (Opcional)
            val scaleFactor = OCR_IMAGE_SCALE_FACTOR
            if (abs(scaleFactor - 1.0f) > 0.01f) {
                val newWidth = (bitmapToProcess.width * scaleFactor).toInt(); val newHeight = (bitmapToProcess.height * scaleFactor).toInt()
                if (newWidth > 0 && newHeight > 0) {
                    bitmapAfterResize = Bitmap.createScaledBitmap(bitmapToProcess, newWidth, newHeight, true)
                    Log.v(TAG, "Bitmap redimensionado para OCR: ${newWidth}x$newHeight")
                } else { Log.w(TAG, "Resize inválido"); bitmapAfterResize = bitmapToProcess }
            } else { bitmapAfterResize = bitmapToProcess }

            if (bitmapAfterResize == null || bitmapAfterResize.isRecycled) {
                Log.e(TAG, "Bitmap pós-resize nulo/reciclado ($regionTag)."); if (bitmapToProcess != bitmapAfterResize) bitmapToProcess.recycle(); isProcessingImage.compareAndSet(true, false); return
            }

            // 2. Pré-processamento <<< CHAMADA AQUI >>>
            finalBitmapForOcr = preprocessBitmapForOcr(bitmapAfterResize)
            // ---------------------------------------

            if (finalBitmapForOcr == null || finalBitmapForOcr.isRecycled) {
                Log.e(TAG, "Bitmap OCR nulo/reciclado pós-process ($regionTag)."); if (bitmapAfterResize != finalBitmapForOcr && !bitmapAfterResize.isRecycled) bitmapAfterResize.recycle(); if (bitmapToProcess != bitmapAfterResize && !bitmapToProcess.isRecycled) bitmapToProcess.recycle(); isProcessingImage.compareAndSet(true, false); return
            }

            // 3. Reciclar intermediário (se diferente)
            if (finalBitmapForOcr != bitmapAfterResize && !bitmapAfterResize.isRecycled) {
                Log.v(TAG, "Reciclando bitmap pós-resize.")
                bitmapAfterResize.recycle()
            }
            // Recicla original se não foi usado em resize/process
            if (finalBitmapForOcr != bitmapToProcess && !bitmapToProcess.isRecycled) {
                bitmapToProcess.recycle()
            }

            // 4. Enviar para OCR
            val inputImage = InputImage.fromBitmap(finalBitmapForOcr, 0); val bitmapParaListeners = finalBitmapForOcr
            Log.d(TAG, "Iniciando OCR '$regionTag' (${bitmapParaListeners.width}x${bitmapParaListeners.height}) [Pós-Process]...")
            textRecognizer.process(inputImage)
                .addOnSuccessListener listener@{ visionText ->
                    val extractedText = visionText.text; Log.d(TAG,"OCR '$regionTag': OK (len=${extractedText.length})")
                    if (extractedText.isBlank()) { return@listener }
                    val currentTime = System.currentTimeMillis(); val offerData = imageAnalysisUtils.analyzeTextForOffer(extractedText)
                    if (offerData == null || !offerData.isValid()) { Log.v(TAG,"'$regionTag': Não parece oferta válida."); return@listener }
                    val offerSignature = createOfferSignature(offerData); val timeSinceLastOffer = currentTime - lastOfferDetectedTime
                    if (timeSinceLastOffer < OFFER_PROCESSING_LOCK_PERIOD_MS && offerSignature == lastDetectedOfferSignature) { Log.d(TAG, "Oferta '$regionTag' ignorada: Bloqueio/Duplicata."); return@listener }
                    Log.i(TAG, "!!!! OFERTA VÁLIDA ($regionTag) Processando... !!!!"); lastOfferDetectedTime = currentTime; lastDetectedOfferSignature = offerSignature
                    if (shouldSaveScreenshots) {
                        val refBitmap = bitmapParaListeners
                        if (!refBitmap.isRecycled) {
                            try { val copy = refBitmap.copy(refBitmap.config ?: Bitmap.Config.ARGB_8888, false); saveScreenshotToGallery(copy, "OFERTA_${regionTag}_PROC") } // Nome _PROC indica processada
                            catch (e: Exception) { Log.e(TAG,"Erro COPIAR bitmap p/ salvar: ${e.message}", e) }
                        } else { Log.w(TAG,"Bitmap já reciclado, não salva.") }
                    }
                    mainHandler.post { OfferManager.getInstance(applicationContext).processOffer(offerData) }
                }
                .addOnFailureListener { e -> Log.e(TAG, "Falha OCR ($regionTag): ${e.message}") }
                .addOnCompleteListener {
                    if (!bitmapParaListeners.isRecycled) { bitmapParaListeners.recycle(); Log.v(TAG,"Bitmap OCR ($regionTag) reciclado no onComplete.") }
                    else { Log.w(TAG,"Bitmap OCR ($regionTag) já reciclado antes do onComplete?") }
                    isProcessingImage.compareAndSet(true, false); Log.d(TAG,"Proc região '$regionTag' finalizado.")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Erro GERAL processBitmapRegion ($regionTag): ${e.message}", e)
            finalBitmapForOcr?.recycle(); bitmapAfterResize?.recycle(); bitmapToProcess?.recycle() // Tenta limpar tudo
            isProcessingImage.compareAndSet(true, false)
        }
    }
    // --- Fim de processBitmapRegion Modificada ---

    private fun createOfferSignature(offerData: OfferData): String {
        val v = offerData.value.replace(",","."); val pd = offerData.pickupDistance.ifBlank{"0"}
        val td = offerData.tripDistance.ifBlank{"0"}; val pt = offerData.pickupDuration.ifBlank{"0"}
        val tt = offerData.tripDuration.ifBlank{"0"}; return "v:$v|pd:$pd|td:$td|pt:$pt|tt:$tt"
    }

    private fun saveScreenshotToGallery(bitmap: Bitmap?, prefix: String) {
        if (!shouldSaveScreenshots || bitmap == null || bitmap.isRecycled) { bitmap?.recycle(); return }
        processingHandler.post {
            val timeStamp = SimpleDateFormat("yyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
            val fileName = "SmartDriver_${prefix}_${timeStamp}_${screenshotCounter++}.jpg"
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000); put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SmartDriverDebug"); put(MediaStore.Images.Media.IS_PENDING, 1) }
            }
            var imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            var success = false
            if (imageUri != null) {
                try {
                    contentResolver.openOutputStream(imageUri)?.use { it.write(ByteArray(0)); bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it); success = true } ?: Log.e(TAG, "Falha OutputStream $imageUri")
                } catch (e: IOException) { Log.e(TAG, "Erro IO salvar $fileName: ${e.message}", e) }
                catch (e: Exception) { Log.e(TAG, "Erro salvar $fileName: ${e.message}", e) }
                finally { if (!success) { try { contentResolver.delete(imageUri, null, null) } catch (_: Exception) {} } }
            } else { Log.e(TAG, "Falha criar URI $fileName.") }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && imageUri != null && success) {
                contentValues.clear(); contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                try { contentResolver.update(imageUri, contentValues, null, null) } catch (e: Exception) { Log.e(TAG,"Erro upd IS_PENDING $fileName: ${e.message}") }
            }
            if (!bitmap.isRecycled) { bitmap.recycle(); Log.v(TAG,"Bitmap salvo ($prefix) reciclado.") }
        }
    }

    private fun createNotification(contentText: String): Notification {
        createNotificationChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SmartDriver")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_smartdriver) // Ícone Monocromático
            .setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try { nm.notify(NOTIFICATION_ID, createNotification(contentText)) } catch (e: Exception) { Log.e(TAG, "Erro atualizar notificação: ${e.message}") }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply { description = "Notificação Serviço Captura"; enableLights(false); enableVibration(false); setShowBadge(false) }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            try { nm.createNotificationChannel(channel) } catch (e: Exception) { Log.e(TAG, "Erro criar canal notificação: ${e.message}") }
        }
    }

    override fun onBind(intent: Intent?): IBinder? { return null }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "Serviço de Captura DESTRUÍDO"); isRunning.set(false)
        stopScreenCaptureInternal()
        if(::processingThread.isInitialized){ processingThread.quitSafely(); Log.d(TAG,"ProcessingThread finalizada.") }
    }
}