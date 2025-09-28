package com.example.smartdriver.permissions.steps

import com.example.smartdriver.permissions.PermissionsHelper

class StepBatteryOptimizationFragment : BasePermissionFragment() {
    override fun headerText() = "Otimizações de bateria"
    override fun explanationText() =
        "Para evitar que o Android encerre o serviço em segundo plano, exclua o SmartDriver da otimização."
    override fun openSettings() { context?.let { PermissionsHelper.requestIgnoreBatteryOptimizations(it) } }
    override fun isGranted(): Boolean =
        context?.let { PermissionsHelper.isIgnoringBatteryOptimizations(it) } == true
}
