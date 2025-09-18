package com.example.smartdriver

// Compat: se o teu MediaProjectionData não tiver clearConsent(), este extension resolve.
fun MediaProjectionData.clearConsent() {
    clear() // delega no método antigo
}
