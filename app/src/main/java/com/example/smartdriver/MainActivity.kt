package com.example.smartdriver

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color // Import para cores
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat // Import para cores de recursos
import com.example.smartdriver.databinding.ActivityMainBinding // Import ViewBinding
import com.example.smartdriver.overlay.OverlayService // Import OverlayService

/**
 * Atividade Principal: Gerencia permissões, status dos serviços e navegação.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // ViewBinding para acesso fácil aos elementos do layout
    private lateinit var binding: ActivityMainBinding

    // Launchers para obter resultados de pedidos de permissão
    private lateinit var requestMediaProjectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var requestOverlayPermissionLauncher: ActivityResultLauncher<Intent>

    // Gerenciador de Projeção de Mídia
    private var mediaProjectionManager: MediaProjectionManager? = null

    // Preferências compartilhadas para salvar estado do switch de imagens
    private lateinit var prefs: SharedPreferences

    // --- Ciclo de Vida da Activity ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Inflar layout usando ViewBinding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.d(TAG, "onCreate chamado")

        // Inicializar componentes
        initializeViewsAndPrefs()
        setupButtonClickListeners()
        setupSwitchListeners()
        registerActivityLaunchers()

        // Inicializar gerenciador de projeção
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Verificar estado inicial das permissões e serviços
        updateUIBasedOnPermissionsAndServices()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume chamado")
        // Re-verificar o estado ao voltar para a activity, pois o usuário
        // pode ter alterado permissões nas configurações do sistema.
        updateUIBasedOnPermissionsAndServices()

        // Limpar estado da última oferta no OfferManager para evitar
        // mostrar overlay antigo ao reabrir o app.
        OfferManager.getInstance(this).clearLastOfferState()
    }

    // --- Inicialização ---

    /** Inicializa referências de views e SharedPreferences */
    private fun initializeViewsAndPrefs() {
        prefs = getSharedPreferences(ScreenCaptureService.PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Configura os listeners de clique para os botões */
    private fun setupButtonClickListeners() {
        binding.accessibilityButton.setOnClickListener { openAccessibilitySettings() }
        binding.overlayButton.setOnClickListener { requestOverlayPermission() }
        binding.captureButton.setOnClickListener { requestScreenCapturePermission() }
        binding.settingsButton.setOnClickListener { openSettingsActivity() }
        binding.shutdownButton.setOnClickListener { /* Ação removida, controlada pelo switch */ }
    }

    /** Configura os listeners para os switches */
    private fun setupSwitchListeners() {
        // Switch principal de ON/OFF do app
        binding.appStatusSwitch.setOnCheckedChangeListener { _, isChecked ->
            handleAppStatusChange(isChecked)
        }

        // Switch para salvar imagens (debug)
        binding.saveImagesSwitch.setOnCheckedChangeListener { _, isChecked ->
            handleSaveImagesToggle(isChecked)
        }
    }

    /** Registra os launchers para receber resultados de outras Activities/Permissões */
    private fun registerActivityLaunchers() {
        // Launcher para permissão de Captura de Tela (MediaProjection)
        requestMediaProjectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                Log.i(TAG, "Permissão de Captura de Tela CONCEDIDA.")
                // Armazena os dados da projeção para o serviço usar
                ScreenCaptureService.mediaProjectionData = MediaProjectionData(
                    result.resultCode,
                    result.data!! // Non-null assertion ok aqui devido à verificação anterior
                )
                // Inicia o serviço de captura AGORA que temos a permissão e os dados
                startScreenCaptureServiceIfNecessary()
                updateCapturePermissionUI(true) // Atualiza UI da captura
                // Garante que o switch principal fique ON se o usuário deu a permissão
                if (!binding.appStatusSwitch.isChecked) {
                    binding.appStatusSwitch.isChecked = true
                } else {
                    // Se já estava ON, força atualização da UI geral
                    updateAppStatusUI(true)
                }

            } else {
                Log.w(TAG, "Permissão de Captura de Tela NEGADA.")
                Toast.makeText(this, "Permissão de captura negada.", Toast.LENGTH_SHORT).show()
                updateCapturePermissionUI(false)
                // Se a permissão for negada, desliga o app
                binding.appStatusSwitch.isChecked = false
            }
        }

        // Launcher para permissão de Overlay
        requestOverlayPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            // Após o usuário voltar da tela de permissão de overlay,
            // simplesmente re-verificamos e atualizamos a UI.
            Log.d(TAG, "Retornou da tela de permissão de Overlay. Verificando status...")
            updateOverlayPermissionUI(hasOverlayPermission())
            // Se ganhou permissão, mas o app estava OFF, não liga automaticamente.
            // Se o app estava ON, a permissão agora está disponível para uso.
        }
    }

    // --- Lógica de UI e Permissões ---

    /** Atualiza toda a UI com base no estado atual das permissões e serviços */
    private fun updateUIBasedOnPermissionsAndServices() {
        val hasAccessibility = isAccessibilityServiceEnabled()
        val hasOverlay = hasOverlayPermission()
        // A permissão de captura é verificada pelo `mediaProjectionData`
        val hasCapture = ScreenCaptureService.mediaProjectionData != null

        updateAccessibilityUI(hasAccessibility)
        updateOverlayPermissionUI(hasOverlay)
        updateCapturePermissionUI(hasCapture)

        // O estado geral do App (Switch ON/OFF) depende se TODAS as permissões
        // essenciais foram dadas (Acessibilidade e Captura). Overlay é opcional.
        // Ou mantemos o estado salvo e apenas habilitamos/desabilitamos os botões?
        // Vamos manter o estado do switch e apenas indicar visualmente.
        val isAppEffectivelyOn = binding.appStatusSwitch.isChecked && hasAccessibility && hasCapture
        updateAppStatusUI(binding.appStatusSwitch.isChecked) // Atualiza texto e cor do switch
        enableDisableComponents(binding.appStatusSwitch.isChecked) // Habilita/desabilita botões baseado no switch

        // Atualiza o estado inicial/atual do switch de salvar imagens
        binding.saveImagesSwitch.isChecked = prefs.getBoolean(ScreenCaptureService.KEY_SAVE_IMAGES, false)

        Log.d(TAG, "UI Atualizada: Access=$hasAccessibility, Overlay=$hasOverlay, Capture=$hasCapture, AppSwitch=${binding.appStatusSwitch.isChecked}")
    }


    /** Atualiza a seção da UI relacionada à permissão de Acessibilidade */
    private fun updateAccessibilityUI(isEnabled: Boolean) {
        if (isEnabled) {
            binding.accessibilityStatusTextView.text = "1. Acessibilidade: Ativo"
            binding.accessibilityStatusTextView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            binding.accessibilityButton.text = "Configurar" // Ou "Desativar"? Melhor deixar ir pras configs
            binding.accessibilityButton.isEnabled = true // Sempre pode ir configurar
        } else {
            binding.accessibilityStatusTextView.text = "1. Acessibilidade: Inativo"
            binding.accessibilityStatusTextView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            binding.accessibilityButton.text = "Ativar"
            binding.accessibilityButton.isEnabled = true
        }
    }

    /** Atualiza a seção da UI relacionada à permissão de Overlay */
    private fun updateOverlayPermissionUI(hasPermission: Boolean) {
        if (hasPermission) {
            binding.overlayStatusTextView.text = "2. Overlay: Permitido"
            binding.overlayStatusTextView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            binding.overlayButton.text = "Configurar" // Botão leva para as configurações do app
            binding.overlayButton.setOnClickListener { openAppSystemSettings() } // Muda ação do botão
            binding.overlayButton.isEnabled = true
            // Atualiza o OfferManager sobre a permissão (caso ele precise)
            OfferManager.getInstance(this).setUseOverlay(true) // Assumimos que se tem permissão, quer usar
        } else {
            binding.overlayStatusTextView.text = "2. Overlay: Não Permitido"
            binding.overlayStatusTextView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            binding.overlayButton.text = "Permitir"
            binding.overlayButton.setOnClickListener { requestOverlayPermission() } // Ação padrão
            binding.overlayButton.isEnabled = true // Sempre pode tentar permitir
            OfferManager.getInstance(this).setUseOverlay(false) // Informa que não pode usar
        }
    }

    /** Atualiza a seção da UI relacionada à permissão de Captura de Tela */
    private fun updateCapturePermissionUI(hasPermission: Boolean) {
        if (hasPermission) {
            binding.captureStatusTextView.text = "3. Captura: Permitida"
            binding.captureStatusTextView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            binding.captureButton.text = "Ativa" // Indica que está ativa
            binding.captureButton.isEnabled = false // Não precisa clicar novamente
        } else {
            binding.captureStatusTextView.text = "3. Captura: Não Permitida"
            binding.captureStatusTextView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            binding.captureButton.text = "Permitir"
            binding.captureButton.isEnabled = true // Habilitado para pedir permissão
        }
    }

    /** Atualiza a aparência do switch principal ON/OFF e seu texto */
    private fun updateAppStatusUI(isOn: Boolean) {
        if (isOn) {
            binding.appStatusText.text = getString(R.string.app_status_on)
            binding.appStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            binding.appStatusText.text = getString(R.string.app_status_off)
            binding.appStatusText.setTextColor(ContextCompat.getColor(this, R.color.gray_inactive))
        }
    }

    /** Habilita ou desabilita os botões de permissão baseado no estado do switch ON/OFF */
    private fun enableDisableComponents(enable: Boolean) {
        binding.accessibilityButton.isEnabled = enable || !isAccessibilityServiceEnabled() // Sempre habilitado se OFF, ou se ON mas serviço inativo
        binding.overlayButton.isEnabled = enable || !hasOverlayPermission()
        binding.captureButton.isEnabled = enable || ScreenCaptureService.mediaProjectionData == null
        binding.settingsButton.isEnabled = enable // Só pode configurar se app estiver ON? Ou sempre? Melhor sempre.
        binding.saveImagesSwitch.isEnabled = enable // Só pode mudar se app ON
    }


    // --- Lógica de Ações ---

    /** Abre a tela de configurações de acessibilidade do sistema */
    private fun openAccessibilitySettings() {
        Toast.makeText(this, "Procure e ative 'SmartDriver Service'", Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        // Verificar se a intent pode ser resolvida antes de iniciar
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            Log.e(TAG, "Não foi possível abrir as configurações de Acessibilidade.")
            Toast.makeText(this, "Não foi possível abrir as configurações.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Solicita a permissão para desenhar sobre outros apps (Overlay) */
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.d(TAG, "Solicitando permissão de Overlay...")
            Toast.makeText(this,"Conceda a permissão para o SmartDriver", Toast.LENGTH_LONG).show()
            // Intent para a tela específica de permissão do app
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            requestOverlayPermissionLauncher.launch(intent)
        } else {
            Log.d(TAG,"Permissão de Overlay já concedida ou API < 23.")
            updateOverlayPermissionUI(true) // Garante que UI esteja atualizada
        }
    }

    /** Abre as configurações do sistema para este aplicativo */
    private fun openAppSystemSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = Uri.fromParts("package", packageName, null)
        intent.data = uri
        // Verificar se a intent pode ser resolvida
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            Log.e(TAG, "Não foi possível abrir as configurações do aplicativo.")
            Toast.makeText(this, "Não foi possível abrir as configurações.", Toast.LENGTH_SHORT).show()
        }
    }


    /** Solicita a permissão de Captura de Tela (MediaProjection) */
    private fun requestScreenCapturePermission() {
        if (mediaProjectionManager != null && ScreenCaptureService.mediaProjectionData == null) {
            Log.d(TAG, "Solicitando permissão de Captura de Tela...")
            val captureIntent = mediaProjectionManager?.createScreenCaptureIntent()
            if (captureIntent != null) {
                requestMediaProjectionLauncher.launch(captureIntent)
            } else {
                Log.e(TAG, "Não foi possível criar ScreenCaptureIntent.")
                Toast.makeText(this, "Erro ao iniciar pedido de captura.", Toast.LENGTH_SHORT).show()
            }
        } else if (ScreenCaptureService.mediaProjectionData != null) {
            Log.d(TAG,"Permissão de Captura já concedida anteriormente.")
            updateCapturePermissionUI(true)
        } else {
            Log.e(TAG,"MediaProjectionManager é nulo.")
        }
    }

    /** Abre a SettingsActivity */
    private fun openSettingsActivity() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    /** Lida com a mudança de estado do switch principal ON/OFF */
    private fun handleAppStatusChange(isOn: Boolean) {
        updateAppStatusUI(isOn) // Atualiza cor/texto do switch
        enableDisableComponents(isOn) // Habilita/desabilita botões

        if (isOn) {
            Log.i(TAG, "App LIGADO pelo usuário.")
            // Ao ligar, verificar se as permissões essenciais estão OK
            val hasAccessibility = isAccessibilityServiceEnabled()
            val hasCapture = ScreenCaptureService.mediaProjectionData != null

            if (!hasAccessibility) {
                Toast.makeText(this, "Ative o serviço de Acessibilidade!", Toast.LENGTH_LONG).show()
                // Poderia desligar o switch de volta? Ou deixar o usuário resolver?
                // binding.appStatusSwitch.isChecked = false
                return // Sai, pois sem acessibilidade não adianta prosseguir
            }
            if (!hasCapture) {
                Toast.makeText(this, "Permita a Captura de Tela!", Toast.LENGTH_LONG).show()
                // binding.appStatusSwitch.isChecked = false
                return // Sai, pois sem captura não adianta prosseguir
            }

            // Se ambas as permissões essenciais estão OK, inicia o serviço de captura (se não estiver rodando)
            startScreenCaptureServiceIfNecessary()

        } else {
            Log.i(TAG, "App DESLIGADO pelo usuário.")
            // Ao desligar, para os serviços
            stopScreenCaptureService()
            // O serviço de acessibilidade é gerenciado pelo sistema, mas podemos
            // garantir que o overlay seja escondido.
            OfferManager.getInstance(this).setUseOverlay(false) // Desativa uso e esconde
            ScreenCaptureService.mediaProjectionData = null // Limpa dados da projeção
            updateCapturePermissionUI(false) // Atualiza UI da captura
        }
    }

    /** Lida com a mudança do switch de salvar imagens */
    private fun handleSaveImagesToggle(saveImages: Boolean) {
        prefs.edit().putBoolean(ScreenCaptureService.KEY_SAVE_IMAGES, saveImages).apply()
        Log.i(TAG, "Opção 'Salvar Imagens' alterada para: $saveImages")
        // Envia a configuração para o serviço, caso ele esteja rodando
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_UPDATE_SETTINGS
            putExtra(ScreenCaptureService.KEY_SAVE_IMAGES, saveImages)
        }
        try {
            startService(intent) // Envia mesmo se não estiver rodando, ele lerá no onCreate/onStartCommand
            Toast.makeText(this,
                if (saveImages) "Salvamento de imagens ATIVADO" else "Salvamento de imagens DESATIVADO",
                Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG,"Erro ao enviar config de salvar imagens para o serviço: ${e.message}")
        }
    }

    // --- Funções Auxiliares de Serviço ---

    /** Inicia o ScreenCaptureService se tiver permissão e dados, e ainda não estiver ativo */
    private fun startScreenCaptureServiceIfNecessary() {
        if (ScreenCaptureService.mediaProjectionData != null) {
            Log.d(TAG, "Tentando iniciar ScreenCaptureService...")
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START_CAPTURE
            }
            try {
                ContextCompat.startForegroundService(this, intent) // Correto para iniciar serviço de foreground
                Log.i(TAG,"Comando START_CAPTURE enviado para ScreenCaptureService.")
            } catch (e: Exception) {
                Log.e(TAG,"Erro ao iniciar ScreenCaptureService via startForegroundService: ${e.message}")
                Toast.makeText(this,"Erro ao iniciar serviço de captura.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.w(TAG,"Não é possível iniciar ScreenCaptureService: mediaProjectionData é nulo.")
        }
    }

    /** Para o ScreenCaptureService */
    private fun stopScreenCaptureService() {
        Log.d(TAG, "Tentando parar ScreenCaptureService...")
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP_CAPTURE
        }
        stopService(intent) // Comando para parar o serviço
        Log.i(TAG,"Comando STOP_CAPTURE enviado para ScreenCaptureService.")
    }

    /** Verifica se o nosso serviço de acessibilidade está ativo */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val expectedServiceId = "$packageName/.SmartDriverAccessibilityService" // Nome completo do componente

        for (service in enabledServices) {
            // Log.d(TAG, "Serviço de Acessibilidade Ativo: ${service.id}") // Log para debug
            if (service.id.equals(expectedServiceId, ignoreCase = true)) {
                Log.d(TAG, "Nosso serviço de acessibilidade está ATIVO.")
                return true
            }
        }
        Log.d(TAG, "Nosso serviço de acessibilidade está INATIVO.")
        return false
    }

    /** Verifica permissão de Overlay */
    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }
}