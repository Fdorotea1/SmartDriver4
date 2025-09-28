package com.example.smartdriver.permissions.steps

import com.example.smartdriver.permissions.PermissionsHelper

class StepNotificationPermissionFragment : BasePermissionFragment() {
    override fun headerText() = "Acesso a Notificações"
    override fun explanationText() =
        "Permite ler notificações relevantes (ex.: alertas de viagens) e melhorar tempos de reação."
    override fun openSettings() { context?.let { PermissionsHelper.openNotificationListenerSettings(it) } }
    override fun isGranted(): Boolean =
        context?.let { PermissionsHelper.isNotificationListenerEnabled(it) } == true
}
