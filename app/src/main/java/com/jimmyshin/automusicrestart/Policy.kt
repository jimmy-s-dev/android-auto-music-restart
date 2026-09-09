package com.jimmyshin.automusicrestart

/** Pure state machine: re-posting an ongoing notification must never restart healthy music. */
class ConnectionGate(var consumed: Boolean = false) {
    fun observe(connected: Boolean, enabled: Boolean): Boolean {
        if (!connected) { consumed = false; return false }
        if (!enabled || consumed) return false
        consumed = true
        return true
    }
}

object Target {
    const val PACKAGE = "app.morphe.android.apps.youtube.music"
    const val BROWSER = "com.google.android.apps.youtube.music.mediabrowser.MusicBrowserService"
    const val ACTIVITY = "com.google.android.apps.youtube.music.activities.MusicActivity"
    const val AUTO = "com.google.android.projection.gearhead"
}
