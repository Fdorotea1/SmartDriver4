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
        const val ACTION_SAVE_LAST_VALID_OFFER_SCREENSHOT = "com.example.smartdriver.screen_capture.SAVE_LAST_VALID" // <<< NOVA AÇÃO PARA SALVAR
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val KEY_SAVE_IMAGES = "save_images" // Usado por MainActivity e SettingsActivity

        @JvmStatic val isRunning = AtomicBoolean(false)

        // Constantes de controle (mantidas)
        private const val OCR_IMAGE_SCALE_FACTOR = 1.0f
        private const val OFFER_PROCESSING_LOCK_PERIOD_MS = 3000L // Bloqueio interno rápido
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

    // Controle de Duplicatas/Recência interno (para evitar processamento excessivo)
    @Volatile private var lastOfferDetectedTime = 0L // Timestamp do último *processamento* iniciado neste serviço
    private var lastDetectedOfferSignature: String? = null // Assinatura do último *processamento* iniciado

    // --- Variáveis para o Hash Cache --- <<< DECLARAÇÕES ADICIONADAS AQUI <<<
    private var lastScreenHash: Int? = null // Armazena o hash da última tela processada
    private var lastHashTime: Long = 0L     // Timestamp de quando o último hash foi calculado
    // ------------------------------------

    // --- NOVO: Bitmap preparado para ser salvo sob demanda ---
    @Volatile private var lastBitmapForPotentialSave: Bitmap? = null
    // @Volatile private var lastOfferSignatureForPotentialSave: String? = null // Opcional: Para verificação extra no comando de salvar

    // Outras Variáveis
    private var screenshotCounter = 0
    private var initialResultCode: Int = Activity.RESULT_CANCELED
    private var initialResultData: Intent? = null

    // --- Métodos do Ciclo de Vida e Configuração (sem grandes alterações) ---

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

        // --- Lógica para obter dados de projeção (MediaProjection) ---
        if (intent?.hasExtra(EXTRA_RESULT_CODE) == true && initialResultCode == Activity.RESULT_CANCELED) {
            initialResultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            // Usa clone() para evitar problemas se o Intent original for modificado
            initialResultData = getParcelableExtraCompat(intent, EXTRA_RESULT_DATA, Intent::class.java)?.clone() as? Intent
            Log.i(TAG, "Dados projeção recebidos: Code=$initialResultCode, Data=${initialResultData != null}")
            if (initialResultCode != Activity.RESULT_CANCELED && initialResultData != null) {
                if (!isCapturingActive.get()) {
                    MediaProjectionData.resultCode = initialResultCode
                    MediaProjectionData.resultData = initialResultData?.clone() as? Intent // Armazena cópia
                    setupMediaProjection(); startScreenCaptureInternal()
                }
            } else { Log.e(TAG, "Dados projeção inválidos recebidos! Parando."); stopSelf() }
        }
        // --- Fim da lógica de projeção ---

        when (intent?.action) {
            ACTION_STOP_CAPTURE -> { Log.i(TAG, "Ação STOP_CAPTURE recebida."); stopScreenCaptureInternal(); stopSelf() }
            ACTION_CAPTURE_NOW -> {
                //Log.d(TAG, "Ação CAPTURE_NOW recebida.") // Log pode ser verboso
                if (mediaProjection != null && imageReader != null && isCapturingActive.get()) {
                    // Posta a tarefa de processamento para a thread dedicada
                    processingHandler.post { processAvailableImage(imageReader) }
                } else { Log.w(TAG, "CAPTURE_NOW ignorado: Serviço não pronto ou captura inativa.") }
            }

            // --- NOVO CASE: Lida com o pedido para salvar o último screenshot válido ---
            ACTION_SAVE_LAST_VALID_OFFER_SCREENSHOT -> {
                Log.i(TAG, "Recebido pedido para salvar último screenshot válido (ACTION_SAVE_LAST_VALID_OFFER_SCREENSHOT).")
                // Opcional: Verificação de assinatura
                // val expectedSignature = intent.getStringExtra("offer_signature") // Se OfferManager enviar
                // if (expectedSignature != null && expectedSignature != lastOfferSignatureForPotentialSave) {
                //     Log.w(TAG, "Assinatura não corresponde! Screenshot não salvo. Esperado: $expectedSignature, Último: $lastOfferSignatureForPotentialSave")
                //     // Limpa o bitmap pendente se a assinatura não bater? Talvez não, pode ser de uma oferta ligeiramente diferente mas ainda pendente.
                // } else

                // Posta a tarefa de salvamento para a thread de processamento
                processingHandler.post {
                    val bitmapToSave = lastBitmapForPotentialSave // Pega a referência atual
                    if (bitmapToSave != null && !bitmapToSave.isRecycled) {
                        Log.d(TAG, ">>> Iniciando salvamento do screenshot da oferta confirmada...")
                        // Passa o bitmap, a função saveScreenshotToGallery AGORA o recicla APÓS a tentativa
                        saveScreenshotToGallery(bitmapToSave, "OFERTA_VALIDA") // Novo prefixo indica confirmação
                        lastBitmapForPotentialSave = null // Limpa referência APÓS postar a tarefa de salvar
                        // lastOfferSignatureForPotentialSave = null // Limpa assinatura associada (se usar)
                        Log.d(TAG,"Referência ao bitmap pendente limpa após postar para salvar.")
                    } else {
                        Log.w(TAG, "Nenhum bitmap válido pendente encontrado para salvar ou já foi reciclado.")
                        lastBitmapForPotentialSave = null // Garante limpeza
                    }
                } // Fim do post
            }
            // --- Fim do novo case ---

            ACTION_UPDATE_SETTINGS -> {
                Log.d(TAG, "Ação UPDATE_SETTINGS recebida.")
                if (intent.hasExtra(KEY_SAVE_IMAGES)) {
                    val save = intent.getBooleanExtra(KEY_SAVE_IMAGES, false)
                    if (save != shouldSaveScreenshots) { // Só atualiza se mudou
                        shouldSaveScreenshots = save
                        preferences.edit().putBoolean(KEY_SAVE_IMAGES, save).apply()
                        Log.i(TAG, "'Salvar Screenshots' atualizado para: $save")
                        // Se a opção foi DESLIGADA, remove qualquer bitmap que estava pendente
                        if (!save) {
                            processingHandler.post { recyclePotentialSaveBitmap("configuração desligada") }
                        }
                    }
                } else { Log.w(TAG,"UPDATE_SETTINGS recebido sem o extra '$KEY_SAVE_IMAGES'") }
            }
            null -> { // Intent nulo ou sem ação específica (pode acontecer em reinícios)
                // Se temos dados de projeção e não estamos ativos, tenta reiniciar
                if (initialResultCode != Activity.RESULT_CANCELED && !isCapturingActive.get()){
                    Log.i(TAG, "Comando nulo/sem ação. Tentando (re)iniciar captura com dados existentes...");
                    if (MediaProjectionData.resultCode == Activity.RESULT_CANCELED) { // Garante que MediaProjectionData está sincronizado
                        MediaProjectionData.resultCode = initialResultCode
                        MediaProjectionData.resultData = initialResultData?.clone() as? Intent
                    }
                    setupMediaProjection(); startScreenCaptureInternal()
                } else if (!isCapturingActive.get()) { Log.w(TAG, "Comando nulo/sem ação, mas sem dados válidos para iniciar.") }
            }
            else -> { Log.w(TAG, "Ação desconhecida recebida: ${intent.action}") }
        }
        // Usa START_STICKY para tentar reiniciar o serviço se for morto pelo sistema
        return START_STICKY
    }

    // --- Função auxiliar para obter Parcelable (compatibilidade) ---
    private fun <T : Any?> getParcelableExtraCompat(intent: Intent?, key: String, clazz: Class<T>): T? {
        return intent?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableExtra(key, clazz)
            } else {
                // Suppressão necessária para versões < 33
                @Suppress("DEPRECATION") it.getParcelableExtra(key) as? T
            }
        }
    }

    // --- Funções de Configuração de Captura (MediaProjection, ImageReader, VirtualDisplay) ---

    private fun getScreenMetrics() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            screenWidth = metrics.bounds.width(); screenHeight = metrics.bounds.height()
        } else {
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            screenWidth = displayMetrics.widthPixels; screenHeight = displayMetrics.heightPixels
        }
        screenDensity = resources.configuration.densityDpi
        Log.i(TAG, "Screen Metrics: $screenWidth x $screenHeight @ $screenDensity dpi")
        // Fallback básico se algo falhar
        if (screenWidth <= 0 || screenHeight <= 0) {
            Log.w(TAG, "Falha ao obter métricas de tela, usando fallback (1080x1920).");
            screenWidth = 1080; screenHeight = 1920; screenDensity = DisplayMetrics.DENSITY_DEFAULT
        }
    }

    private fun setupMediaProjection() {
        if (mediaProjection != null) { Log.d(TAG, "MediaProjection já configurado."); return }
        // Usa os dados armazenados em MediaProjectionData
        val code = MediaProjectionData.resultCode
        val data = MediaProjectionData.resultData
        if (code == Activity.RESULT_CANCELED || data == null) {
            Log.e(TAG, "Não foi possível configurar MediaProjection: Dados inválidos (code=$code, data=${data==null}). Parando serviço.");
            stopSelf(); return
        }
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            mediaProjection = projectionManager.getMediaProjection(code, data)
            if (mediaProjection == null) {
                Log.e(TAG, "Falha ao obter MediaProjection (retornou null). Limpando dados.");
                MediaProjectionData.clear(); stopSelf(); return
            }
            // Registra callback para ser notificado se a projeção for parada externamente
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection parado externamente! Limpando e parando serviço.");
                    stopScreenCaptureInternal(); stopSelf()
                }
            }, mainHandler) // Usa handler do main thread para callbacks
            Log.i(TAG, "MediaProjection configurado com sucesso.");
            setupImageReader() // Configura o ImageReader em seguida
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao configurar MediaProjection: ${e.message}", e);
            mediaProjection = null; MediaProjectionData.clear(); stopSelf()
        }
    }

    private fun setupImageReader() {
        if (imageReader != null) { Log.d(TAG, "ImageReader já configurado."); return }
        if (screenWidth <= 0 || screenHeight <= 0) { Log.e(TAG,"Não foi possível configurar ImageReader: Dimensões de tela inválidas."); return }
        try {
            // Cria ImageReader para receber imagens RGBA_8888 com buffer de 2 imagens
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            // Define o listener que será chamado quando uma nova imagem estiver disponível (na thread de processamento)
            imageReader?.setOnImageAvailableListener({ reader -> processAvailableImage(reader) }, processingHandler)
            Log.i(TAG, "ImageReader configurado: ${screenWidth}x$screenHeight, Formato=RGBA_8888, Buffer=2")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Erro ao criar ImageReader (Argumento Ilegal): ${e.message}", e); imageReader = null; stopSelf()
        } catch (e: Exception) { Log.e(TAG, "Erro genérico ao criar ImageReader: ${e.message}", e); imageReader = null; stopSelf() }
    }

    private fun startScreenCaptureInternal() {
        // Verificações prévias
        if (mediaProjection == null) { Log.e(TAG, "Falha ao iniciar captura: MediaProjection nulo."); setupMediaProjection(); if(mediaProjection == null) return }
        if (imageReader == null) { Log.e(TAG, "Falha ao iniciar captura: ImageReader nulo."); setupImageReader(); if(imageReader == null) return }
        if (isCapturingActive.get()) { Log.d(TAG, "Captura de tela já está ativa."); return }

        Log.i(TAG, "Iniciando VirtualDisplay para captura de tela...")
        try {
            // Cria o VirtualDisplay que espelha a tela para a Surface do ImageReader
            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "ScreenCapture", screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, // Flag para espelhar conteúdo protegido se possível (não garantido)
                imageReader!!.surface, // Onde a imagem será renderizada
                null, // Callback (opcional)
                processingHandler // Handler para callbacks do VirtualDisplay (se houver)
            )
            isCapturingActive.set(true); updateNotification("SmartDriver monitorando..."); Log.i(TAG, ">>> Captura de tela INICIADA <<<")
        } catch (e: SecurityException) {
            Log.e(TAG, "Erro de segurança ao criar VirtualDisplay (permissão revogada?): ${e.message}", e); isCapturingActive.set(false); stopSelf()
        } catch (e: Exception) { Log.e(TAG, "Erro genérico ao criar VirtualDisplay: ${e.message}", e); isCapturingActive.set(false); stopSelf() }
    }

    private fun stopScreenCaptureInternal() {
        if (!isCapturingActive.getAndSet(false)) { // Tenta setar para false e verifica o valor anterior
            Log.d(TAG, "Captura de tela já estava inativa.");
            // Limpa dados de projeção mesmo se já estava inativo, por segurança
            MediaProjectionData.clear(); initialResultCode = Activity.RESULT_CANCELED; initialResultData = null;
            return
        }
        Log.w(TAG, "Parando captura de tela...")
        // Libera recursos na ordem inversa da criação (VirtualDisplay -> ImageReader -> MediaProjection)
        try { virtualDisplay?.release() } catch (e: Exception) { Log.e(TAG,"Erro liberar VD: ${e.message}") } finally { virtualDisplay = null }
        try { imageReader?.close() } catch (e: Exception) { Log.e(TAG,"Erro fechar IR: ${e.message}") } finally { imageReader = null }
        try { mediaProjection?.stop() } catch (e: Exception) { Log.e(TAG,"Erro parar MP: ${e.message}") } finally { mediaProjection = null }

        // Limpa estado
        MediaProjectionData.clear() // Limpa os dados estáticos
        initialResultCode = Activity.RESULT_CANCELED; initialResultData = null // Limpa os dados iniciais do serviço
        isProcessingImage.set(false); lastScreenHash = null // Limpa o hash cache
        processingHandler.post { recyclePotentialSaveBitmap("captura parada") } // Limpa bitmap pendente na thread correta

        Log.i(TAG, ">>> Captura de tela PARADA <<<");
        updateNotification("SmartDriver inativo")
    }

    // --- Processamento de Imagem ---

    /** Chamado na thread de processamento quando uma nova imagem está disponível */
    private fun processAvailableImage(reader: ImageReader?) {
        if (reader == null || !isCapturingActive.get()) { return } // Verifica se estamos ativos e o reader é válido

        // --- Verificação de bloqueio rápido (antes de adquirir imagem) ---
        // Ajuda a evitar processamento se uma oferta acabou de ser processada
        val currentTimeForLockCheck = System.currentTimeMillis()
        if (currentTimeForLockCheck - lastOfferDetectedTime < OFFER_PROCESSING_LOCK_PERIOD_MS / 2) { // Usa metade do tempo para rápida rejeição
            // Log.v(TAG, "Processamento pulado: Bloqueio rápido pós-oferta recente.");
            reader.acquireLatestImage()?.close() // Descarta a imagem rapidamente
            return
        }
        // --- Fim do bloqueio rápido ---

        // Garante que apenas uma imagem seja processada por vez
        if (!isProcessingImage.compareAndSet(false, true)) {
            // Log.v(TAG, "Processamento pulado: Processamento anterior ainda ativo.");
            reader.acquireLatestImage()?.close() // Descarta a imagem
            return
        }

        var image: Image? = null; var originalBitmap: Bitmap? = null
        try {
            image = reader.acquireLatestImage() // Pega a imagem mais recente disponível
            if (image == null) {
                // Log.v(TAG, "acquireLatestImage retornou null (sem imagem nova?).");
                isProcessingImage.set(false); return
            }

            // Converte Image para Bitmap e fecha Image o mais rápido possível
            originalBitmap = imageToBitmap(image)
            image.close(); image = null // Libera recursos da Image

            if (originalBitmap == null) { Log.e(TAG, "Falha ao converter Image para Bitmap."); isProcessingImage.set(false); return }

            // --- Verificação de Hash para evitar processar telas idênticas seguidas ---
            val hash = calculateImageHash(originalBitmap); val currentTimeForHash = System.currentTimeMillis()
            if (currentTimeForHash - lastHashTime < HASH_CACHE_DURATION_MS && hash == lastScreenHash) { // <<< USA AS VARIÁVEIS CORRIGIDAS <<<
                // Log.v(TAG, "Hash da tela idêntico ao anterior recente ($hash). Pulando processamento.");
                originalBitmap.recycle(); originalBitmap = null // Recicla o bitmap não usado
                isProcessingImage.set(false); return
            }
            lastScreenHash = hash; lastHashTime = currentTimeForHash // <<< USA AS VARIÁVEIS CORRIGIDAS <<<
            // Log.d(TAG, "Processando nova imagem (hash: $hash).") // Log pode ser verboso
            // --- Fim da verificação de Hash ---

            // Obtem Região de Interesse (ROI) - Ex: parte inferior da tela
            val roi = imageAnalysisUtils.getRegionsOfInterest(originalBitmap.width, originalBitmap.height).firstOrNull()
            var bitmapToAnalyze: Bitmap? = null; var regionTag = "UNKNOWN_ROI"

            if (roi != null && !roi.isEmpty && roi.width() > 0 && roi.height() > 0) {
                bitmapToAnalyze = imageAnalysisUtils.cropToRegion(originalBitmap, roi)
                if (bitmapToAnalyze != null) {
                    // Log.v(TAG, "Analisando ROI: ${roi.flattenToString()}");
                    if (!originalBitmap.isRecycled) originalBitmap.recycle(); originalBitmap = null // Recicla original se o corte deu certo
                    regionTag = "ROI_${roi.top}_${roi.height()}"
                } else { // Falha no corte
                    Log.e(TAG, "Falha ao recortar ROI. Usando tela inteira como fallback.");
                    bitmapToAnalyze = originalBitmap // Usa o original
                    originalBitmap = null // Evita reciclagem dupla no final
                    regionTag = "FULL_SCREEN_CROP_FAIL"
                }
            } else { // ROI inválida ou não definida
                Log.w(TAG, "ROI inválida ou vazia, usando tela inteira.");
                bitmapToAnalyze = originalBitmap // Usa o original
                originalBitmap = null // Evita reciclagem dupla no final
                regionTag = "FULL_SCREEN_ROI_FAIL"
            }

            // Processa o bitmap final (seja ROI ou tela inteira)
            if (bitmapToAnalyze != null && !bitmapToAnalyze.isRecycled) {
                processBitmapRegion(bitmapToAnalyze, screenWidth, screenHeight, regionTag)
                // processBitmapRegion agora é responsável por reciclar o bitmapToAnalyze e suas cópias
            } else {
                Log.e(TAG,"Bitmap para análise final é nulo ou já reciclado.");
                originalBitmap?.recycle() // Tenta reciclar o original se sobrou
                isProcessingImage.set(false)
            }

        } catch (e: IllegalStateException) {
            // Pode ocorrer se o ImageReader for fechado enquanto tentamos adquirir/processar
            Log.w(TAG, "Erro de Estado Ilegal em processAvailableImage (serviço parando?): ${e.message}");
            originalBitmap?.recycle()
            image?.close() // Garante que a imagem seja fechada
            isProcessingImage.set(false) // Libera o lock
        } catch (e: Exception) {
            Log.e(TAG, "Erro GERAL durante processamento da imagem disponível: ${e.message}", e);
            originalBitmap?.recycle()
            image?.close()
            isProcessingImage.set(false) // Libera o lock
        }
        // O 'finally' não é ideal aqui porque isProcessingImage precisa ser resetado
        // dentro dos listeners assíncronos do OCR em processBitmapRegion.
    }

    /** Converte um objeto Image (formato RGBA_8888) para Bitmap */
    private fun imageToBitmap(image: Image): Bitmap? {
        // Validação básica do formato esperado
        if (image.format != PixelFormat.RGBA_8888) {
            Log.w(TAG,"Formato de imagem inesperado: ${image.format}. Esperado RGBA_8888.");
            return null
        }
        val planes = image.planes
        val buffer = planes[0].buffer // Buffer de pixels
        val pixelStride = planes[0].pixelStride // Distância entre pixels consecutivos em uma linha
        val rowStride = planes[0].rowStride // Distância entre o início de linhas consecutivas (pode incluir padding)
        val rowPadding = rowStride - pixelStride * image.width // Calcula o padding no final de cada linha

        var bitmap: Bitmap? = null
        try {
            // Cria bitmap com largura que inclui o padding (se houver)
            val bitmapWithPadding = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
            bitmapWithPadding.copyPixelsFromBuffer(buffer) // Copia os dados do buffer

            // Se havia padding, cria um bitmap final sem ele
            bitmap = if (rowPadding == 0) {
                bitmapWithPadding // Sem padding, usa direto
            } else {
                val finalBitmap = Bitmap.createBitmap(bitmapWithPadding, 0, 0, image.width, image.height)
                bitmapWithPadding.recycle() // Recicla o bitmap intermediário com padding
                finalBitmap
            }
        } catch (e: Exception) {
            Log.e(TAG,"Erro ao converter Image para Bitmap: ${e.message}", e); bitmap = null
        }
        return bitmap
    }

    /** Calcula um hash simples da imagem para detecção de duplicatas */
    private fun calculateImageHash(bitmap: Bitmap): Int {
        // Reduz a imagem para um tamanho pequeno e calcula o hash dos pixels
        val scaleSize = 64 // Tamanho pequeno para cálculo rápido
        return try {
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaleSize, scaleSize, true) // Reduz com filtro bilinear
            val pixels = IntArray(scaleSize * scaleSize)
            scaledBitmap.getPixels(pixels, 0, scaleSize, 0, 0, scaleSize, scaleSize) // Extrai pixels
            scaledBitmap.recycle() // Libera o bitmap redimensionado
            pixels.contentHashCode() // Calcula hash do array de pixels
        } catch (e: Exception) {
            Log.w(TAG,"Erro ao calcular hash da imagem: ${e.message}");
            bitmap.hashCode() // Fallback para hashcode do bitmap original (menos eficaz)
        }
    }

    /** Aplica pré-processamento de contraste a um Bitmap */
    private fun preprocessBitmapForOcr(originalBitmap: Bitmap?): Bitmap? {
        if (originalBitmap == null || originalBitmap.isRecycled) {
            Log.w(TAG, "Bitmap original nulo ou reciclado, não pode pré-processar.")
            return originalBitmap // Retorna o original (nulo ou reciclado)
        }

        //Log.v(TAG, "Aplicando pré-processamento (contraste)...")
        val startTime = System.currentTimeMillis()

        var processedBitmap: Bitmap? = null
        try {
            processedBitmap = Bitmap.createBitmap(originalBitmap.width, originalBitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(processedBitmap)
            val paint = Paint()

            // Ajuste de Contraste e Brilho
            val contrastValue = 1.5f // Valor de contraste (1.0 = sem mudança)
            val brightnessValue = -(128 * (contrastValue - 1)) // Ajuste de brilho para compensar contraste
            val colorMatrix = ColorMatrix(floatArrayOf(
                contrastValue, 0f, 0f, 0f, brightnessValue,
                0f, contrastValue, 0f, 0f, brightnessValue,
                0f, 0f, contrastValue, 0f, brightnessValue,
                0f, 0f, 0f, 1f, 0f // Alpha inalterado
            ))
            paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
            canvas.drawBitmap(originalBitmap, 0f, 0f, paint) // Desenha o original no novo bitmap com o filtro

            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "Pré-processamento (contraste $contrastValue) aplicado em ${duration}ms.")
            // Não recicla o original aqui, deixa para quem chamou
            return processedBitmap // Retorna o bitmap processado

        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Erro de Memória (OOM) durante pré-processamento!", e)
            processedBitmap?.recycle() // Tenta limpar o que foi criado
            // Não retorna o original neste caso, pois a falha indica falta de memória
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Erro durante pré-processamento do bitmap: ${e.message}", e)
            processedBitmap?.recycle() // Tenta limpar o que foi criado
            // Retorna o original como fallback em caso de outros erros
            return originalBitmap
        }
    }

    /** Processa uma região específica (ou tela inteira) do bitmap com OCR */
    private fun processBitmapRegion(bitmapToProcess: Bitmap, originalWidth: Int, originalHeight: Int, regionTag: String) {
        var bitmapAfterResize: Bitmap? = null
        var bitmapPreprocessed: Bitmap? = null
        var bitmapCopyForListeners: Bitmap? = null // Cópia final que vai para OCR e pode ser guardada
        val timeStart = System.currentTimeMillis()

        try {
            // 1. Redimensionamento (se necessário)
            val scaleFactor = OCR_IMAGE_SCALE_FACTOR
            if (abs(scaleFactor - 1.0f) > 0.01f) { // Se escala for diferente de 1.0
                val newWidth = (bitmapToProcess.width * scaleFactor).toInt()
                val newHeight = (bitmapToProcess.height * scaleFactor).toInt()
                if (newWidth > 0 && newHeight > 0) {
                    bitmapAfterResize = Bitmap.createScaledBitmap(bitmapToProcess, newWidth, newHeight, true)
                    Log.v(TAG, "Bitmap redimensionado para OCR: ${newWidth}x$newHeight ($regionTag)")
                } else { Log.w(TAG, "Dimensões de resize inválidas ($newWidth x $newHeight). Usando original."); bitmapAfterResize = bitmapToProcess }
            } else { bitmapAfterResize = bitmapToProcess } // Sem resize necessário

            if (bitmapAfterResize == null || bitmapAfterResize.isRecycled) {
                Log.e(TAG, "Bitmap pós-resize nulo/reciclado ($regionTag). Abortando processamento da região.");
                if (bitmapToProcess != bitmapAfterResize && bitmapToProcess?.isRecycled == false) bitmapToProcess.recycle() // Limpa original se diferente
                isProcessingImage.compareAndSet(true, false); return
            }

            // 2. Pré-processamento (ex: contraste)
            bitmapPreprocessed = preprocessBitmapForOcr(bitmapAfterResize) // Pode retornar o original ou um novo

            if (bitmapPreprocessed == null || bitmapPreprocessed.isRecycled) {
                Log.e(TAG, "Bitmap OCR nulo/reciclado pós-pré-processamento ($regionTag).");
                if (bitmapAfterResize != bitmapPreprocessed && bitmapAfterResize?.isRecycled == false) bitmapAfterResize.recycle(); // Limpa intermediário
                if (bitmapToProcess != bitmapAfterResize && bitmapToProcess?.isRecycled == false) bitmapToProcess.recycle(); // Limpa original
                isProcessingImage.compareAndSet(true, false); return
            }

            // 3. Cria cópia final para OCR e potencial salvamento
            // Isso desacopla o bitmap do listener do bitmap que pode ficar pendente para salvar
            try {
                bitmapCopyForListeners = bitmapPreprocessed.copy(bitmapPreprocessed.config ?: Bitmap.Config.ARGB_8888, false)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao COPIAR bitmap pré-processado para listeners ($regionTag): ${e.message}", e)
                bitmapCopyForListeners = null // Falhou a cópia
            }


            // 4. Reciclagem dos bitmaps intermediários AGORA que temos a cópia final (ou null)
            if (bitmapPreprocessed != bitmapAfterResize && bitmapAfterResize?.isRecycled == false) {
                // Log.v(TAG, "Reciclando bitmap pós-resize ($regionTag).")
                bitmapAfterResize.recycle()
            }
            if (bitmapPreprocessed != bitmapToProcess && bitmapToProcess?.isRecycled == false) {
                // Log.v(TAG, "Reciclando bitmap original/entrada ($regionTag).")
                bitmapToProcess.recycle()
            }
            // Recicla o pré-processado se ele for diferente da cópia (o que sempre será, exceto em erro de cópia)
            if (bitmapCopyForListeners != bitmapPreprocessed && bitmapPreprocessed?.isRecycled == false) {
                bitmapPreprocessed.recycle()
            }

            // 5. Verifica se a cópia para listeners é válida antes de prosseguir
            if (bitmapCopyForListeners == null || bitmapCopyForListeners.isRecycled) {
                Log.e(TAG, "Cópia do Bitmap para OCR falhou ou já reciclado ($regionTag). Não pode continuar.")
                isProcessingImage.compareAndSet(true, false)
                recyclePotentialSaveBitmap("falha na cópia para listener") // Limpa qualquer um antigo pendente
                return
            }


            // 6. Envia para OCR
            val inputImage = InputImage.fromBitmap(bitmapCopyForListeners, 0)
            Log.d(TAG, "Iniciando OCR '$regionTag' (${bitmapCopyForListeners.width}x${bitmapCopyForListeners.height})...")

            textRecognizer.process(inputImage)
                .addOnSuccessListener listener@{ visionText ->
                    val extractedText = visionText.text
                    Log.d(TAG,"OCR '$regionTag' SUCESSO (len=${extractedText.length})")

                    // Limpa o bitmap ANTERIOR que estava pendente para salvar, antes de processar o novo resultado
                    // Faz isso na thread principal ou na do handler? Melhor na do handler para evitar concorrência.
                    processingHandler.post { recyclePotentialSaveBitmap("novo resultado OCR recebido") }


                    if (extractedText.isBlank()) {
                        Log.v(TAG, "Texto OCR vazio para '$regionTag'.")
                        // Não prepara para salvar, mas o listener continua para o onComplete
                        return@listener
                    }

                    // Análise do texto extraído
                    val currentTime = System.currentTimeMillis()
                    val offerData = imageAnalysisUtils.analyzeTextForOffer(visionText, bitmapCopyForListeners.width, bitmapCopyForListeners.height) // <<< PASSA visionText e dimensões

                    if (offerData == null || !offerData.isValid()) {
                        Log.v(TAG,"'$regionTag': Texto OCR não parece ser uma oferta válida.")
                        return@listener // Não é uma oferta válida
                    }

                    // --- Lógica interna de bloqueio rápido ---
                    // Evita enviar ofertas muito repetidas rapidamente para o OfferManager
                    val offerSignature = createOfferSignature(offerData)
                    val timeSinceLastOfferProc = currentTime - lastOfferDetectedTime
                    if (timeSinceLastOfferProc < OFFER_PROCESSING_LOCK_PERIOD_MS && offerSignature == lastDetectedOfferSignature) {
                        Log.d(TAG, "Oferta '$regionTag' ignorada (Bloqueio Rápido Interno): Assinatura repetida muito rápido.");
                        return@listener // Ignora
                    }
                    // Atualiza o tempo/assinatura deste bloqueio interno
                    lastOfferDetectedTime = currentTime
                    lastDetectedOfferSignature = offerSignature
                    // --- Fim do bloqueio interno ---


                    // !!! OFERTA POTENCIALMENTE VÁLIDA ENCONTRADA !!!

                    // PREPARA O BITMAP PARA SER SALVO (SE A OPÇÃO ESTIVER ATIVA)
                    if (shouldSaveScreenshots) {
                        Log.d(TAG, ">>> Preparando bitmap ($regionTag) para potencial salvamento futuro...")
                        // Guarda uma NOVA cópia do bitmap que foi para o listener
                        try {
                            lastBitmapForPotentialSave = bitmapCopyForListeners.copy(bitmapCopyForListeners.config ?: Bitmap.Config.ARGB_8888, false)
                            // lastOfferSignatureForPotentialSave = offerSignature // Guarda assinatura associada (opcional)
                        } catch (e: Exception) {
                            Log.e(TAG, "Erro ao COPIAR bitmap para salvamento pendente ($regionTag): ${e.message}", e)
                            lastBitmapForPotentialSave = null // Garante que não há bitmap inválido pendente
                        }
                    } else {
                        // Garante que não há bitmap pendente se a opção está desligada
                        // (Já chamado no início do listener, mas reforça aqui)
                        processingHandler.post { recyclePotentialSaveBitmap("config save desligada no momento da oferta") }
                    }

                    // Envia para o OfferManager para validação final e exibição
                    Log.i(TAG, "!!!! OFERTA POTENCIAL ($regionTag) [${offerSignature}] ENVIANDO para OfferManager... !!!!")
                    mainHandler.post { OfferManager.getInstance(applicationContext).processOffer(offerData) }

                } // Fim addOnSuccessListener
                .addOnFailureListener { e ->
                    Log.e(TAG, "Falha OCR ($regionTag): ${e.message}")
                    // Limpa bitmap pendente se OCR falhar
                    processingHandler.post { recyclePotentialSaveBitmap("falha no OCR") }
                }
                .addOnCompleteListener {
                    // ESTE BLOCO É EXECUTADO SEMPRE (SUCESSO OU FALHA DO OCR)

                    // Recicla a cópia do bitmap que foi USADA PELOS LISTENERS do OCR
                    if (bitmapCopyForListeners?.isRecycled == false) {
                        bitmapCopyForListeners.recycle()
                        //Log.v(TAG,"Bitmap da cópia para listeners ($regionTag) reciclado no onComplete.")
                    } else {
                        //Log.w(TAG,"Bitmap da cópia para listeners ($regionTag) já reciclado antes do onComplete?")
                    }

                    // Libera o lock de processamento DEPOIS que tudo terminou (incluindo listeners)
                    isProcessingImage.compareAndSet(true, false)
                    val duration = System.currentTimeMillis() - timeStart
                    Log.d(TAG,"Processamento completo da região '$regionTag' finalizado em ${duration}ms.")

                } // Fim addOnCompleteListener

        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "Erro de Memória (OOM) em processBitmapRegion ($regionTag)!", oom)
            // Tenta limpar tudo agressivamente
            bitmapCopyForListeners?.recycle()
            bitmapPreprocessed?.recycle()
            bitmapAfterResize?.recycle()
            bitmapToProcess?.recycle()
            processingHandler.post { recyclePotentialSaveBitmap("OOM") }
            isProcessingImage.compareAndSet(true, false) // Libera lock
        } catch (e: Exception) {
            Log.e(TAG, "Erro GERAL durante processamento da região do bitmap ($regionTag): ${e.message}", e)
            // Tenta limpar o que puder
            bitmapCopyForListeners?.recycle()
            bitmapPreprocessed?.recycle()
            bitmapAfterResize?.recycle()
            bitmapToProcess?.recycle()
            processingHandler.post { recyclePotentialSaveBitmap("exceção geral") }
            isProcessingImage.compareAndSet(true, false) // Libera lock
        }
    } // --- Fim de processBitmapRegion ---

    /** Cria uma assinatura única para a oferta baseada nos seus dados numéricos */
    private fun createOfferSignature(offerData: OfferData): String {
        val v = offerData.value.replace(",",".")
        val pd = offerData.pickupDistance.replace(",", ".").toDoubleOrNull()?.let { String.format(Locale.US, "%.2f", it) } ?: "0"
        val td = offerData.tripDistance.replace(",", ".").toDoubleOrNull()?.let { String.format(Locale.US, "%.2f", it) } ?: "0"
        val pt = offerData.pickupDuration.toIntOrNull()?.toString() ?: "0"
        val tt = offerData.tripDuration.toIntOrNull()?.toString() ?: "0"
        return "v:$v|pd:$pd|td:$td|pt:$pt|tt:$tt"
    }

    /** Função dedicada para reciclar o bitmap pendente de forma segura */
    private fun recyclePotentialSaveBitmap(reason: String = "") {
        val bitmapToRecycle = lastBitmapForPotentialSave
        if (bitmapToRecycle != null && !bitmapToRecycle.isRecycled) {
            Log.d(TAG, "Reciclando bitmap pendente para salvar. Razão: [$reason]")
            bitmapToRecycle.recycle()
        }
        lastBitmapForPotentialSave = null
        // lastOfferSignatureForPotentialSave = null // Limpa assinatura associada (se usar)
    }

    // --- Função para Salvar Screenshot (Chamada via Handler pelo ACTION_SAVE...) ---
    private fun saveScreenshotToGallery(bitmapToSave: Bitmap?, prefix: String) {
        if (bitmapToSave == null || bitmapToSave.isRecycled) {
            Log.w(TAG, "Bitmap nulo ou já reciclado fornecido para saveScreenshotToGallery ($prefix).")
            return // Não pode salvar
        }

        val timeStamp = SimpleDateFormat("yyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
        val fileName = "SmartDriver_${prefix}_${timeStamp}_${screenshotCounter++}.jpg"
        Log.d(TAG, "Iniciando salvamento assíncrono de: $fileName")

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            // Para Android Q (API 29) e superior, usa Scoped Storage
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SmartDriverDebug") // Pasta dedicada
                put(MediaStore.Images.Media.IS_PENDING, 1) // Marca como pendente
            }
        }

        var imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        var success = false

        if (imageUri != null) {
            try {
                contentResolver.openOutputStream(imageUri)?.use { outputStream ->
                    // Comprime e escreve o bitmap no OutputStream
                    bitmapToSave.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                    success = true
                    Log.i(TAG, "Screenshot '$fileName' salvo com sucesso (URI: $imageUri)")
                } ?: Log.e(TAG, "Falha ao abrir OutputStream para URI: $imageUri")
            } catch (e: IOException) {
                Log.e(TAG, "Erro de IO ao salvar screenshot '$fileName': ${e.message}", e)
            } catch (e: Exception) {
                Log.e(TAG, "Erro GERAL ao salvar screenshot '$fileName': ${e.message}", e)
            } finally {
                // Se a operação falhou, tenta remover a entrada pendente do MediaStore
                if (!success) {
                    try {
                        contentResolver.delete(imageUri, null, null)
                        Log.d(TAG,"URI removido devido a falha no salvamento: $imageUri")
                    } catch (deleteEx: Exception) {
                        Log.e(TAG, "Erro ao tentar remover URI pendente após falha: $imageUri", deleteEx)
                    }
                }
            }
        } else {
            Log.e(TAG, "Falha ao criar URI no MediaStore para '$fileName'.")
        }

        // Finaliza o estado pendente no Android Q+ SE teve sucesso
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && imageUri != null && success) {
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0) // Marca como não pendente
            try {
                contentResolver.update(imageUri, contentValues, null, null)
            } catch (e: Exception) {
                Log.e(TAG,"Erro ao atualizar IS_PENDING para 0 para '$fileName': ${e.message}")
                // O arquivo provavelmente ainda está lá, mas o flag pode estar errado.
            }
        }

        // --- RECICLA O BITMAP AQUI ---
        // Faz isso no final da operação de salvamento (dentro do handler)
        // independentemente do sucesso, pois a tentativa de uso acabou.
        if (!bitmapToSave.isRecycled) {
            bitmapToSave.recycle()
            Log.v(TAG,"Bitmap ($prefix) reciclado no final da função saveScreenshotToGallery.")
        }
    } // --- Fim saveScreenshotToGallery ---


    // --- Funções de Notificação (inalteradas) ---
    private fun createNotification(contentText: String): Notification {
        createNotificationChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SmartDriver")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_smartdriver) // Ícone Monocromático para status bar
            .setOngoing(true) // Serviço em primeiro plano
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Prioridade baixa para não ser intrusivo
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
                NotificationManager.IMPORTANCE_LOW // Importância baixa
            ).apply {
                description = "Notificação persistente do serviço de captura SmartDriver"
                enableLights(false)
                enableVibration(false)
                setShowBadge(false) // Não mostra badge no ícone da app
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            try {
                notificationManager.createNotificationChannel(channel)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao criar canal de notificação: ${e.message}")
            }
        }
    }

    // --- onBind (Serviço não vinculado) ---
    override fun onBind(intent: Intent?): IBinder? {
        return null // Não permite vinculação
    }

    // --- onDestroy (Limpeza Final) ---
    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, ">>> Serviço de Captura DESTRUÍDO <<<");
        isRunning.set(false) // Atualiza o estado estático

        // Garante que o bitmap pendente seja limpo
        // Usa o handler para garantir que a limpeza ocorra na thread correta se ela ainda estiver ativa
        if (::processingThread.isInitialized && processingThread.isAlive) {
            processingHandler.post { recyclePotentialSaveBitmap("onDestroy") }
        } else {
            recyclePotentialSaveBitmap("onDestroy - thread inativa") // Tenta reciclar na thread atual se a outra morreu
        }


        stopScreenCaptureInternal() // Para projeção, virtual display, etc.

        // Finaliza a thread de processamento de forma segura
        if (::processingThread.isInitialized && processingThread.isAlive){
            try {
                processingThread.quitSafely() // Tenta finalizar a thread de forma segura
                // Espera um pouco para a thread realmente terminar (opcional, mas pode ajudar)
                processingThread.join(500)
                Log.d(TAG,"ProcessingThread finalizada no onDestroy (quitSafely).")
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrompido ao esperar pela finalização da processingThread.")
                Thread.currentThread().interrupt() // Restaura o status de interrupção
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao finalizar processingThread: ${e.message}", e)
            }
        }

        // Remove notificação foreground
        try {
            stopForeground(STOP_FOREGROUND_REMOVE) // Remove a notificação
        } catch (e: Exception){
            Log.e(TAG, "Erro ao remover notificação foreground: ${e.message}")
        }
    } // Fim onDestroy
}