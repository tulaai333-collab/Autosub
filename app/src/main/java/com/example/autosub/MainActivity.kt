package com.example.autosub

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : AppCompatActivity() {

    private var selectedVideo: Uri? = null

    private val pickVideo =
        registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri ->
            if (uri != null) {
                selectedVideo = uri
                val name = getFileName(uri)
                findViewById<TextView>(R.id.txtVideo).text = "Video: $name"
                findViewById<TextView>(R.id.txtStatus).text =
                    "Đã chọn video. Sẵn sàng nhận diện."
            }
        }

    private val createSrt =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/x-subrip")
        ) { uri ->
            if (uri != null) {
                val subtitle =
                    findViewById<EditText>(R.id.edtSubtitle).text.toString()

                contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(subtitle.toByteArray(Charsets.UTF_8))
                }

                findViewById<TextView>(R.id.txtStatus).text =
                    "Đã xuất phụ đề thành công."

                Toast.makeText(this, "Xuất SRT thành công", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnChooseVideo).setOnClickListener {
            pickVideo.launch("video/*")
        }

        findViewById<Button>(R.id.btnAutoSubtitle).setOnClickListener {
            val video = selectedVideo

            if (video == null) {
                Toast.makeText(this, "Hãy chọn video trước.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val button = findViewById<Button>(R.id.btnAutoSubtitle)
            button.isEnabled = false

            lifecycleScope.launch {
                try {
                    runAutoSubtitle(video)
                } catch (e: Exception) {
                    findViewById<TextView>(R.id.txtStatus).text = "Lỗi: ${e.message}"
                    Toast.makeText(
                        this@MainActivity,
                        "Không thể tạo phụ đề.",
                        Toast.LENGTH_LONG
                    ).show()
                } finally {
                    button.isEnabled = true
                }
            }
        }

        findViewById<Button>(R.id.btnExport).setOnClickListener {
            val subtitle =
                findViewById<EditText>(R.id.edtSubtitle).text.toString().trim()

            if (subtitle.isEmpty()) {
                Toast.makeText(this, "Chưa có phụ đề.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            createSrt.launch("autosub.srt")
        }
    }

    private suspend fun runAutoSubtitle(videoUri: Uri) {

        updateStatus("Đang chuẩn bị âm thanh...")

        val audioFile = File(cacheDir, "autosub_audio.wav")

        withContext(Dispatchers.IO) {
            extractAudioToWav(videoUri, audioFile)
        }

        updateStatus("Đã tách âm thanh. Đang kiểm tra model...")

        val modelFile = File(filesDir, "models/ggml-small.bin")

        if (!modelFile.exists()) {
            updateStatus("Đang tải Whisper model ~466 MB...")
            withContext(Dispatchers.IO) {
                downloadWhisperModel(modelFile)
            }
        }

        updateStatus("Đang nạp Whisper...")

        val model = Whisper.loadModel(this, modelFile.absolutePath)

        try {
            updateStatus("Whisper đang nhận diện lời thoại...")

            val result =
                Whisper.transcribe(
                    model,
                    audioFile.absolutePath,
                    WhisperConfig(
                        language = "auto"
                    )
                )

            val segs = result.segments.mapNotNull { segment ->
                val text = cleanSubtitleText(segment.text)
                if (text.isEmpty()) null
                else Triple(segment.startMs, segment.endMs, text)
            }

            val viTexts = translateToVietnamese(segs.map { it.third })

            val srt = buildString {
                segs.forEachIndexed { i, (start, end, _) ->
                    append(i + 1)
                    append("\n")
                    append(formatSrtTime(start))
                    append(" --> ")
                    append(formatSrtTime(end))
                    append("\n")
                    append(splitSubtitleText(viTexts[i]))
                    append("\n\n")
                }
            }

            findViewById<EditText>(R.id.edtSubtitle).setText(srt)

            updateStatus("Hoàn tất! Đã tạo ${segs.size} đoạn phụ đề.")

            Toast.makeText(this, "Đã nhận diện lời thoại!", Toast.LENGTH_SHORT).show()

        } finally {
            Whisper.releaseModel(model)
            audioFile.delete()
        }
    }

    private suspend fun translateToVietnamese(
        texts: List<String>
    ): List<String> {
        if (texts.isEmpty()) return texts

        return try {
            val sample = texts.joinToString(" ").take(2000)

            val langTag = LanguageIdentification
                .getClient()
                .identifyLanguage(sample)
                .await()

            if (langTag == "und" || langTag == "vi") return texts

            val source = TranslateLanguage.fromLanguageTag(langTag)
                ?: return texts

            val translator = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                    .build()
            )

            try {
                updateStatus("Đang tải model dịch (lần đầu)...")
                translator
                    .downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .await()

                updateStatus("Đang dịch sang tiếng Việt...")
                texts.map { translator.translate(it).await() }
            } finally {
                translator.close()
            }
        } catch (e: Exception) {
            updateStatus("Dịch lỗi, giữ nguyên bản gốc: ${e.message}")
            texts
        }
    }

    private fun downloadWhisperModel(targetFile: File) {

        targetFile.parentFile?.mkdirs()

        val url =
            URL("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin")

        val connection = url.openConnection() as HttpURLConnection

        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"

        connection.connect()

        if (connection.responseCode !in 200..299) {
            val code = connection.responseCode
            connection.disconnect()
            throw Exception("Không tải được model. HTTP $code")
        }

        val total = connection.contentLengthLong
        var downloaded = 0L

        connection.inputStream.use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(8192)

                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break

                    output.write(buffer, 0, count)
                    downloaded += count

                    if (total > 0) {
                        val percent = (downloaded * 100L / total).toInt()
                        runOnUiThread {
                            findViewById<TextView>(R.id.txtStatus).text =
                                "Đang tải Whisper: $percent%"
                        }
                    }
                }
            }
        }

        connection.disconnect()
    }

    private fun extractAudioToWav(uri: Uri, outputFile: File) {

        val extractor = MediaExtractor()

        contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            extractor.setDataSource(descriptor.fileDescriptor)
        } ?: throw Exception("Không đọc được video.")

        var audioTrack = -1

        for (i in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(i)
            val trackMime = trackFormat.getString(MediaFormat.KEY_MIME)

            if (trackMime != null && trackMime.startsWith("audio/")) {
                audioTrack = i
                break
            }
        }

        if (audioTrack == -1) {
            extractor.release()
            throw Exception("Video không có audio.")
        }

        val format = extractor.getTrackFormat(audioTrack)

        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: throw Exception("Không xác định được codec audio.")

        val sourceSampleRate =
            if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44100
            }

        val sourceChannels =
            if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                2
            }

        val decoder = MediaCodec.createDecoderByType(mime)

        extractor.selectTrack(audioTrack)
        decoder.configure(format, null, null, 0)
        decoder.start()

        val pcmBuffer = ByteArrayOutputStream()

        var inputDone = false
        var outputDone = false

        val bufferInfo = MediaCodec.BufferInfo()

        try {
            while (!outputDone) {

                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10000)

                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)

                        if (inputBuffer != null) {
                            inputBuffer.clear()

                            val sampleSize = extractor.readSampleData(inputBuffer, 0)

                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    extractor.sampleTime,
                                    0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)) {

                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Codec đã báo format output.
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // Chưa có dữ liệu, thử lại.
                    }

                    else -> {
                        if (outputIndex >= 0) {
                            val outputBuffer = decoder.getOutputBuffer(outputIndex)

                            if (outputBuffer != null && bufferInfo.size > 0) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                                val bytes = ByteArray(bufferInfo.size)
                                outputBuffer.get(bytes)
                                pcmBuffer.write(bytes)
                            }

                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }

                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
        } finally {
            try {
                decoder.stop()
            } catch (_: Exception) {
            }
            decoder.release()
            extractor.release()
        }

        val sourcePcm = pcmBuffer.toByteArray()

        if (sourcePcm.isEmpty()) {
            throw Exception("Không lấy được dữ liệu audio.")
        }

        /*
         * Chuyển audio về: 16 kHz, mono, PCM 16-bit
         */

        val sourceFrameSize = sourceChannels * 2

        if (sourceFrameSize <= 0 || sourcePcm.size < sourceFrameSize) {
            throw Exception("Định dạng PCM audio không hợp lệ.")
        }

        val sourceFrameCount = sourcePcm.size / sourceFrameSize

        val monoSamples = IntArray(sourceFrameCount)

        var frame = 0

        while (frame < sourceFrameCount) {
            var sum = 0L
            var channel = 0

            while (channel < sourceChannels) {
                val index = frame * sourceFrameSize + channel * 2

                val low = sourcePcm[index].toInt() and 0xFF
                val high = sourcePcm[index + 1].toInt()

                val sample = low or (high shl 8)

                val signedSample =
                    if (sample and 0x8000 != 0) sample - 65536 else sample

                sum += signedSample
                channel++
            }

            monoSamples[frame] = (sum / sourceChannels).toInt()
            frame++
        }

        val targetSampleRate = 16000

        val targetFrameCount =
            (sourceFrameCount.toLong() * targetSampleRate / sourceSampleRate).toInt()

        if (targetFrameCount <= 0) {
            throw Exception("Không đủ dữ liệu để chuyển đổi audio.")
        }

        val targetPcm = ByteArrayOutputStream(targetFrameCount * 2)

        var i = 0

        while (i < targetFrameCount) {
            val sourcePosition = i.toDouble() * sourceSampleRate / targetSampleRate

            val leftIndex = sourcePosition.toInt()
            val rightIndex = minOf(leftIndex + 1, sourceFrameCount - 1)
            val fraction = sourcePosition - leftIndex

            val leftSample = monoSamples[leftIndex]
            val rightSample = monoSamples[rightIndex]

            val interpolated =
                (leftSample + (rightSample - leftSample) * fraction).toInt()

            val sample = interpolated.coerceIn(-32768, 32767)

            targetPcm.write(sample and 0xFF)
            targetPcm.write((sample shr 8) and 0xFF)

            i++
        }

        val finalPcm = targetPcm.toByteArray()

        FileOutputStream(outputFile).use { output ->
            writeWavHeader(output, finalPcm.size.toLong(), targetSampleRate, 1)
            output.write(finalPcm)
            output.flush()
        }
    }

    private fun writeWavHeader(
        output: FileOutputStream,
        dataSize: Long,
        sampleRate: Int,
        channels: Int
    ) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36 + dataSize).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))

        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)

        val byteRate = sampleRate * channels * 2
        header.putInt(byteRate)

        header.putShort((channels * 2).toShort())
        header.putShort(16)

        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize.toInt())

        output.write(header.array())
    }

    private fun updateStatus(text: String) {
        runOnUiThread {
            findViewById<TextView>(R.id.txtStatus).text = text
        }
    }

    private fun cleanSubtitleText(text: String): String {

        var result = text.replace(Regex("\\s+"), " ").trim()

        result = result
            .replace(Regex("\\s+([,.!?;:])"), "$1")
            .replace(Regex("([,.!?;:])(?=\\S)"), "$1 ")

        // Xóa từ bị lặp liên tiếp: "đúng đúng" -> "đúng"
        result = result.replace(
            Regex("(?iu)\\b([\\p{L}\\p{N}]{2,})\\b(?:\\s+\\1\\b)+"),
            "$1"
        )

        return result.trim()
    }

    private fun splitSubtitleText(text: String): String {

        if (text.length <= 42) {
            return text
        }

        // Ưu tiên ngắt dòng tại dấu câu
        val punctuationBreak = Regex("(?<=[,.!?;:])\\s+")
        val parts = text.split(punctuationBreak)

        if (parts.size > 1) {
            val first = parts[0].trim()
            val remaining = parts.drop(1).joinToString(" ").trim()

            if (first.isNotEmpty() && remaining.isNotEmpty() && first.length <= 42) {
                return "$first\n$remaining"
            }
        }

        // Không có vị trí ngắt phù hợp -> chia theo từ
        val words = text.split(" ")

        val firstLine = StringBuilder()
        val secondLine = StringBuilder()

        for (word in words) {
            if (secondLine.isEmpty() &&
                firstLine.length + word.length + (if (firstLine.isEmpty()) 0 else 1) <= 42
            ) {
                if (firstLine.isNotEmpty()) firstLine.append(" ")
                firstLine.append(word)
            } else {
                if (secondLine.isNotEmpty()) secondLine.append(" ")
                secondLine.append(word)
            }
        }

        return if (secondLine.isEmpty()) {
            firstLine.toString()
        } else {
            "$firstLine\n$secondLine"
        }
    }

    private fun formatSrtTime(milliseconds: Long): String {

        val hours = milliseconds / 3_600_000
        val minutes = (milliseconds % 3_600_000) / 60_000
        val seconds = (milliseconds % 60_000) / 1_000
        val millis = milliseconds % 1_000

        return buildString {
            append(hours.toString().padStart(2, '0'))
            append(":")
            append(minutes.toString().padStart(2, '0'))
            append(":")
            append(seconds.toString().padStart(2, '0'))
            append(",")
            append(millis.toString().padStart(3, '0'))
        }
    }

    private fun getFileName(uri: Uri): String {

        var fileName = "video"

        val cursor: android.database.Cursor? =
            contentResolver.query(uri, null, null, null, null)

        if (cursor != null) {
            try {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

                if (cursor.moveToFirst() && nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex)
                }
            } finally {
                cursor.close()
            }
        }

        return fileName
    }
}
