package com.example.smartdriver.permissions.steps

import com.example.smartdriver.permissions.PermissionsHelper

class StepOverlayPermissionFragment : BasePermissionFragment() {
    override fun headerText() = "Permissão para sobrepor ecrã"
    override fun explanationText() =
        "Necessária para mostrar o semáforo e elementos por cima de outras apps (ex.: Uber)."
    override fun openSettings() { context?.let { PermissionsHelper.requestOverlayPermission(it) } }
    override fun isGranted(): Boolean = context?.let { PermissionsHelper.isOverlayGranted(it) } == true
}
