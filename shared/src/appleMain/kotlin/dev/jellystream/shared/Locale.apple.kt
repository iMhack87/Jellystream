package dev.jellystream.shared

import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

actual fun currentLanguageTag(): String =
    (NSLocale.preferredLanguages.firstOrNull() as? String) ?: "en"
