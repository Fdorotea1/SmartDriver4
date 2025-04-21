package com.example.smartdriver

import android.Manifest
import android.app.Activity
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
import com.example.smartdriver.databinding.ActivityMainBinding // Garanta que este import está correto
import com.example.smartdriver.overlay.OverlayService // Import Service

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var accessibilitySettingsLauncher: ActivityResultLauncher<Intent>
    private lateinit var overlaySettingsLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var sharedPreferences: SharedPreferences

    companion object {
        internal const val TAG = "MainActivity"
        internal const val PREFS_NAME = "SmartDriverPrefs"
        internal const val KEY_APP_ACTIVE = "appActive"
        internal const val KEY_SAVE_IMAGES = "save_images"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Log.d(TAG, "onCreate")

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupResultLaunchers()
        setupButtonClickListeners() // Configura TODOS os listeners, incluindo o do histórico
        setupSwitches()
        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume - Atualizando UI")
        // Atualiza a UI sempre que a activity volta ao foco
        updatePermissionStatusUI()
        updateAppStatusUI(sharedPreferences.getBoolean(KEY_APP_ACTIVE, false))
    }

    // Configura os listeners de clique para os botões
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

        // --- >>> Listener do Botão Histórico <<< ---
        binding.historyButton.setOnClickListener {
            Log.d(TAG, "Botão Histórico clicado")
            val intent = Intent(this, HistoryActivity::class.java) // Cria Intent para HistoryActivity
            startActivity(intent)                                  // Abre a HistoryActivity
        }
        // -------------------------------------------

        // Botão Shutdown
        binding.shutdownButton.setOnClickListener {
            Log.d(TAG, "Botão Shutdown clicado")
            stopScreenCaptureService() // Para serviço de captura
            stopOverlayService()       // Para serviço de overlay
            MediaProjectionData.clear() // Limpa dados da captura de tela
            sharedPreferences.edit().putBoolean(KEY_APP_ACTIVE, false).apply() // Marca app como inativo
            binding.appStatusSwitch.isChecked = false // Desliga o switch visualmente
            updatePermissionStatusUI() // Atualiza status (captura ficará inativa)
            updateAppStatusUI(false)   // Atualiza texto status
            finishAffinity() // Fecha todas as activities da app
        }
    }

    // Solicita permissão de captura de tela
    private fun requestScreenCapturePermission() {
        Log.d(TAG, "Solicitando permissão de captura de tela")
        try {
            MediaProjectionData.clear() // Limpa dados antigos
            mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar pedido de captura: ${e.message}")
            Toast.makeText(this, "Não foi possível solicitar permissão de captura.", Toast.LENGTH_SHORT).show()
            updatePermissionStatusUI()
            if (binding.appStatusSwitch.isChecked) { binding.appStatusSwitch.isChecked = false; updateAppStatusUI(false) }
        }
    }

    // Configura os launchers que esperam resultados de outras activities/permissões
    private fun setupResultLaunchers() {
        // Launcher para Configurações de Acessibilidade
        accessibilitySettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            Log.d(TAG, "Retornou das config. acessibilidade - Atualizando UI")
            // Atualiza a UI após um pequeno delay para dar tempo ao sistema
            Handler(Looper.getMainLooper()).postDelayed({ updatePermissionStatusUI() }, 300)
        }
        // Launcher para Configurações de Overlay
        overlaySettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            Log.d(TAG, "Retornou das config. overlay - Atualizando UI")
            Handler(Looper.getMainLooper()).postDelayed({ updatePermissionStatusUI() }, 300)
        }
        // Launcher para Permissão de Captura de Tela (MediaProjection)
        mediaProjectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                Log.d(TAG, "Permissão de captura CONCEDIDA");
                MediaProjectionData.resultCode = result.resultCode;
                MediaProjectionData.resultData = result.data?.clone() as Intent? // Clona o Intent
                Toast.makeText(this, "Permissão de captura concedida!", Toast.LENGTH_SHORT).show()
                if (binding.appStatusSwitch.isChecked) {
                    Log.d(TAG, "Switch ligado, iniciando serviços após obter captura...");
                    startServicesIfReady() // Tenta iniciar os serviços agora
                }
            } else {
                Log.w(TAG, "Permissão de captura NEGADA ou cancelada");
                MediaProjectionData.clear();
                Toast.makeText(this, "Permissão de captura é necessária.", Toast.LENGTH_LONG).show()
                if (binding.appStatusSwitch.isChecked) {
                    binding.appStatusSwitch.isChecked = false;
                    updateAppStatusUI(false)
                }
            }
            updatePermissionStatusUI() // Atualiza status da UI
        }
        // Launcher para Permissão de Notificação (Android 13+)
        notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) { Log.i(TAG, "Permissão POST_NOTIFICATIONS concedida.") }
            else { Log.w(TAG, "Permissão POST_NOTIFICATIONS negada."); Toast.makeText(this, "Notificações são recomendadas.", Toast.LENGTH_LONG).show() }
        }
    }

    // Configura os switches da tela principal
    private fun setupSwitches() {
        val isAppActiveSaved = sharedPreferences.getBoolean(KEY_APP_ACTIVE, false)
        binding.appStatusSwitch.isChecked = isAppActiveSaved
        updateAppStatusUI(isAppActiveSaved)

        val saveImagesEnabled = sharedPreferences.getBoolean(KEY_SAVE_IMAGES, false)
        binding.saveImagesSwitch.isChecked = saveImagesEnabled

        // Listener do Switch Principal (App ON/OFF)
        binding.appStatusSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "Switch App Status alterado: $isChecked");
            sharedPreferences.edit().putBoolean(KEY_APP_ACTIVE, isChecked).apply();
            updateAppStatusUI(isChecked);

            if (isChecked) {
                checkPermissionsAndStartOrRequestCapture() // Verifica e inicia/pede permissão
            } else {
                stopScreenCaptureService(); stopOverlayService(); MediaProjectionData.clear();
                updatePermissionStatusUI();
            }
        }

        // Listener do Switch Salvar Imagens
        binding.saveImagesSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "Switch Salvar Imagens alterado: $isChecked");
            sharedPreferences.edit().putBoolean(KEY_SAVE_IMAGES, isChecked).apply();
            updateScreenCaptureSaveSetting(isChecked); // Envia config para o serviço
            Toast.makeText(this, if (isChecked) "Capturas de oferta serão salvas" else "Capturas NÃO serão salvas", Toast.LENGTH_SHORT).show()
        }
    }

    // Verifica permissões antes de iniciar os serviços
    private fun checkPermissionsAndStartOrRequestCapture() {
        Log.d(TAG, "Verificando permissões para iniciar...")
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        val canDrawOverlays = Settings.canDrawOverlays(this)
        val isCaptureAllowed = MediaProjectionData.resultCode == Activity.RESULT_OK && MediaProjectionData.resultData != null

        if (!isAccessibilityEnabled) {
            Log.w(TAG, "Falha: Acessibilidade INATIVA."); Toast.makeText(this, "Ative o Serviço de Acessibilidade (Item 1).", Toast.LENGTH_SHORT).show(); binding.appStatusSwitch.isChecked = false; updateAppStatusUI(false); return
        }
        if (!canDrawOverlays) {
            Log.w(TAG, "Falha: Overlay NÃO Permitido."); Toast.makeText(this, "Permita o Overlay (Item 2).", Toast.LENGTH_SHORT).show(); binding.appStatusSwitch.isChecked = false; updateAppStatusUI(false); return
        }
        if (!isCaptureAllowed) {
            Log.w(TAG, "Captura AUSENTE. Solicitando..."); Toast.makeText(this, "Permita a captura de tela (Item 3).", Toast.LENGTH_SHORT).show(); requestScreenCapturePermission(); // Pede permissão, não inicia ainda
        }
        else {
            Log.i(TAG, "Permissões OK. Iniciando serviços..."); startServicesIfReady();
        }
    }

    // --- Gerenciamento de Permissões e UI ---
    private fun updatePermissionStatusUI() {
        Log.v(TAG, "Atualizando UI de status das permissões") // Log mais verboso

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

    // Verifica se o serviço de acessibilidade está ativo
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = "$packageName/${SmartDriverAccessibilityService::class.java.name}"
        val enabledServicesSetting = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServicesSetting?.split(':')?.contains(expectedComponentName) ?: false
    }

    // Atualiza o texto ON/OFF do status da app
    private fun updateAppStatusUI(isActive: Boolean) {
        binding.appStatusText.text = getString(if (isActive) R.string.app_status_on else R.string.app_status_off)
        binding.appStatusText.setTextColor(ContextCompat.getColor(this, if (isActive) R.color.black else R.color.gray_inactive))
    }

    // --- Gerenciamento de Serviços ---
    private fun startServicesIfReady() {
        Log.d(TAG, "startServicesIfReady chamado")
        if (!isAccessibilityServiceEnabled() || !Settings.canDrawOverlays(this) || MediaProjectionData.resultCode != Activity.RESULT_OK) {
            Log.e(TAG, "ERRO: Tentativa de iniciar serviços sem permissões OK!")
            binding.appStatusSwitch.isChecked = false; updateAppStatusUI(false); updatePermissionStatusUI(); return
        }
        startOverlayService()
        startScreenCaptureService()
    }

    private fun startScreenCaptureService() {
        if (MediaProjectionData.resultCode != Activity.RESULT_OK || MediaProjectionData.resultData == null) {
            Log.e(TAG, "Dados de projeção inválidos. Não iniciar ScreenCaptureService."); Toast.makeText(this, "Erro interno: Falha na permissão de captura.", Toast.LENGTH_SHORT).show(); binding.appStatusSwitch.isChecked = false; updateAppStatusUI(false); return
        }
        if (ScreenCaptureService.isRunning.get()) {
            Log.d(TAG, "ScreenCaptureService já rodando. Atualizando saveImages."); updateScreenCaptureSaveSetting(binding.saveImagesSwitch.isChecked); return
        }
        Log.d(TAG, "Iniciando ScreenCaptureService...");
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, MediaProjectionData.resultCode); putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, MediaProjectionData.resultData); putExtra(KEY_SAVE_IMAGES, binding.saveImagesSwitch.isChecked)
        }
        try { ContextCompat.startForegroundService(this, intent) }
        catch (e: Exception) { Log.e(TAG, "Erro ao iniciar ScreenCaptureService: ${e.message}", e); Toast.makeText(this, "Falha ao iniciar captura.", Toast.LENGTH_SHORT).show(); binding.appStatusSwitch.isChecked = false; updateAppStatusUI(false); MediaProjectionData.clear(); updatePermissionStatusUI() }
    }

    private fun stopScreenCaptureService() {
        if (!ScreenCaptureService.isRunning.get()) { Log.d(TAG, "ScreenCaptureService já parado."); return }
        Log.d(TAG, "Enviando pedido STOP para ScreenCaptureService...");
        val intent = Intent(this, ScreenCaptureService::class.java).apply { action = ScreenCaptureService.ACTION_STOP_CAPTURE }
        try { stopService(intent) } catch (e: Exception) { Log.e(TAG, "Erro ao parar ScreenCaptureService: ${e.message}", e) }
        Handler(Looper.getMainLooper()).postDelayed({ updatePermissionStatusUI() }, 500) // Atualiza UI após delay
    }

    private fun startOverlayService() {
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Permissão de overlay faltando. Não iniciar OverlayService."); Toast.makeText(this, "Permissão de overlay necessária.", Toast.LENGTH_SHORT).show(); binding.appStatusSwitch.isChecked = false; updateAppStatusUI(false); return
        }
        if (OverlayService.isRunning.get()) { Log.d(TAG, "OverlayService já rodando."); return }
        Log.d(TAG, "Iniciando OverlayService...");
        val intent = Intent(this, OverlayService::class.java)
        try { ContextCompat.startForegroundService(this, intent) }
        catch (e: Exception) { Log.e(TAG, "Erro ao iniciar OverlayService: ${e.message}", e); Toast.makeText(this, "Falha ao iniciar overlay.", Toast.LENGTH_SHORT).show(); binding.appStatusSwitch.isChecked = false; updateAppStatusUI(false) }
    }

    private fun stopOverlayService() {
        if (!OverlayService.isRunning.get()) { Log.d(TAG, "OverlayService já parado."); return }
        Log.d(TAG, "Parando OverlayService...");
        val intent = Intent(this, OverlayService::class.java)
        try { stopService(intent) } catch (e: Exception) { Log.e(TAG, "Erro ao parar OverlayService: ${e.message}", e) }
    }

    // Envia configuração de salvar imagens para o serviço de captura
    private fun updateScreenCaptureSaveSetting(shouldSave: Boolean) {
        if (ScreenCaptureService.isRunning.get()) {
            Log.d(TAG, "Enviando config saveImages ($shouldSave) para ScreenCaptureService");
            val intent = Intent(this, ScreenCaptureService::class.java).apply { action = ScreenCaptureService.ACTION_UPDATE_SETTINGS; putExtra(KEY_SAVE_IMAGES, shouldSave) }
            try { startService(intent) } catch (e: Exception) { Log.e(TAG, "Erro ao enviar UPDATE_SETTINGS: ${e.message}", e) }
        } else { Log.v(TAG,"ScreenCaptureService inativo, config saveImages será usada no próximo início.") }
    }

    // Pede permissões necessárias na inicialização (Notificação no Android 13+)
    private fun requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Solicitando permissão POST_NOTIFICATIONS."); notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        // Permissão de armazenamento não é pedida aqui, tratada via MediaStore/Manifest.
    }
} // Fim da MainActivity