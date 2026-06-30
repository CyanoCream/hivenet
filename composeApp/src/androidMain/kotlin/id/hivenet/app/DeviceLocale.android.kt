package id.hivenet.app

import java.util.Locale

actual fun deviceLanguageCode(): String = Locale.getDefault().language
