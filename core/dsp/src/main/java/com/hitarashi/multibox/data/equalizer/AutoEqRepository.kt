package com.hitarashi.multibox.data.equalizer

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutoEqRepository @Inject constructor() {

    val popularProfiles: List<AutoEqProfile> = listOf(
        AutoEqProfile(
            id = "sony_wh1000xm4",
            manufacturer = "Sony",
            model = "WH-1000XM4",
            formFactor = "Over-Ear Wireless",
            bandGains = listOf(-4, -6, -3, 0, 1, 2, 3, 2, 4, 3)
        ),
        AutoEqProfile(
            id = "sony_wh1000xm5",
            manufacturer = "Sony",
            model = "WH-1000XM5",
            formFactor = "Over-Ear Wireless",
            bandGains = listOf(-3, -5, -2, 0, 1, 2, 2, 3, 3, 2)
        ),
        AutoEqProfile(
            id = "sennheiser_hd600",
            manufacturer = "Sennheiser",
            model = "HD 600",
            formFactor = "Open-Back Over-Ear",
            bandGains = listOf(6, 4, 2, 0, -1, 0, 1, 2, -1, -2)
        ),
        AutoEqProfile(
            id = "sennheiser_hd650",
            manufacturer = "Sennheiser",
            model = "HD 650 / HD 6XX",
            formFactor = "Open-Back Over-Ear",
            bandGains = listOf(5, 4, 1, 0, -1, 0, 1, 3, 0, -2)
        ),
        AutoEqProfile(
            id = "airpods_pro_2",
            manufacturer = "Apple",
            model = "AirPods Pro 2",
            formFactor = "TWS In-Ear",
            bandGains = listOf(1, 0, -1, 0, 1, 2, 1, 0, 2, 1)
        ),
        AutoEqProfile(
            id = "airpods_max",
            manufacturer = "Apple",
            model = "AirPods Max",
            formFactor = "Over-Ear Wireless",
            bandGains = listOf(0, -1, -1, 0, 1, 1, 2, 2, 1, 0)
        ),
        AutoEqProfile(
            id = "kz_zsn_pro",
            manufacturer = "KZ",
            model = "ZSN Pro",
            formFactor = "IEM In-Ear",
            bandGains = listOf(-3, -4, -2, 0, 1, 2, -2, -5, -4, -3)
        ),
        AutoEqProfile(
            id = "kz_zsn_pro_x",
            manufacturer = "KZ",
            model = "ZSN Pro X",
            formFactor = "IEM In-Ear",
            bandGains = listOf(-2, -3, -2, 0, 1, 2, -3, -4, -4, -2)
        ),
        AutoEqProfile(
            id = "audio_technica_ath_m50x",
            manufacturer = "Audio-Technica",
            model = "ATH-M50x",
            formFactor = "Closed-Back Over-Ear",
            bandGains = listOf(-2, -3, -1, 0, 1, 0, 2, -2, 1, 2)
        ),
        AutoEqProfile(
            id = "samsung_galaxy_buds2_pro",
            manufacturer = "Samsung",
            model = "Galaxy Buds2 Pro",
            formFactor = "TWS In-Ear",
            bandGains = listOf(0, -1, 0, 0, 1, 1, 0, 1, 2, 1)
        ),
        AutoEqProfile(
            id = "beyerdynamic_dt770_80",
            manufacturer = "Beyerdynamic",
            model = "DT 770 Pro (80 Ohm)",
            formFactor = "Closed-Back Over-Ear",
            bandGains = listOf(1, -2, -1, 0, 1, 1, 1, -4, -5, -2)
        ),
        AutoEqProfile(
            id = "moondrop_aria",
            manufacturer = "Moondrop",
            model = "Aria",
            formFactor = "IEM In-Ear",
            bandGains = listOf(1, 0, 0, 0, 0, 1, 1, 1, 0, 0)
        ),
        AutoEqProfile(
            id = "anker_soundcore_q30",
            manufacturer = "Anker",
            model = "Soundcore Q30",
            formFactor = "Over-Ear Wireless",
            bandGains = listOf(-6, -7, -4, -1, 1, 2, 3, 2, 4, 3)
        ),
        AutoEqProfile(
            id = "bose_qc35_ii",
            manufacturer = "Bose",
            model = "QuietComfort 35 II",
            formFactor = "Over-Ear Wireless",
            bandGains = listOf(1, 0, -1, 0, 1, 1, 2, 1, 2, 1)
        )
    )

    fun findProfileById(id: String): AutoEqProfile? {
        return popularProfiles.find { it.id == id }
    }

    fun searchProfiles(query: String): List<AutoEqProfile> {
        if (query.isBlank()) return popularProfiles
        val trimmed = query.trim().lowercase()
        return popularProfiles.filter {
            it.manufacturer.lowercase().contains(trimmed) ||
                it.model.lowercase().contains(trimmed) ||
                it.formFactor.lowercase().contains(trimmed)
        }
    }
}
