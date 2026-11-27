package com.hitarashi.multibox.core.telegram

internal object TelegramAuthMapper {

    fun normalizePhoneNumber(raw: String): String {
        val trimmed = raw.trim()
        val hasPlus = trimmed.startsWith("+")
        val digits = trimmed.filter(Char::isDigit)
        return if (hasPlus) "+$digits" else digits
    }

    fun isValidPhoneNumber(normalized: String): Boolean {
        val digits = normalized.filter(Char::isDigit)
        return normalized.startsWith("+") && digits.length in 7..15
    }

    fun mapTdLibError(code: Int, rawMessage: String?): String {
        val message = rawMessage.orEmpty()
        return when {
            message.contains("API_ID_INVALID", ignoreCase = true) ||
                message.contains("API_HASH_INVALID", ignoreCase = true) -> {
                "Invalid Telegram API ID or API Hash. Check my.telegram.org and re-enter them."
            }
            message.contains("PHONE_NUMBER_INVALID", ignoreCase = true) -> {
                "Invalid phone number. Include country code (e.g., +14155552671)."
            }
            message.contains("PHONE_NUMBER_FLOOD", ignoreCase = true) ||
                message.contains("FLOOD_WAIT", ignoreCase = true) -> {
                "Too many attempts. Please wait a few minutes and try again."
            }
            message.contains("PHONE_CODE_INVALID", ignoreCase = true) -> {
                "Invalid verification code. Check it and try again."
            }
            message.contains("PHONE_CODE_EXPIRED", ignoreCase = true) -> {
                "Verification code expired. Request a new code."
            }
            message.contains("PASSWORD_HASH_INVALID", ignoreCase = true) -> {
                "Incorrect two-step verification password."
            }
            message.contains("NETWORK", ignoreCase = true) ||
                message.contains("TIMEOUT", ignoreCase = true) ||
                code == 408 -> {
                "Telegram network error. Check your internet connection and retry."
            }
            message.isBlank() -> "Telegram error ($code)."
            else -> "Telegram error ($code): $message"
        }
    }
}
