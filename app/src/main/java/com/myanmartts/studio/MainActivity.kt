package com.myanmartts.studio

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
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
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var voiceSpinner: Spinner
    private lateinit var scriptInput: EditText
    private lateinit var speedSeek: SeekBar
    private lateinit var speedLabel: TextView
    private lateinit var generateBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var playBtn: Button
    private lateinit var shareAudioBtn: Button
    private lateinit var shareSrtBtn: Button

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
        scriptInput = findViewById(R.id.scriptInput)
        speedSeek = findViewById(R.id.speedSeek)
        speedLabel = findViewById(R.id.speedLabel)
        generateBtn = findViewById(R.id.generateBtn)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        playBtn = findViewById(R.id.playBtn)
        shareAudioBtn = findViewById(R.id.shareAudioBtn)
        shareSrtBtn = findViewById(R.id.shareSrtBtn)

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
        playBtn.setOnClickListener { togglePlay() }
        shareAudioBtn.setOnClickListener { shareFile(lastAudioFile, "audio/mpeg") }
        shareSrtBtn.setOnClickListener { shareFile(lastSrtFile, "text/plain") }

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
    // Generation
    // ----------------------------------------------------------

    private fun generate() {
        val script = TtsLogic.normalizeScript(scriptInput.text.toString())
        if (script.isEmpty()) {
            Toast.makeText(this, "Script ထည့်ပေးပါ။", Toast.LENGTH_SHORT).show()
            return
        }
        val label = voiceSpinner.selectedItem as? String
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
                    playBtn.visibility = View.VISIBLE
                    shareAudioBtn.visibility = View.VISIBLE
                    shareSrtBtn.visibility = View.VISIBLE
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
        voiceSpinner.isEnabled = !busy
        scriptInput.isEnabled = !busy
        speedSeek.isEnabled = !busy
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        if (!busy) progressBar.progress = 0
    }

    // ----------------------------------------------------------
    // Playback + share
    // ----------------------------------------------------------

    private fun togglePlay() {
        val file = lastAudioFile ?: return
        val player = mediaPlayer
        if (player != null && player.isPlaying) {
            player.pause()
            playBtn.text = "▶️ ဆက်ဖွင့်မယ်"
            return
        }
        if (player != null) {
            player.start()
            playBtn.text = "⏸️ ရပ်မယ်"
            return
        }
        try {
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.setOnCompletionListener {
                playBtn.text = "▶️ ဖွင့်မယ်"
                stopPlayback()
            }
            mp.start()
            mediaPlayer = mp
            playBtn.text = "⏸️ ရပ်မယ်"
        } catch (e: Exception) {
            Toast.makeText(this, "ဖွင့်၍မရပါ: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPlayback() {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
                it.release()
            } catch (_: Exception) {
            }
        }
        mediaPlayer = null
        if (::playBtn.isInitialized) playBtn.text = "▶️ ဖွင့်မယ်"
    }

    private fun shareFile(file: File?, mime: String) {
        if (file == null || !file.exists()) {
            Toast.makeText(this, "ဖိုင်မရှိသေးပါ။", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "မျှဝေမယ်"))
    }

    override fun onDestroy() {
        stopPlayback()
        worker.shutdownNow()
        super.onDestroy()
    }
}
