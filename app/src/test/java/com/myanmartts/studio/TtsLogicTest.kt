package com.myanmartts.studio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the 2026-10-28 error "စာသားစစ်ဆေးမှု မအောင်မြင်ပါ။
 * စကားလုံး ပျောက်နေသည်။" on stories with quoted dialogue: the old
 * splitForTts tore a closing quote off its word ("မယ်။”" -> "မယ်။" + "”")
 * and the word-list integrity check then refused the result.
 */
class TtsLogicTest {

    @Test
    fun quotedDialogueWithClosingQuoteAfterFullStop() {
        val text = "“ပထမတစ်ဆပ် နီးတလာပြီ။ အချိန်ပျောက်ဆုံးသော မြို့တော်ဆီ သွားရမယ်။” " +
            "တို့ညမှာ သူရယူ နားလည်သွားခဲ့ပါတယ်။"
        val chunks = TtsLogic.splitForTts(text)
        assertEquals(
            text.filterNot { it.isWhitespace() },
            chunks.joinToString("").filterNot { it.isWhitespace() }
        )
    }

    @Test
    fun normalSentencesStillSplitAtFullStop() {
        val chunks = TtsLogic.splitForTts("ပထမဝါကျ။ ဒုတိယဝါကျ။")
        assertEquals(listOf("ပထမဝါကျ။", "ဒုတိယဝါကျ။"), chunks)
    }

    @Test
    fun srtSplitPreservesText() {
        val text = "စာသား အရှည် စမ်းသပ်မှု ဖြစ်သည်။"
        val parts = TtsLogic.splitForSrt(text)
        assertEquals(
            text.filterNot { it.isWhitespace() },
            parts.joinToString("").filterNot { it.isWhitespace() }
        )
    }
}
