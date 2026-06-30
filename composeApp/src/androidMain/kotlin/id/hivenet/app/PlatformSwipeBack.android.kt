package id.hivenet.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun Modifier.platformSwipeBack(enabled: Boolean, onBack: () -> Unit): Modifier = this
