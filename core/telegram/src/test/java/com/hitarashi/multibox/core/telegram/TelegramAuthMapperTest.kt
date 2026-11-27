package com.hitarashi.multibox.core.telegram

import com.google.common.truth.Truth.assertThat
import com.hitarashi.multibox.core.model.telegram.TelegramApiCredentials
import org.junit.jupiter.api.Test

class TelegramAuthMapperTest {

    @Test
    fun `normalizePhoneNumber keeps leading plus and strips separators`() {
        assertThat(TelegramAuthMapper.normalizePhoneNumber("  +1 (415) 555-2671 "))
            .isEqualTo("+14155552671")
        assertThat(TelegramAuthMapper.normalizePhoneNumber("987-654-3210"))
            .isEqualTo("9876543210")
    }

    @Test
    fun `isValidPhoneNumber requires leading plus and E164 digit length`() {
        assertThat(TelegramAuthMapper.isValidPhoneNumber("+14155552671")).isTrue()
        assertThat(TelegramAuthMapper.isValidPhoneNumber("14155552671")).isFalse()
        assertThat(TelegramAuthMapper.isValidPhoneNumber("+123")).isFalse()
    }

    @Test
    fun `mapTdLibError maps known TDLib errors to actionable copy`() {
        assertThat(TelegramAuthMapper.mapTdLibError(400, "PHONE_NUMBER_INVALID"))
            .contains("country code")
        assertThat(TelegramAuthMapper.mapTdLibError(400, "PHONE_CODE_INVALID"))
            .contains("verification code")
        assertThat(TelegramAuthMapper.mapTdLibError(400, "PASSWORD_HASH_INVALID"))
            .contains("two-step")
        assertThat(TelegramAuthMapper.mapTdLibError(420, "FLOOD_WAIT_60"))
            .contains("Too many attempts")
        assertThat(TelegramAuthMapper.mapTdLibError(400, "API_ID_INVALID"))
            .contains("my.telegram.org")
    }

    @Test
    fun `TelegramApiCredentials requires positive apiId and non-blank hash`() {
        assertThat(TelegramApiCredentials(0, "abc").isValid).isFalse()
        assertThat(TelegramApiCredentials(12345, "   ").isValid).isFalse()
        assertThat(TelegramApiCredentials(12345, "abcdef").isValid).isTrue()
    }
}
