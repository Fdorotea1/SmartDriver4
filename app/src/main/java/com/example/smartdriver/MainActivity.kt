package com.example.smartdriver

import android.Manifest
import android.app.Activity
// --- Imports para BroadcastReceiver ---
import android.content.BroadcastReceiver
import android.content.IntentFilter
// --- Fim Imports ---
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.smartdriver.databinding.ActivityMainBinding
import com.example.smartdriver.overlay.OverlayService // Import OverlayService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var accessibilitySettingsLauncher: ActivityResultLauncher<Intent>
    private lateinit var overlaySettingsLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var sharedPreferences: SharedPreferences

    // --- Receiver para o comando de desligar vindo do Serviço/Menu ---
    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SHUTDOWN_APP) {
                Log.w(TAG, "Recebido broadcast $ACTION_SHUTDOWN_APP. Encerrando MainActivity.")
                // Fecha esta e todas as activities relacionadas da app
                finishAffinity()
            }
        }
    }
    // ---------------------------------------------------------------

    companion object {
        internal const val TAG = "MainActivity"
        internal const val PREFS_NAME = "SmartDriverPrefs"
        internal const val KEY_APP_ACTIVE = "appActive"
        internal const val KEY_SAVE_IMAGES = "save_images"
        // --- Ação para Broadcast de Desligamento ---
        const val ACTION_SHUTDOWN_APP = "com.example.smartdriver.ACTION_SHUTDOWN_APP"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Log.d(TAG, "onCreate")

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupResultLaunchers()
        setupButtonClickListeners() // Configura todos os botões
        setupSwitches()
        requestNeededPermissions()

        // --- Registar o BroadcastReceiver para o shutdown ---
        val intentFilter = IntentFilter(ACTION_SHUTDOWN_APP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // No Android 13+ é recomendado especificar se o receiver é exportado ou não
            registerReceiver(shutdownReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            // Versões anteriores não requerem o flag de exportação explícito
            registerReceiver(shutdownReceiver, intentFilter)
        }
        Log.d(TAG, "Shutdown receiver registrado.")
        // -----------------------------------------------------
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume - Atualizando UI")
        updatePermissionStatusUI()
        updateAppStatusUI(sharedPreferences.getBoolean(KEY_APP_ACTIVE, false))
    }

    // --- Desregistar o Receiver ao destruir a Activity ---
    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(shutdownReceiver)
            Log.d(TAG, "Shutdown receiver desregistrado.")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Erro ao desregistrar shutdown receiver (já desregistrado?): ${e.message}")
        }
    }
    // -------------------------------------------------

    // --- Configuração dos Listeners de Botão ---
    private fun setupButtonClickListeners() {
        // Botão Acessibilidade
        binding.accessibilityButton.setOnClickListener {
            Log.d(TAG, "Botão Acessibilidade clicado")
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                Toast.makeText(this, "Encontre 'SmartDriver Service' e ative-o", Toast.LENGTH_LONG).show()
                accessibilitySettingsLauncher.launch(intent)
            } catch (e: Exception) { Log.e(TAG, "Erro ao abrir config. acessibilidade: ${e.message}"); Toast.makeText(this, "Não foi possível abrir as configurações.", Toast.LENGTH_SHORT).show() }
        }

        // Botão Overlay
        binding.overlayButton.setOnClickListener {
            Log.d(TAG, "Botão Overlay clicado")
            if (!Settings.canDrawOverlays(this)) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    overlaySettingsLauncher.launch(intent)
                } catch (e: Exception) { Log.e(TAG, "Erro ao abrir config. overlay: ${e.message}"); Toast.makeText(this, "Não foi possível abrir as configurações.", Toast.LENGTH_SHORT).show() }
            } else { Toast.makeText(this, "Permissão de overlay já concedida", Toast.LENGTH_SHORT).show() }
        }

        // Botão Configurações
        binding.settingsButton.setOnClickListener {
            Log.d(TAG, "Botão Configurações clicado")
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        // Botão Histórico
        binding.historyButton.setOnClickListener {
            Log.d(TAG, "Botão Histórico clicado")
            val intent = Intent(this, HistoryActivity::class.java)
            startActivity(intent)
        }

        // Botão Shutdown (Mantém a lógica original de parar tudo aqui)
        binding.shutdownButton.setOnClickListener {
            Log.w(TAG, "Botão Shutdown clicado na MainActivity. Parando tudo...")
            stopScreenCaptureService()
            stopOverlayService() // O onDestroy do OverlayService removerá os overlays
            MediaProjectionData.clear()
            sharedPreferences.edit().putBoolean(KEY_APP_ACTIVE, false).apply()
            binding.appStatusSwitch.isChecked = false // Garante que o switch reflete o estado
            updatePermissionStatusUI()
            updateAppStatusUI(false)
            finishAffinity() // Fecha a app
        }
    }

    // --- Função para pedir permissão de Captura ---
    private fun requestScreenCapturePermission() {
        Log.d(TAG, "Solicitando permissão de captura de tela")
        try {
            MediaProjectionData.clear() // Limpa dados de projeção anteriores
            mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar pedido de captura: ${e.message}")
            Toast.makeText(this, "Não foi possível solicitar permissão de captura.", Toast.LENGTH_SHORT).show()
            updatePermissionStatusUI()
            // Garante que o switch desliga se houver erro
            if (binding.appStatusSwitch.isChecked) {
                binding.appStatusSwitch.isChecked = false; updateAppStatusUI(false)
            }
        }
    }

    // --- Configuração dos ActivityResultLaunchers ---
    private fun setupResultLaunchers() {
        // Acessibilidade
        accessibilitySettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            Log.d(TAG, "Retornou das config. acessibilidade - Atualizando UI")
            // Atualiza UI após voltar das configurações
            Handler(Looper.getMainLooper()).postDelayed({ updatePermissionStatusUI() }, 300)
        }
        // Overlay
        overlaySettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            Log.d(TAG, "Retornou das config. overlay - Atualizando UI")
            Handler(Looper.getMainLooper()).postDelayed({ updatePermissionStatusUI() }, 300)
        }
        // Media Projection (Captura de Tela)
        mediaProjectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                Log.d(TAG, "Permissão de captura CONCEDIDA");
                MediaProjectionData.resultCode = result.resultCode;
                MediaProjectionData.resultData = result.data?.clone() as Intent? // Clona o Intent
                Toast.makeText(this, "Permissão de captura concedida!", Toast.LENGTH_SHORT).show()
                // Se o switch principal estiver ligado, tenta iniciar os serviços agora
                if (binding.appStatusSwitch.isChecked) {
                    Log.d(TAG, "Switch ligado, iniciando serviços após obter captura...");
                    startServicesIfReady()
                }
            } else {
                Log.w(TAG, "Permissão de captura NEGADA ou cancelada");
                MediaProjectionData.clear(); // Limpa dados se negado
                Toast.makeText(this, "Permissão de captura é necessária.", Toast.LENGTH_LONG).show()
                // Desliga o switch se a permissão for negada enquanto estava ativo
                if (binding.appStatusSwitch.isChecked) {
                    binding.appStatusSwitch.isChecked = false;
                    updateAppStatusUI(false)
                }
            }
            updatePermissionStatusUI() // Atualiza o status da UI
        }
        // Notificação (Android 13+)
        notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) { Log.i(TAG, "Permissão POST_NOTIFICATIONS concedida.") }
            else { Log.w(TAG, "Permissão POST_NOTIFICATIONS negada."); Toast.makeText(this, "Notificações são recomendadas.", Toast.LENGTH_LONG).show() }
        }
    }

    // --- Configuração dos Switches ---
    private fun setupSwitches() {
        val isAppActiveSaved = sharedPreferences.getBoolean(KEY_APP_ACTIVE, false)
        binding.appStatusSwitch.isChecked = isAppActiveSaved
        updateAppStatusUI(isAppActiveSaved)

        val saveImagesEnabled = sharedPreferences.getBoolean(KEY_SAVE_IMAGES, false)
        binding.saveImagesSwitch.isChecked = saveImagesEnabled

        // Listener Switch Principal ON/OFF
        binding.appStatusSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "Switch App Status alterado: $isChecked");
            sharedPreferences.edit().putBoolean(KEY_APP_ACTIVE, isChecked).apply();
            updateAppStatusUI(isChecked);

            if (isChecked) {
                checkPermissionsAndStartOrRequestCapture() // Tenta iniciar
            } else {
                // Desliga tudo
                stopScreenCaptureService(); stopOverlayService(); MediaProjectionData.clear();
                updatePermissionStatusUI(); // Atualiza UI (captura ficará inativa)
            }
        }

        // Listener Switch Salvar Imagens
        binding.saveImagesSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "Switch Salvar Imagens alterado: $isChecked");
            sharedPreferences.edit().putBoolean(KEY_SAVE_IMAGES, isChecked).apply();
            updateScreenCaptureSaveSetting(isChecked); // Envia config para o serviço
            Toast.makeText(this, if (isChecked) "Capturas de oferta serão salvas" else "Capturas NÃO serão salvas", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Verifica Permissões e Inicia/Pede Captura ---
    private fun checkPermissionsAndStartOrRequestCapture() {
        Log.d(TAG, "Verificando permissões para iniciar...")
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        val canDrawOverlays = Settings.canDrawOverlays(this)
        val isCaptureAllowed = MediaProjectionData.resultCode == Activity.RESULT_OK && MediaProjectionData.resultData != null

        // Verifica na ordem
        if (!isAccessibilityEnabled) {
            Log.w(TAG, "Falha: Acessibilidade INATIVA."); Toast.makeText(this, "Ative o Serviço de Acessibilidade (Item 1).", Toast.LENGTH_SHORT).show(); binding.appStatusSwitch.isChecked = false; updateAppStatusUI(false); return
        }
        if (!canDrawOverlays) {
            Log.w(TAG, "Falha: Overlay NÃO Permitido."); Toast.makeText(this, "Permita o Overlay (Item 2).", Toast.LENGTH_SHORT).show(); binding.appStatusSwitch.isChecked = false; updateAppStatusUI(false); return
        }
        if (!isCaptureAllowed) {
            Log.w(TAG, "Captura AUSENTE. Solicitando..."); Toast.makeText(this, "Permita a captura de tela (Item 3).", Toast.LENGTH_SHORT).show(); requestScreenCapturePermission(); // Pede permissão
        }
        else {
            Log.i(TAG, "Permissões OK. Iniciando serviços..."); startServicesIfReady(); // Inicia se tudo OK
        }
    }

    // --- Atualização da UI de Status das Permissões ---
    private fun updatePermissionStatusUI() {
        Log.v(TAG, "Atualizando UI de status das permissões")

        // Acessibilidade
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        binding.accessibilityStatusTextView.text = "1. Acessibilidade: ${if (isAccessibilityEnabled) "Ativo ✅" else "Inativo ❌"}"
        binding.accessibilityStatusTextView.setTextColor(ContextCompat.getColor(this, if (isAccessibilityEnabled) R.color.black else R.color.gray_inactive))
        binding.accessibilityButton.isEnabled = !isAccessibilityEnabled
        binding.accessibilityButton.alpha = if (isAccessibilityEnabled) 0.5f else 1.0f

        // Overlay
        val canDrawOverlays = Settings.canDrawOverlays(this)
        binding.overlayStatusTextView.text = "2. Overlay: ${if (canDrawOverlays) "Permitido ✅" else "Não Permitido ❌"}"
        binding.overlayStatusTextView.setTextColor(ContextCompat.getColor(this, if (canDrawOverlays) R.color.black else R.color.gray_inactive))
        binding.overlayButton.isEnabled = !canDrawOverlays
        binding.overlayButton.alpha = if (canDrawOverlays) 0.5f else 1.0f

        // Captura
        val isCaptureAllowed = MediaProjectionData.resultCode == Activity.RESULT_OK && MediaProjectionData.resultData != null
        binding.captureStatusTextView.text = "3. Captura: ${if (isCaptureAllowed) "Permitida (Sessão) ✅" else "Não Permitida ❌"}"
        binding.captureStatusTextView.setTextColor(ContextCompat.getColor(this, if (isCaptureAllowed) R.color.black else R.color.gray_inactive))
    }

    // --- Verifica se o Serviço de Acessibilidade está Ativo ---
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = "$packageName/${SmartDriverAccessibilityService::class.java.name}"
        val enabledServicesSetting = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServicesSetting?.split(':')?.contains(expectedComponentName) ?: false
    }

    // --- Atualiza o Texto de Status ON/OFF ---
    private fun updateAppStatusUI(isActive: Boolean) {
        binding.appStatusText.text = getString(if (isActive) R.string.app_status_on else R.string.app_status_off)
        binding.appStatusText.setTextColor(ContextCompat.getColor(this, if (isActive) R.color.black else R.color.gray_inactive))
    }

    // --- Gerenciamento de Serviços ---
    private fun startServicesIfReady() {
        Log.d(TAG, "startServicesIfReady chamado")
        // Checagem final antes de iniciar
        if (!isAccessibilityServiceEnabled() || !Settings.canDrawOverlays(this) || MediaProjectionData.resultCode != Activity.RESULT_OK) {
            Log.e(TAG, "ERRO: Tentativa de iniciar serviços sem permissões OK!")
            binding.appStatusSwitch.isChecked = false; updateAppStatusUI(false); updatePermissionStatusUI(); return
        }
        startOverlayService() // Inicia Overlay primeiro (gerencia menus/gestos)
        startScreenCaptureService() // Inicia Captura depois
    }

    private fun startScreenCaptureService() {
        if (MediaProjectionData.resultCode != Activity.RESULT_OK || MediaProjectionData.resultData == null) {
            Log.e(TAG, "Dados projeção inválidos. Não iniciar ScreenCaptureService."); Toast.makeText(this, "Erro permissão captura.", Toast.LENGTH_SHORT).show(); binding.appStatusSwitch.isChecked = false; updateAppStatusUI(false); return
        }
        if (ScreenCaptureService.isRunning.get()) {
            Log.d(TAG, "ScreenCaptureService já rodando. Atualizando saveImages."); updateScreenCaptureSaveSetting(binding.saveImagesSwitch.isChecked); return
        }
        Log.d(TAG, "Iniciando ScreenCaptureService...");
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, MediaProjectionData.resultCode); putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, MediaProjectionData.resultData); putExtra(KEY_SAVE_IMAGES, binding.saveImagesSwitch.isChecked)
        }
        try { ContextCompat.startForegroundService(this, intent) }
        catch (e: Exception) { Log.e(TAG, "Erro iniciar ScreenCaptureService: ${e.message}", e); Toast.makeText(this, "Falha iniciar captura.", Toast.LENGTH_SHORT).show(); binding.appStatusSwitch.isChecked = false; updateAppStatusUI(false); MediaProjectionData.clear(); updatePermissionStatusUI() }
    }

    private fun stopScreenCaptureService() {
        if (!ScreenCaptureService.isRunning.get()) { Log.d(TAG, "ScreenCaptureService já parado."); return }
        Log.d(TAG, "Parando ScreenCaptureService...");
        val intent = Intent(this, ScreenCaptureService::class.java).apply { action = ScreenCaptureService.ACTION_STOP_CAPTURE }
        try { stopService(intent) } catch (e: Exception) { Log.e(TAG, "Erro parar ScreenCaptureService: ${e.message}", e) }
        // Não precisa de delay aqui, o estado da permissão não muda ao parar o serviço
    }

    private fun startOverlayService() {
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Permissão overlay faltando. Não iniciar OverlayService."); Toast.makeText(this, "Permissão overlay necessária.", Toast.LENGTH_SHORT).show(); binding.appStatusSwitch.isChecked = false; updateAppStatusUI(false); return
        }
        if (OverlayService.isRunning.get()) { Log.d(TAG, "OverlayService já rodando."); return }
        Log.d(TAG, "Iniciando OverlayService...");
        val intent = Intent(this, OverlayService::class.java)
        try { ContextCompat.startForegroundService(this, intent) }
        catch (e: Exception) { Log.e(TAG, "Erro iniciar OverlayService: ${e.message}", e); Toast.makeText(this, "Falha iniciar overlay.", Toast.LENGTH_SHORT).show(); binding.appStatusSwitch.isChecked = false; updateAppStatusUI(false) }
    }

    private fun stopOverlayService() {
        if (!OverlayService.isRunning.get()) { Log.d(TAG, "OverlayService já parado."); return }
        Log.d(TAG, "Parando OverlayService...");
        val intent = Intent(this, OverlayService::class.java)
        try { stopService(intent) } catch (e: Exception) { Log.e(TAG, "Erro parar OverlayService: ${e.message}", e) }
    }

    // --- Atualiza Configuração de Salvar Imagens no Serviço ---
    private fun updateScreenCaptureSaveSetting(shouldSave: Boolean) {
        if (ScreenCaptureService.isRunning.get()) {
            Log.d(TAG, "Enviando config saveImages ($shouldSave) para ScreenCaptureService");
            val intent = Intent(this, ScreenCaptureService::class.java).apply { action = ScreenCaptureService.ACTION_UPDATE_SETTINGS; putExtra(KEY_SAVE_IMAGES, shouldSave) }
            try { startService(intent) } catch (e: Exception) { Log.e(TAG, "Erro enviar UPDATE_SETTINGS: ${e.message}", e) }
        } else { Log.v(TAG,"ScreenCaptureService inativo, config saveImages será usada no próximo início.") }
    }

    // --- Pede Permissões Necessárias (Notificação) ---
    private fun requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Solicitando permissão POST_NOTIFICATIONS."); notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
} // Fim da MainActivity