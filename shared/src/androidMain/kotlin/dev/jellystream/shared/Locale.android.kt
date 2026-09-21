package dev.jellystream.shared

import java.util.Locale

actual fun currentLanguageTag(): String = Locale.getDefault().toLanguageTag()
