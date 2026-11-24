// OASX task_parameter_panel.dart wrapper for standalone Settings entries (GlobalGame / Script).
package com.oas.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun TaskEditor(controller: OasController, id: String, onBack: () -> Unit) {
    Surface(
        Modifier.fillMaxSize().statusBarsPadding(),
        color = MaterialTheme.colorScheme.background
    ) {
        OasxTaskParameterPanel(
            controller = controller,
            id = id,
            onBack = onBack,
            showTopTitleBar = true
        )
    }
}
