package id.hivenet.app

import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.languageCode

actual fun deviceLanguageCode(): String = NSLocale.currentLocale.languageCode
