package com.miller.tpics

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.miller.tpics.data.Auth
import com.miller.tpics.data.Prefs
import com.miller.tpics.data.Store
import com.miller.tpics.ui.AppRoot
import com.miller.tpics.ui.TPicsTheme
import com.miller.tpics.ui.isDark

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val mode by Prefs.theme.collectAsState()
            val dark = isDark(mode)
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            TPicsTheme(mode) { AppRoot() }
        }
    }

    override fun onResume() {
        super.onResume()
        // Al volver a la app se traen los cambios hechos desde otros dispositivos.
        if (Auth.session.value != null && Store.loaded.value) Store.syncNow()
    }
}
