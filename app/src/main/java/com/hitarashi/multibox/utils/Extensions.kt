package com.hitarashi.multibox.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.util.Locale

fun Color.toHexString(): String {
    return String.format(Locale.US, "#%08X", this.toArgb())
}
