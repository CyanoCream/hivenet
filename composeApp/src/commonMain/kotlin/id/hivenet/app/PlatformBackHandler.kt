package id.hivenet.app

import androidx.compose.runtime.Composable

@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
