package com.myanmartts.studio

import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var voiceSpinner: Spinner
    private lateinit var previewBtn: Button
    private lateinit var scriptInput: EditText
    private lateinit var speedSeek: SeekBar
    private lateinit var speedLabel: TextView
    private lateinit var generateBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var playerPanel: android.view.View
    private lateinit var playerSeek: SeekBar
    private lateinit var playerCurrent: TextView
    private lateinit var playerTotal: TextView
    private lateinit var playerPlayPauseBtn: Button
    private var playerFile: File? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            val mp = mediaPlayer
            if (mp != null && mp.isPlaying) {
                try {
                    val pos = mp.currentPosition
                    playerSeek.progress = pos
                    playerCurrent.text = formatDuration(pos.toLong())
                } catch (_: Exception) {
                }
                uiHandler.postDelayed(this, 500)
            }
        }
    }
    private lateinit var downloadAudioBtn: Button
    private lateinit var downloadSrtBtn: Button

    private var voiceMap: LinkedHashMap<String, String> = linkedMapOf()
    private var speedPercent: Int = TtsLogic.DEFAULT_SPEED
    private var lastAudioFile: File? = null
    private var lastSrtFile: File? = null
    private var mediaPlayer: MediaPlayer? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val client = EdgeTtsClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        voiceSpinner = findViewById(R.id.voiceSpinner)
        previewBtn = findViewById(R.id.previewBtn)
        scriptInput = findViewById(R.id.scriptInput)
        speedSeek = findViewById(R.id.speedSeek)
        speedLabel = findViewById(R.id.speedLabel)
        generateBtn = findViewById(R.id.generateBtn)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        playerPanel = findViewById(R.id.playerPanel)
        playerSeek = findViewById(R.id.playerSeek)
        playerCurrent = findViewById(R.id.playerCurrent)
        playerTotal = findViewById(R.id.playerTotal)
        playerPlayPauseBtn = findViewById(R.id.playerPlayPauseBtn)
        downloadAudioBtn = findViewById(R.id.downloadAudioBtn)
        downloadSrtBtn = findViewById(R.id.downloadSrtBtn)

        speedSeek.max = 150
        speedSeek.progress = TtsLogic.DEFAULT_SPEED + 50
        updateSpeedLabel()
        speedSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                speedPercent = progress - 50
                updateSpeedLabel()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        generateBtn.setOnClickListener { generate() }
        previewBtn.setOnClickListener { previewVoice() }
        playerPlayPauseBtn.setOnClickListener { togglePlayPause() }
        downloadAudioBtn.setOnClickListener { downloadFile(lastAudioFile, "audio/mpeg") }
        downloadSrtBtn.setOnClickListener { downloadFile(lastSrtFile, "text/plain") }

        playerSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val mp = mediaPlayer
                    if (mp != null) {
                        try {
                            mp.seekTo(progress)
                            playerCurrent.text = formatDuration(progress.toLong())
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        loadVoices()
    }

    private fun updateSpeedLabel() {
        val rate = TtsLogic.speedToRate(speedPercent)
        speedLabel.text = "အသံ Speed: $rate"
    }

    // ----------------------------------------------------------
    // Voices
    // ----------------------------------------------------------

    private fun loadVoices() {
        statusText.text = "Voice list ရယူနေသည်..."
        worker.execute {
            val voices = try {
                TtsLogic.selectVoices(client.fetchVoices())
            } catch (e: Exception) {
                TtsLogic.selectVoices(TtsLogic.fallbackVoices())
            }
            voiceMap = TtsLogic.buildVoiceDisplayMap(voices)
            runOnUiThread {
                val labels = voiceMap.keys.toList()
                voiceSpinner.adapter = ArrayAdapter(
                    this, android.R.layout.simple_spinner_dropdown_item, labels
                )
                val defaultLabel = voiceMap.entries
                    .firstOrNull { it.value == TtsLogic.DEFAULT_VOICE_ID }?.key
                    ?: labels.firstOrNull()
                val idx = labels.indexOf(defaultLabel)
                if (idx >= 0) voiceSpinner.setSelection(idx)
                statusText.text = "Voice ${labels.size} ခု အသင့်ဖြစ်ပါပြီ။"
            }
        }
    }

    // ----------------------------------------------------------
    // Voice preview (short sample)
    // ----------------------------------------------------------

    private fun previewVoice() {
        val label = voiceSpinner.selectedItem as? String
        val voiceId = label?.let { voiceMap[it] }
        if (voiceId == null) {
            Toast.makeText(this, "Voice ကို ပြန်ရွေးပေးပါ။", Toast.LENGTH_SHORT).show()
            return
        }
        val rate = TtsLogic.speedToRate(speedPercent)
        setBusy(true)
        stopPlayback()
        worker.execute {
            try {
                val sample = "မင်္ဂလာပါ။ ကျွန်တော်က $label ပါ။ ဒါက အသံနမူနာ ဖြစ်ပါတယ်။"
                runOnUiThread { statusText.text = "အသံနမူနာ ထုတ်နေသည်..." }
                val mp3 = client.synthesize(sample, voiceId, rate)
                if (mp3.size < 100) throw Exception("Edge TTS က အသံဖိုင်အလွတ် ပြန်ပေးထားသည်။")
                val previewFile = File(cacheDir, "preview.mp3")
                previewFile.writeBytes(mp3)
                runOnUiThread {
                    statusText.text = "အသင့်ဖြစ်ပါပြီ။"
                    setBusy(false)
                    playFile(previewFile)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "နမူနာထုတ်၍မရပါ: ${e.message?.take(200)}"
                    setBusy(false)
                }
            }
        }
    }

    // ----------------------------------------------------------
    // Generation
    // ----------------------------------------------------------

    private fun generate() {
        val script = TtsLogic.normalizeScript(scriptInput.text.toString())
        if (script.isEmpty()) {
            Toast.makeText(this, "Script ထည့်ပေးပါ။", Toast.LENGTH_SHORT).show()
            return
        }
        val label = voiceSpinner.selectedItem as? String
        if (label == null) {
            Toast.makeText(this, "Voice ကို ပြန်ရွေးပေးပါ။", Toast.LENGTH_SHORT).show()
            return
        }
        val voiceId = voiceMap[label]
        if (voiceId == null) {
            Toast.makeText(this, "Voice ကို ပြန်ရွေးပေးပါ။", Toast.LENGTH_SHORT).show()
            return
        }
        val chunks: List<String>
        try {
            chunks = TtsLogic.splitForTts(script)
        } catch (e: Exception) {
            statusText.text = "Error: ${e.message}"
            return
        }
        if (chunks.isEmpty()) {
            Toast.makeText(this, "အသံထုတ်ရန် Script မရှိပါ။", Toast.LENGTH_SHORT).show()
            return
        }
        val rate = TtsLogic.speedToRate(speedPercent)

        setBusy(true)
        stopPlayback()
        worker.execute {
            try {
                val combined = ByteArrayOutputStream()
                chunks.forEachIndexed { index, chunk ->
                    runOnUiThread {
                        val pct = (index * 75) / chunks.size
                        progressBar.progress = pct
                        statusText.text = "Audio ထုတ်နေသည် ${index + 1}/${chunks.size}"
                    }
                    val mp3 = client.synthesize(chunk, voiceId, rate)
                    if (mp3.size < 100) throw Exception("Edge TTS က အသံဖိုင်အလွတ် ပြန်ပေးထားသည်။")
                    combined.write(mp3)
                }
                runOnUiThread {
                    progressBar.progress = 80
                    statusText.text = "Audio ပေါင်းနေသည်..."
                }
                val audioBytes = combined.toByteArray()
                if (audioBytes.isEmpty()) throw Exception("ပေါင်းစပ်ထားသော Audio ဖိုင်တွင် အသံမရှိပါ။")

                val stamp = System.currentTimeMillis()
                val audioFile = File(getExternalFilesDir(null), "tts_$stamp.mp3")
                audioFile.writeBytes(audioBytes)

                val durationMs = readDurationMs(audioFile)

                runOnUiThread { statusText.text = "SRT ပြုလုပ်နေသည်..." }
                val subtitleTexts = TtsLogic.splitForSrt(script)
                TtsLogic.verifyTextIntegrity(script, subtitleTexts)
                val entries = TtsLogic.makeSrtEntries(subtitleTexts, durationMs)
                // utf-8-sig BOM like the Python app
                val srtBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                    TtsLogic.buildSrt(entries).toByteArray(Charsets.UTF_8)
                val srtFile = File(getExternalFilesDir(null), "tts_$stamp.srt")
                srtFile.writeBytes(srtBytes)

                lastAudioFile = audioFile
                lastSrtFile = srtFile
                runOnUiThread {
                    progressBar.progress = 100
                    statusText.text =
                        "အောင်မြင်ပါသည်!\nVoice: $label\nSpeed: $rate\n" +
                            "Script: ${script.length} characters\nAudio chunks: ${chunks.size}\nSRT lines: ${subtitleTexts.size}"
                    setupPlayer(audioFile, durationMs)
                    downloadAudioBtn.visibility = View.VISIBLE
                    downloadSrtBtn.visibility = View.VISIBLE
                    setBusy(false)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Error: ${e.message?.take(300)}"
                    setBusy(false)
                }
            }
        }
    }

    private fun readDurationMs(file: File): Long {
        return try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(file.absolutePath)
            val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            mmr.release()
            ms ?: 0L
        } catch (e: Exception) {
            0L
        }.let { if (it > 0) it else 60_000L } // fallback estimate
    }

    private fun setBusy(busy: Boolean) {
        generateBtn.isEnabled = !busy
        previewBtn.isEnabled = !busy
        voiceSpinner.isEnabled = !busy
        scriptInput.isEnabled = !busy
        speedSeek.isEnabled = !busy
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        if (!busy) progressBar.progress = 0
    }

    // ----------------------------------------------------------
    // Audio player (seek bar + time + play/pause)
    // ----------------------------------------------------------

    /** Show the player panel for a file without starting playback. */
    private fun setupPlayer(file: File, durationMs: Long) {
        stopPlayback()
        playerFile = file
        val dur = durationMs.coerceAtLeast(1L)
        playerSeek.max = dur.toInt().coerceAtLeast(1)
        playerSeek.progress = 0
        playerCurrent.text = formatDuration(0)
        playerTotal.text = "⏱️ ${formatDuration(dur)}"
        playerPlayPauseBtn.text = "▶️ ဖွင့်မယ်"
        playerPanel.visibility = View.VISIBLE
    }

    /** Set up the player and start playing immediately. */
    private fun playFile(file: File) {
        setupPlayer(file, readDurationMs(file))
        startPlayback()
    }

    private fun startPlayback() {
        val file = playerFile ?: return
        stopPlayback()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            val dur = mp.duration.coerceAtLeast(1)
            playerSeek.max = dur
            playerTotal.text = "⏱️ ${formatDuration(dur.toLong())}"
            mp.setOnCompletionListener { runOnUiThread { onPlaybackComplete() } }
            mp.start()
            mediaPlayer = mp
            playerPlayPauseBtn.text = "⏸️ ရပ်မယ်"
            startProgressUpdates()
        } catch (e: Exception) {
            Toast.makeText(this, "ဖွင့်၍မရပါ: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun togglePlayPause() {
        val mp = mediaPlayer
        when {
            mp != null && mp.isPlaying -> {
                mp.pause()
                playerPlayPauseBtn.text = "▶️ ဆက်ဖွင့်မယ်"
                uiHandler.removeCallbacks(progressRunnable)
            }
            mp != null -> {
                mp.start()
                playerPlayPauseBtn.text = "⏸️ ရပ်မယ်"
                startProgressUpdates()
            }
            else -> startPlayback()
        }
    }

    private fun onPlaybackComplete() {
        uiHandler.removeCallbacks(progressRunnable)
        playerSeek.progress = 0
        playerCurrent.text = formatDuration(0)
        playerPlayPauseBtn.text = "▶️ ဖွင့်မယ်"
        stopPlayback()
    }

    private fun startProgressUpdates() {
        uiHandler.removeCallbacks(progressRunnable)
        uiHandler.post(progressRunnable)
    }

    private fun stopPlayback() {
        uiHandler.removeCallbacks(progressRunnable)
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
                it.release()
            } catch (_: Exception) {
            }
        }
        mediaPlayer = null
        if (::playerPlayPauseBtn.isInitialized) playerPlayPauseBtn.text = "▶️ ဖွင့်မယ်"
    }

    /** Save the generated file into the phone's Downloads folder (MediaStore, no permission needed). */
    private fun downloadFile(src: File?, mime: String) {
        if (src == null || !src.exists()) {
            Toast.makeText(this, "ဖိုင်မရှိသေးပါ။", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val displayName = src.name
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            // NOTE: the Downloads collection accepts any file type; the Audio
            // collection rejects RELATIVE_PATH=Download ("Primary directory
            // Download not allowed"), so use Downloads for both MP3 and SRT.
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("MediaStore ထဲ ထည့်၍မရပါ။")
            contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(src).use { inp -> inp.copyTo(out) }
            } ?: throw Exception("ဖိုင်ရေး၍မရပါ။")
            Toast.makeText(
                this,
                "Downloads folder ထဲ သိမ်းပြီးပါပြီ:\n$displayName",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download မရပါ: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    override fun onDestroy() {
        stopPlayback()
        worker.shutdownNow()
        super.onDestroy()
    }
}
