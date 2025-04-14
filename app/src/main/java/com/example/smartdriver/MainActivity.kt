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
import android.view.accessibility.AccessibilityManager // Ainda pode ser útil para logs futuros
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.smartdriver.databinding.ActivityMainBinding
import com.example.smartdriver.MediaProjectionData
import com.example.smartdriver.OfferManager
import com.example.smartdriver.ScreenCaptureService
import com.example.smartdriver.overlay.OverlayService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var accessibilitySettingsLauncher: ActivityResultLauncher<Intent>
    private lateinit var overlaySettingsLauncher: ActivityResultLauncher<Intent>
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var offerManager: OfferManager

    companion object {
        internal const val TAG = "MainActivity"
        internal const val PREFS_NAME = "SmartDriverPrefs"
        internal const val KEY_APP_ACTIVE = "appActive"
        internal const val KEY_SAVE_IMAGES = ScreenCaptureService.KEY_SAVE_IMAGES
        private const val ACTION_STOP_CAPTURE_SERVICE = "com.example.smartdriver.STOP_CAPTURE_SERVICE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Log.d(TAG, "onCreate")

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        offerManager = OfferManager.getInstance(applicationContext)

        setupButtonClickListeners()
        setupResultLaunchers()
        setupSwitches()

        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume - Atualizando UI de permissões")
        // A atualização da UI agora acontece aqui, após o retorno das configurações
        updatePermissionStatusUI()
        updateAppStatusUI(sharedPreferences.getBoolean(KEY_APP_ACTIVE, false))
    }

    // --- Configuração Inicial ---

    private fun setupButtonClickListeners() {
        binding.accessibilityButton.setOnClickListener {
            Log.d(TAG, "Botão Acessibilidade clicado")
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            Toast.makeText(this, "Encontre 'SmartDriver Service' e ative-o", Toast.LENGTH_LONG).show()
            accessibilitySettingsLauncher.launch(intent)
        }

        binding.overlayButton.setOnClickListener {
            Log.d(TAG, "Botão Overlay clicado")
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                overlaySettingsLauncher.launch(intent)
            } else {
                Toast.makeText(this, "Permissão de overlay já concedida", Toast.LENGTH_SHORT).show()
            }
        }

        binding.captureButton.setOnClickListener {
            Log.d(TAG, "Botão Captura clicado")
            if (MediaProjectionData.resultCode == Activity.RESULT_CANCELED) {
                requestScreenCapturePermission()
            } else {
                Toast.makeText(this, "Permissão de captura já concedida nesta sessão", Toast.LENGTH_SHORT).show()
                updatePermissionStatusUI()
            }
        }

        binding.settingsButton.setOnClickListener {
            Log.d(TAG, "Botão Configurações clicado")
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        binding.shutdownButton.setOnClickListener {
            Log.d(TAG, "Botão Shutdown clicado")
            stopScreenCaptureService()
            stopOverlayService()
            sharedPreferences.edit().putBoolean(KEY_APP_ACTIVE, false).apply()
            binding.appStatusSwitch.isChecked = false
            finishAffinity()
        }
    }

    private fun requestScreenCapturePermission() {
        Log.d(TAG, "Solicitando permissão de captura de tela via mediaProjectionLauncher")
        try {
            MediaProjectionData.clear()
            mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar pedido de captura: ${e.message}")
            Toast.makeText(this, "Não foi possível solicitar permissão de captura.", Toast.LENGTH_SHORT).show()
            updatePermissionStatusUI()
            binding.appStatusSwitch.isChecked = false
        }
    }


    private fun setupResultLaunchers() {
        accessibilitySettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            Log.d(TAG, "Retornou das configurações de acessibilidade")
            // A verificação e atualização da UI será feita automaticamente no onResume()
        }

        overlaySettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            Log.d(TAG, "Retornou das configurações de overlay")
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Permissão de overlay não concedida.", Toast.LENGTH_SHORT).show()
            }
            // A verificação e atualização da UI será feita automaticamente no onResume()
        }

        mediaProjectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                Log.d(TAG, "Permissão de captura de tela CONCEDIDA (via launcher)")
                MediaProjectionData.resultCode = result.resultCode
                MediaProjectionData.resultData = result.data?.clone() as? Intent
                Toast.makeText(this, "Permissão de captura concedida!", Toast.LENGTH_SHORT).show()
                if (binding.appStatusSwitch.isChecked) {
                    Log.d(TAG, "Permissão obtida, tentando iniciar serviços agora...")
                    startServicesIfReady()
                } else {
                    Log.d(TAG, "Permissão obtida, mas switch está desligado.")
                }
            } else {
                Log.w(TAG, "Permissão de captura de tela NEGADA ou cancelada (via launcher)")
                MediaProjectionData.clear()
                Toast.makeText(this, "Permissão de captura é necessária para o SmartDriver funcionar.", Toast.LENGTH_LONG).show()
                if (binding.appStatusSwitch.isChecked) {
                    binding.appStatusSwitch.isChecked = false
                }
            }
            updatePermissionStatusUI() // Atualiza UI da captura especificamente
        }
    }

    private fun setupSwitches() {
        val isAppActiveSaved = sharedPreferences.getBoolean(KEY_APP_ACTIVE, false)
        binding.appStatusSwitch.isChecked = isAppActiveSaved
        updateAppStatusUI(isAppActiveSaved)

        val saveImagesEnabled = sharedPreferences.getBoolean(KEY_SAVE_IMAGES, false)
        binding.saveImagesSwitch.isChecked = saveImagesEnabled
        updateScreenCaptureSaveSetting(saveImagesEnabled)

        binding.appStatusSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "Switch App Status alterado para: $isChecked")
            sharedPreferences.edit().putBoolean(KEY_APP_ACTIVE, isChecked).apply()
            updateAppStatusUI(isChecked)

            if (isChecked) {
                checkPermissionsAndStartOrRequestCapture()
            } else {
                stopScreenCaptureService()
                stopOverlayService()
            }
        }

        binding.saveImagesSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "Switch Salvar Imagens alterado para: $isChecked")
            sharedPreferences.edit().putBoolean(KEY_SAVE_IMAGES, isChecked).apply()
            updateScreenCaptureSaveSetting(isChecked)
            Toast.makeText(this, if (isChecked) "Capturas de tela serão salvas" else "Capturas de tela NÃO serão salvas", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissionsAndStartOrRequestCapture() {
        Log.d(TAG, "Verificando permissões para iniciar...")
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        val canDrawOverlays = Settings.canDrawOverlays(this)
        val isCaptureAllowed = MediaProjectionData.resultCode == Activity.RESULT_OK && MediaProjectionData.resultData != null

        if (!isAccessibilityEnabled) {
            Log.w(TAG, "Falha ao iniciar: Serviço de acessibilidade INATIVO.")
            Toast.makeText(this, "Ative o Serviço de Acessibilidade primeiro.", Toast.LENGTH_SHORT).show()
            binding.appStatusSwitch.isChecked = false; return
        }
        if (!canDrawOverlays) {
            Log.w(TAG, "Falha ao iniciar: Permissão de overlay AUSENTE.")
            Toast.makeText(this, "Permita o Overlay sobre outros apps.", Toast.LENGTH_SHORT).show()
            binding.appStatusSwitch.isChecked = false; return
        }

        if (!isCaptureAllowed) {
            Log.w(TAG, "Permissão de captura AUSENTE ou inválida. Solicitando novamente...")
            Toast.makeText(this, "É necessário permitir a captura de tela.", Toast.LENGTH_SHORT).show()
            requestScreenCapturePermission()
        } else {
            Log.i(TAG, "Todas as permissões OK. Iniciando serviços diretamente.")
            startServicesIfReady()
        }
    }

    // --- Gerenciamento de Permissões e UI ---

    private fun updatePermissionStatusUI() {
        Log.d(TAG, "Atualizando UI de permissões")

        // --- Acessibilidade ---
        val isAccessibilityEnabled = isAccessibilityServiceEnabled() // Chama a função atualizada
        binding.accessibilityStatusTextView.text = "1. Acessibilidade: ${if (isAccessibilityEnabled) "Ativo" else "Inativo"}"
        binding.accessibilityStatusTextView.setTextColor(ContextCompat.getColor(this, if (isAccessibilityEnabled) R.color.black else R.color.gray_inactive))
        binding.accessibilityButton.isEnabled = !isAccessibilityEnabled
        binding.accessibilityButton.alpha = if (isAccessibilityEnabled) 0.5f else 1.0f

        // --- Overlay ---
        val canDrawOverlays = Settings.canDrawOverlays(this)
        binding.overlayStatusTextView.text = "2. Overlay: ${if (canDrawOverlays) "Permitido" else "Não Permitido"}"
        binding.overlayStatusTextView.setTextColor(ContextCompat.getColor(this, if (canDrawOverlays) R.color.black else R.color.gray_inactive))
        binding.overlayButton.isEnabled = !canDrawOverlays
        binding.overlayButton.alpha = if (canDrawOverlays) 0.5f else 1.0f

        // --- Captura ---
        val isCaptureAllowed = MediaProjectionData.resultCode == Activity.RESULT_OK && MediaProjectionData.resultData != null
        binding.captureStatusTextView.text = "3. Captura: ${if (isCaptureAllowed) "Permitida" else "Não Permitida"}"
        binding.captureStatusTextView.setTextColor(ContextCompat.getColor(this, if (isCaptureAllowed) R.color.black else R.color.gray_inactive))
        binding.captureButton.isEnabled = !isCaptureAllowed
        binding.captureButton.alpha = if (isCaptureAllowed) 0.5f else 1.0f

        Log.d(TAG, "Status Permissões UI: Acessibilidade=$isAccessibilityEnabled, Overlay=$canDrawOverlays, Captura=$isCaptureAllowed")
    }

    // --- Função isAccessibilityServiceEnabled ATUALIZADA ---
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = "$packageName/${SmartDriverAccessibilityService::class.java.name}"
        val enabledServicesSetting = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)

        Log.d(TAG, "Verificando Acessibilidade:")
        Log.d(TAG, " - Esperado: $expectedComponentName")
        Log.d(TAG, " - Habilitados (Settings.Secure): $enabledServicesSetting")

        if (enabledServicesSetting == null) {
            Log.w(TAG, " - String de serviços habilitados é nula.")
            return false
        }

        // Verifica se o nome EXATO do componente existe na lista separada por ':'
        val enabledServices = enabledServicesSetting.split(':').filter { it.isNotEmpty() }.toSet()
        val isEnabled = enabledServices.contains(expectedComponentName)

        Log.d(TAG, " - Está habilitado? $isEnabled")
        return isEnabled
    }
    // -----------------------------------------------------


    private fun updateAppStatusUI(isActive: Boolean) {
        if (isActive) {
            binding.appStatusText.text = getString(R.string.app_status_on)
            binding.appStatusText.setTextColor(ContextCompat.getColor(this, R.color.black))
        } else {
            binding.appStatusText.text = getString(R.string.app_status_off)
            binding.appStatusText.setTextColor(ContextCompat.getColor(this, R.color.gray_inactive))
        }
    }

    // --- Gerenciamento de Serviços ---

    private fun startServicesIfReady() {
        Log.d(TAG, "startServicesIfReady chamado (presume-se permissões OK)")
        val canDrawOverlays = Settings.canDrawOverlays(this)
        val isCaptureAllowed = MediaProjectionData.resultCode == Activity.RESULT_OK && MediaProjectionData.resultData != null
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()

        if(isAccessibilityEnabled && canDrawOverlays && isCaptureAllowed) {
            startOverlayService()
            startScreenCaptureService()
        } else {
            Log.e(TAG, "startServicesIfReady chamado mas as permissões não estão OK! Acessibilidade=$isAccessibilityEnabled, Overlay=$canDrawOverlays, Captura=$isCaptureAllowed")
            binding.appStatusSwitch.isChecked = false
        }
    }


    private fun startScreenCaptureService() {
        if (MediaProjectionData.resultCode != Activity.RESULT_OK || MediaProjectionData.resultData == null) {
            Log.e(TAG, "Tentativa de iniciar ScreenCaptureService sem dados de projeção válidos.")
            Toast.makeText(this, "Erro interno: Falha ao obter permissão de captura.", Toast.LENGTH_SHORT).show()
            binding.appStatusSwitch.isChecked = false
            return
        }
        if (ScreenCaptureService.isRunning.get()) {
            Log.d(TAG, "ScreenCaptureService já está rodando.")
            updateScreenCaptureSaveSetting(binding.saveImagesSwitch.isChecked)
            return
        }
        Log.d(TAG, "Iniciando ScreenCaptureService...")
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, MediaProjectionData.resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, MediaProjectionData.resultData)
            putExtra(ScreenCaptureService.KEY_SAVE_IMAGES, binding.saveImagesSwitch.isChecked)
        }
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar ScreenCaptureService: ${e.message}")
            Toast.makeText(this, "Falha ao iniciar serviço de captura.", Toast.LENGTH_SHORT).show()
            binding.appStatusSwitch.isChecked = false
        }
    }

    private fun stopScreenCaptureService() {
        if (!ScreenCaptureService.isRunning.get()) {
            Log.d(TAG, "ScreenCaptureService já está parado.")
            // Atualiza a UI mesmo se já parado, pois MediaProjectionData pode ter sido limpo externamente
            updatePermissionStatusUI()
            return
        }
        Log.d(TAG, "Parando ScreenCaptureService...")
        val intent = Intent(this, ScreenCaptureService::class.java)
        intent.action = ScreenCaptureService.ACTION_STOP_CAPTURE
        stopService(intent)
        Handler(Looper.getMainLooper()).postDelayed({
            Log.d(TAG, "Atualizando UI após pedido de paragem do ScreenCaptureService.")
            updatePermissionStatusUI()
        }, 500)
    }

    private fun startOverlayService() {
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Tentativa de iniciar OverlayService sem permissão.")
            Toast.makeText(this, "Permissão de overlay necessária.", Toast.LENGTH_SHORT).show()
            binding.appStatusSwitch.isChecked = false
            return
        }
        if (OverlayService.isRunning.get()) {
            Log.d(TAG, "OverlayService já está rodando.")
            return
        }
        Log.d(TAG, "Iniciando OverlayService...")
        val intent = Intent(this, OverlayService::class.java)
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar OverlayService: ${e.message}")
            Toast.makeText(this, "Falha ao iniciar serviço de overlay.", Toast.LENGTH_SHORT).show()
            binding.appStatusSwitch.isChecked = false
        }
    }

    private fun stopOverlayService() {
        if (!OverlayService.isRunning.get()) {
            Log.d(TAG, "OverlayService já está parado.")
            return
        }
        Log.d(TAG, "Parando OverlayService...")
        val intent = Intent(this, OverlayService::class.java)
        stopService(intent)
    }

    private fun updateScreenCaptureSaveSetting(shouldSave: Boolean) {
        if (ScreenCaptureService.isRunning.get()) {
            Log.d(TAG, "Enviando atualização de saveImages ($shouldSave) para ScreenCaptureService")
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_UPDATE_SETTINGS
                putExtra(ScreenCaptureService.KEY_SAVE_IMAGES, shouldSave)
            }
            startService(intent)
        } else {
            Log.d(TAG,"ScreenCaptureService não está rodando, atualização de saveImages adiada.")
        }
    }

    // --- Permissão de Notificação (Android 13+) ---
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.i(TAG, "Permissão POST_NOTIFICATIONS concedida.")
        } else {
            Log.w(TAG, "Permissão POST_NOTIFICATIONS negada.")
            Toast.makeText(this, "Permissão de notificação é recomendada para o serviço funcionar em segundo plano.", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Solicitando permissão POST_NOTIFICATIONS.")
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Log.d(TAG, "Permissão POST_NOTIFICATIONS já concedida.")
            }
        }
    }
}