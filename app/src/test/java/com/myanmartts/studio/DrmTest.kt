package com.myanmartts.studio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the Kotlin port of the Sec-MS-GEC v2 algorithm against vectors
 * generated with the Python edge-tts fork itself:
 *
 *   from edge_tts.drm import generate_sec_ms_gec_v2
 *   generate_sec_ms_gec_v2(<timestamp>)
 *
 * Run in Android Studio: right-click DrmTest -> Run.
 */
class DrmTest {

    @Test
    fun secMsGecVectors() {
        assertEquals(
            "2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2",
            Drm.generateSecMsGec(1700000000.0),
        )
        assertEquals(
            "576EF243D9E6C36D-B73D5ADB984074BAE7860791FCA9CE1D28D1F6678CE5644A7981ACCA7E3C0EF2",
            Drm.generateSecMsGec(1720000000.5),
        )
        assertEquals(
            "28146D5A060E671F-BDA73489195BC5C375BBFCDCA5B7043A4911E817410B4C4C88A9F5826CFB39A4",
            Drm.generateSecMsGec(1792326293.641),
        )
        assertEquals(
            "52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6",
            Drm.generateSecMsGec(0.0),
        )
        assertEquals(
            "2D62557F1142BC5D-FD9C30C68FF194A5EE6B45FEFC5E2BA1D1239F88A90569AB55CFFFAA7799F40E",
            Drm.generateSecMsGec(2000000000.0),
        )
    }

    @Test
    fun tokenFormat() {
        val token = Drm.generateSecMsGec()
        val parts = token.split("-")
        // "<16 hex>-<64 hex>"
        assertEquals(2, parts.size)
        assertEquals(16, parts[0].length)
        assertEquals(64, parts[1].length)
        assertEquals(token, token.uppercase())
    }

    @Test
    fun speedToRate() {
        assertEquals("+30%", TtsLogic.speedToRate(30))
        assertEquals("+0%", TtsLogic.speedToRate(0))
        assertEquals("-50%", TtsLogic.speedToRate(-50))
        assertEquals("+100%", TtsLogic.speedToRate(200))
    }

    @Test
    fun splitForTtsKeepsOrder() {
        val chunks = TtsLogic.splitForTts("မင်္ဂလာပါ။ ဒါက စမ်းသပ်စာသား ဖြစ်ပါတယ်။ ကျေးဇူးတင်ပါတယ်။")
        assertEquals(
            listOf("မင်္ဂလာပါ။", "ဒါက စမ်းသပ်စာသား ဖြစ်ပါတယ်။", "ကျေးဇူးတင်ပါတယ်။"),
            chunks,
        )
    }
}
