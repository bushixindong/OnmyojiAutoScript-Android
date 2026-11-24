// Layout measurements adapted from OASX home workbench widgets.
package com.oas.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object OasDesignTokens {
    object Spacing { val xs = 4.dp; val sm = 8.dp; val md = 12.dp; val lg = 16.dp; val sectionGap = 12.dp }
    object CornerRadius { val card = 12.dp; val button = 20.dp; val pill = 20.dp; val inner = 10.dp }
    object Separator { val thickness = 1.dp; val inset = 12.dp }
    object Card { val elevation = 1.dp; val innerPadding = 12.dp }
    object StatusColors {
        val Running = Color(0xFF4CAF50)
        val RunningAccent = Color(0xFF43A047)
        val Pending = Color(0xFFFF9800)
        val AbnormalAccent = Color(0xFFF57C00)
        val Waiting = Color(0xFF607D8B)
        val Stopped = Color(0xFF9E9E9E)
    }
}
