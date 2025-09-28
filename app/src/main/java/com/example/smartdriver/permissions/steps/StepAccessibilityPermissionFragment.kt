package com.example.smartdriver.permissions.steps

import com.example.smartdriver.permissions.PermissionsHelper

class StepAccessibilityPermissionFragment : BasePermissionFragment() {
    override fun headerText() = "Serviço de Acessibilidade"
    override fun explanationText() =
        "Permite ler eventos de interface (detetar ofertas) com estabilidade. Não recolhemos dados pessoais."
    override fun openSettings() { context?.let { PermissionsHelper.openAccessibilitySettings(it) } }
    override fun isGranted(): Boolean =
        context?.let { PermissionsHelper.isAnyAccessibilityFromThisAppEnabled(it) } == true
}
