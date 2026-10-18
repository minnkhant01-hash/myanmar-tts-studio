package com.myanmartts.studio

/**
 * Port of the text-processing logic from the Python Gradio app
 * (myanmar-tts-studio/app.py): chunking, SRT building and the
 * Myanmar voice display-name mapping.
 */
object TtsLogic {

    const val WORDS_PER_SUBTITLE = 20
    const val MAX_CHUNK_CHARACTERS = 1800
    const val DEFAULT_VOICE_ID = "it-IT-GiuseppeMultilingualNeural"
    const val DEFAULT_SPEED = 30

    val PINNED_VOICES = setOf(
        "my-MM-NilarNeural",
        "my-MM-ThihaNeural",
        "it-IT-GiuseppeMultilingualNeural",
    )

    val VOICE_DISPLAY_NAMES = mapOf(
        "it-IT-GiuseppeMultilingualNeural" to "နေတိုး",
        "my-MM-NilarNeural" to "ဝတ်မှုန်ရွှေရည်",
        "my-MM-ThihaNeural" to "ပြေတီဦး",
    )

    val VOICE_ALIAS_NAMES = listOf(
        "အကယ်ဒမီမင်းမော်ကွန်း", "ပိုင်တံခွန်", "ရဲတိုက်", "မြင့်မြတ်",
        "နေမင်း", "အောင်ရဲလင်း", "ကျော်ကျော်ဗို", "လွင်မိုး",
        "ပြေတီဦး", "နေတိုး", "ဝတ်မှုန်ရွှေရည်", "ဖွေးဖွေး",
        "အိန္ဒြာကျော်ဇင်", "သက်မွန်မြင့်", "မိုးဟေကို", "ရွှေမှုံရတီ",
        "သင်ဇာဝင့်ကျော်", "ပိုင်ဖြိုးသု", "ခင်ဝင့်ဝါ", "အေးမြတ်သူ",
        "အိချောပို", "နန်းဆုဦး", "အေးဝတ်ရည်သောင်း", "ဆုပန်ထွာ",
        "မေမြင့်မိုရ်", "ဝါဆိုမိုးဦး", "သံသာမိုးသိမ့်", "မြတ်နိုးအေး",
        "ခင်သဇင်", "ယွန်းယွန်း", "မင်းဦး", "ခန့်စည်သူ",
        "ထွန်းထွန်း", "ဒေါင်း", "အောင်လေး", "ဇေရဲထက်",
        "ဟိန်းဝေယံ", "မင်းရာဇာ", "ရဲအောင်", "ကျော်ဟိန်း",
        "ဇင်ဝိုင်း", "လူမင်း", "ခိုင်သင်းကြည်", "စိုးမြတ်သူဇာ",
        "ထက်ထက်မိုးဦး", "မေသန်းနု", "နန္ဒာလှိုင်", "စိုးပြည့်သဇင်",
        "ဝိုင်းစုခိုင်သိန်း", "ချစ်သုဝေ", "မေကဗျာ", "မိုးပြည့်ပြည့်မောင်",
        "ယုသန္တာတင်", "သဲစုညိမ်း", "မေသက်ခိုင်", "ရွှေသမီး",
        "စန္ဒီမြင့်လွင်", "နန်းမြတ်ဖြိုးသင်း", "အဆိုတော်အမျိုးသား", "အဆိုတော်အမျိုးသမီး",
    )

    fun fallbackVoices(): List<EdgeTtsClient.Voice> = listOf(
        EdgeTtsClient.Voice("my-MM-NilarNeural", "my-MM", "Female"),
        EdgeTtsClient.Voice("my-MM-ThihaNeural", "my-MM", "Male"),
        EdgeTtsClient.Voice("it-IT-GiuseppeMultilingualNeural", "it-IT", "Male"),
    )

    /**
     * Keep pinned + multilingual voices, sorted with the default voice first,
     * then pinned, then the rest — same order as the Python app.
     */
    fun selectVoices(all: List<EdgeTtsClient.Voice>): List<EdgeTtsClient.Voice> {
        val selected = all.filter { v ->
            v.shortName.isNotEmpty() &&
                (v.shortName in PINNED_VOICES || "MultilingualNeural" in v.shortName)
        }.toMutableList()
        val ids = selected.map { it.shortName }.toSet()
        for (pinned in PINNED_VOICES) {
            if (pinned !in ids) {
                fallbackVoices().firstOrNull { it.shortName == pinned }?.let { selected.add(it) }
            }
        }
        return selected.sortedWith(
            compareBy(
                { v ->
                    when {
                        v.shortName == DEFAULT_VOICE_ID -> 0
                        v.shortName in PINNED_VOICES -> 1
                        else -> 2
                    }
                },
                { it.locale },
                { it.shortName },
            )
        )
    }

    /** Myanmar display label -> voice id, without ever showing raw voice ids. */
    fun buildVoiceDisplayMap(voices: List<EdgeTtsClient.Voice>): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        val used = mutableSetOf<String>()
        var aliasIndex = 0
        for (v in voices) {
            if (v.shortName.isEmpty()) continue
            var label: String = VOICE_DISPLAY_NAMES[v.shortName] ?: run {
                var alias: String? = null
                while (aliasIndex < VOICE_ALIAS_NAMES.size) {
                    val candidate = VOICE_ALIAS_NAMES[aliasIndex++]
                    if (candidate !in used) {
                        alias = candidate
                        break
                    }
                }
                alias ?: "အသံဇာတ်ကောင် ${result.size + 1}"
            }
            val base = label
            var suffix = 2
            while (label in used) {
                label = "$base $suffix"
                suffix++
            }
            used.add(label)
            result[label] = v.shortName
        }
        return result
    }

    // ----------------------------------------------------------
    // Script processing
    // ----------------------------------------------------------

    fun normalizeScript(text: String): String {
        return text.replace("\r\n", "\n").replace("\r", "\n")
            .split("\n")
            .map { it.replace(Regex("[ \\t]+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun splitLongText(text: String, maxChars: Int = MAX_CHUNK_CHARACTERS): List<String> {
        val words = text.split(" ").filter { it.isNotEmpty() }
        val chunks = mutableListOf<String>()
        val current = mutableListOf<String>()
        var currentLength = 0
        for (word in words) {
            val added = word.length + if (current.isNotEmpty()) 1 else 0
            if (current.isNotEmpty() && currentLength + added > maxChars) {
                chunks.add(current.joinToString(" "))
                current.clear()
                current.add(word)
                currentLength = word.length
            } else {
                current.add(word)
                currentLength += added
            }
        }
        if (current.isNotEmpty()) chunks.add(current.joinToString(" "))
        return chunks
    }

    fun verifyTextIntegrity(original: String, parts: List<String>) {
        val a = original.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val b = parts.flatMap { it.split(Regex("\\s+")) }.filter { it.isNotEmpty() }
        if (a != b) throw IllegalStateException("စာသားစစ်ဆေးမှု မအောင်မြင်ပါ။ စကားလုံး ပျောက်နေသည်။")
    }

    /** Split after every '။', preserving order and punctuation. */
    fun splitForTts(text: String): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        val parts = Regex("[^။]*။|[^။]+$", RegexOption.DOT_MATCHES_ALL)
            .findAll(t)
            .map { it.value.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val chunks = mutableListOf<String>()
        for (part in parts) {
            if (part.length > MAX_CHUNK_CHARACTERS) chunks.addAll(splitLongText(part))
            else chunks.add(part)
        }
        verifyTextIntegrity(t, chunks)
        return chunks
    }

    fun splitForSrt(text: String, wordsPerSubtitle: Int = WORDS_PER_SUBTITLE): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val result = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            result.add(words.subList(i, minOf(i + wordsPerSubtitle, words.size)).joinToString(" "))
            i += wordsPerSubtitle
        }
        verifyTextIntegrity(text, result)
        return result
    }

    // ----------------------------------------------------------
    // Speed + SRT
    // ----------------------------------------------------------

    /** Edge TTS rate needs an explicit sign; zero is "+0%". */
    fun speedToRate(speed: Int): String {
        val s = speed.coerceIn(-50, 100)
        return if (s >= 0) "+$s%" else "$s%"
    }

    fun formatSrtTime(milliseconds: Long): String {
        var ms = maxOf(0L, milliseconds)
        val h = ms / 3600000; ms %= 3600000
        val m = ms / 60000; ms %= 60000
        val s = ms / 1000; ms %= 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, ms)
    }

    data class SrtEntry(val text: String, val startMs: Long, val endMs: Long)

    fun makeSrtEntries(subtitleTexts: List<String>, totalDurationMs: Long): List<SrtEntry> {
        if (subtitleTexts.isEmpty()) return emptyList()
        val weights = subtitleTexts.map { maxOf(1, it.replace(" ", "").length).toLong() }
        val totalWeight = weights.sum()
        if (totalWeight <= 0) return emptyList()
        val entries = mutableListOf<SrtEntry>()
        var elapsed = 0L
        for (i in subtitleTexts.indices) {
            val start = elapsed
            val end = if (i == subtitleTexts.lastIndex) {
                totalDurationMs
            } else {
                val duration = (totalDurationMs * weights[i] + totalWeight / 2) / totalWeight
                elapsed += duration
                elapsed
            }
            entries.add(SrtEntry(subtitleTexts[i], start, end))
        }
        return entries
    }

    fun buildSrt(entries: List<SrtEntry>): String {
        val sb = StringBuilder()
        entries.forEachIndexed { index, e ->
            sb.append("${index + 1}\n")
            sb.append("${formatSrtTime(e.startMs)} --> ${formatSrtTime(e.endMs)}\n")
            sb.append(e.text).append("\n\n")
        }
        return sb.toString()
    }
}
