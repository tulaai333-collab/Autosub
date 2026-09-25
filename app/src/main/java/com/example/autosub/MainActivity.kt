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
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
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

                findViewById<TextView>(
                    R.id.txtVideo
                ).text = "Video: $name"

                findViewById<TextView>(
                    R.id.txtStatus
                ).text =
                    "Đã chọn video. Sẵn sàng nhận diện."
            }
        }

    private val createSrt =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument(
                "application/x-subrip"
            )
        ) { uri ->

            if (uri != null) {

                val subtitle =
                    findViewById<EditText>(
                        R.id.edtSubtitle
                    ).text.toString()

                contentResolver
                    .openOutputStream(uri)
                    ?.use { output ->

                        output.write(
                            subtitle.toByteArray(
                                Charsets.UTF_8
                            )
                        )
                    }

                findViewById<TextView>(
                    R.id.txtStatus
                ).text =
                    "Đã xuất phụ đề thành công."

                Toast.makeText(
                    this,
                    "Xuất SRT thành công",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_main
        )

        findViewById<Button>(
            R.id.btnChooseVideo
        ).setOnClickListener {
            pickVideo.launch("video/*")
        }

        findViewById<Button>(
            R.id.btnAutoSubtitle
        ).setOnClickListener {

            val video = selectedVideo

            if (video == null) {

                Toast.makeText(
                    this,
                    "Hãy chọn video trước.",
                    Toast.LENGTH_SHORT
                ).show()

                return@setOnClickListener
            }

            val button =
                findViewById<Button>(
                    R.id.btnAutoSubtitle
                )

            button.isEnabled = false

            lifecycleScope.launch {

                try {

                    runAutoSubtitle(video)

                } catch (e: Exception) {

                    findViewById<TextView>(
                        R.id.txtStatus
                    ).text =
                        "Lỗi: ${e.message}"

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

        findViewById<Button>(
            R.id.btnExport
        ).setOnClickListener {

            val subtitle =
                findViewById<EditText>(
                    R.id.edtSubtitle
                ).text.toString().trim()

            if (subtitle.isEmpty()) {

                Toast.makeText(
                    this,
                    "Chưa có phụ đề.",
                    Toast.LENGTH_SHORT
                ).show()

                return@setOnClickListener
            }

            createSrt.launch(
                "autosub.srt"
            )
        }
    }

    private suspend fun runAutoSubtitle(
        videoUri: Uri
    ) {

        updateStatus(
            "Đang chuẩn bị âm thanh..."
        )

        val audioFile =
            File(
                cacheDir,
                "autosub_audio.wav"
            )

        withContext(Dispatchers.IO) {

            extractAudioToWav(
                videoUri,
                audioFile
            )
        }

        updateStatus(
            "Đã tách âm thanh. Đang kiểm tra model..."
        )

        val modelFile =
            File(
                filesDir,
                "models/ggml-base.bin"
            )

        if (!modelFile.exists()) {

            updateStatus(
                "Đang tải Whisper model ~142 MB..."
            )

            withContext(Dispatchers.IO) {
                downloadWhisperModel(modelFile)
            }
        }

        updateStatus(
            "Đang nạp Whisper..."
        )

        val model =
            Whisper.loadModel(
                this,
                modelFile.absolutePath
            )

        try {

            updateStatus(
                "Whisper đang nhận diện lời thoại..."
            )

            val result =
                Whisper.transcribe(
                    model,
                    audioFile.absolutePath,
                    WhisperConfig(
                        language = "vi"
                    )
                )

            val srt =
                buildString {

                    result.segments.forEachIndexed {
                            index,
                            segment ->

                        append(index + 1)
                        append("\n")

                        append(
                            formatSrtTime(
                                segment.startMs
                            )
                        )

                        append(" --> ")

                        append(
                            formatSrtTime(
                                segment.endMs
                            )
                        )

                        append("\n")

                        append(
                            segment.text.trim()
                        )

                        append("\n\n")
                    }
                }

            findViewById<EditText>(
                R.id.edtSubtitle
            ).setText(srt)

            updateStatus(
                "Hoàn tất! Đã tạo ${result.segments.size} đoạn phụ đề."
            )

            Toast.makeText(
                this,
                "Đã nhận diện lời thoại!",
                Toast.LENGTH_SHORT
            ).show()

        } finally {

            Whisper.releaseModel(model)

            audioFile.delete()
        }
    }

    private fun downloadWhisperModel(
        targetFile: File
    ) {

        targetFile.parentFile?.mkdirs()

        val url =
            URL(
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"
            )

        val connection =
            url.openConnection()
                    as HttpURLConnection

        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"

        connection.connect()

        if (connection.responseCode !in 200..299) {

            val code =
                connection.responseCode

            connection.disconnect()

            throw Exception(
                "Không tải được model. HTTP $code"
            )
        }

        val total =
            connection.contentLengthLong

        var downloaded = 0L

        connection.inputStream.use { input ->

            FileOutputStream(
                targetFile
            ).use { output ->

                val buffer =
                    ByteArray(8192)

                while (true) {

                    val count =
                        input.read(buffer)

                    if (count == -1) {
                        break
                    }

                    output.write(
                        buffer,
                        0,
                        count
                    )

                    downloaded += count

                    if (total > 0) {

                        val percent =
                            (
                                downloaded * 100L /
                                    total
                            ).toInt()

                        runOnUiThread {

                            findViewById<TextView>(
                                R.id.txtStatus
                            ).text =
                                "Đang tải Whisper: $percent%"
                        }
                    }
                }
            }
        }

        connection.disconnect()
    }

    private fun extractAudioToWav(
        uri: Uri,
        outputFile: File
    ) {

        val extractor =
            MediaExtractor()

        contentResolver
            .openFileDescriptor(
                uri,
                "r"
            )
            ?.use { descriptor ->

                extractor.setDataSource(
                    descriptor.fileDescriptor
                )
            }
            ?: throw Exception(
                "Không đọc được video."
            )

        var audioTrack = -1

        for (
            i in 0 until extractor.trackCount
        ) {

            val format =
                extractor.getTrackFormat(i)

            val mime =
                format.getString(
                    MediaFormat.KEY_MIME
                )

            if (
                mime != null &&
                mime.startsWith("audio/")
            ) {

                audioTrack = i
                break
            }
        }

        if (audioTrack == -1) {

            extractor.release()

            throw Exception(
                "Video không có audio."
            )
        }

        val format =
            extractor.getTrackFormat(
                audioTrack
            )

        val mime =
            format.getString(
                MediaFormat.KEY_MIME
            )
                ?: throw Exception(
                    "Không xác định được codec audio."
                )

        val sampleRate =
            if (
                format.containsKey(
                    MediaFormat.KEY_SAMPLE_RATE
                )
            ) {
                format.getInteger(
                    MediaFormat.KEY_SAMPLE_RATE
                )
            } else {
                44100
            }

        val channels =
            if (
                format.containsKey(
                    MediaFormat.KEY_CHANNEL_COUNT
                )
            ) {
                format.getInteger(
                    MediaFormat.KEY_CHANNEL_COUNT
                )
            } else {
                2
            }

        val decoder =
            MediaCodec.createDecoderByType(
                mime
            )

        extractor.selectTrack(
            audioTrack
        )

        decoder.configure(
            format,
            null,
            null,
            0
        )

        decoder.start()

        var totalPcmBytes = 0L
        var inputDone = false
        var outputDone = false

        val bufferInfo =
            MediaCodec.BufferInfo()

        val output =
            FileOutputStream(
                outputFile
            )

        writeWavHeader(
            output,
            0,
            sampleRate,
            channels
        )

        try {

            while (!outputDone) {

                if (!inputDone) {

                    val inputIndex =
                        decoder.dequeueInputBuffer(
                            10000
                        )

                    if (inputIndex >= 0) {

                        val inputBuffer =
                            decoder.getInputBuffer(
                                inputIndex
                            )

                        if (inputBuffer != null) {

                            inputBuffer.clear()

                            val sampleSize =
                                extractor.readSampleData(
                                    inputBuffer,
                                    0
                                )

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

                                val sampleTime =
                                    extractor.sampleTime

                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    sampleTime,
                                    0
                                )

                                extractor.advance()
                            }
                        }
                    }
                }

                when (
                    val outputIndex =
                        decoder.dequeueOutputBuffer(
                            bufferInfo,
                            10000
                        )
                ) {

                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Codec đã báo format output.
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // Chưa có dữ liệu, thử lại.
                    }

                    else -> {

                        if (outputIndex >= 0) {

                            val outputBuffer =
                                decoder.getOutputBuffer(
                                    outputIndex
                                )

                            if (
                                outputBuffer != null &&
                                bufferInfo.size > 0
                            ) {

                                outputBuffer.position(
                                    bufferInfo.offset
                                )

                                outputBuffer.limit(
                                    bufferInfo.offset +
                                        bufferInfo.size
                                )

                                val bytes =
                                    ByteArray(
                                        bufferInfo.size
                                    )

                                outputBuffer.get(
                                    bytes
                                )

                                output.write(
                                    bytes
                                )

                                totalPcmBytes +=
                                    bytes.size
                            }

                            if (
                                (
                                    bufferInfo.flags and
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    ) != 0
                            ) {
                                outputDone = true
                            }

                            decoder.releaseOutputBuffer(
                                outputIndex,
                                false
                            )
                        }
                    }
                }
            }

        } finally {

            output.flush()
            output.close()

            try {
                decoder.stop()
            } catch (_: Exception) {
            }

            decoder.release()
            extractor.release()
        }

        updateWavHeader(
            outputFile,
            totalPcmBytes,
            sampleRate,
            channels
        )
    }

    private fun writeWavHeader(
        output: FileOutputStream,
        dataSize: Long,
        sampleRate: Int,
        channels: Int
    ) {

        val header =
            ByteBuffer
                .allocate(44)
                .order(
                    ByteOrder.LITTLE_ENDIAN
                )

        header.put(
            byteArrayOf(
                'R'.code.toByte(),
                'I'.code.toByte(),
                'F'.code.toByte(),
                'F'.code.toByte()
            )
        )

        header.putInt(
            (36 + dataSize).toInt()
        )

        header.put(
            byteArrayOf(
                'W'.code.toByte(),
                'A'.code.toByte(),
                'V'.code.toByte(),
                'E'.code.toByte()
            )
        )

        header.put(
            byteArrayOf(
                'f'.code.toByte(),
                'm'.code.toByte(),
                't'.code.toByte(),
                ' '.code.toByte()
            )
        )

        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())

        header.putInt(sampleRate)

        val byteRate =
            sampleRate *
                channels *
                2

        header.putInt(byteRate)

        header.putShort(
            (channels * 2).toShort()
        )

        header.putShort(16)

        header.put(
            byteArrayOf(
                'd'.code.toByte(),
                'a'.code.toByte(),
                't'.code.toByte(),
                'a'.code.toByte()
            )
        )

        header.putInt(
            dataSize.toInt()
        )

        output.write(
            header.array()
        )
    }

    private fun updateWavHeader(
        file: File,
        dataSize: Long,
        sampleRate: Int,
        channels: Int
    ) {

        RandomAccessFile(
            file,
            "rw"
        ).use { raf ->

            raf.seek(4)

            raf.writeIntLE(
                (36 + dataSize).toInt()
            )

            raf.seek(24)

            raf.writeIntLE(
                sampleRate
            )

            raf.seek(28)

            raf.writeIntLE(
                sampleRate *
                    channels *
                    2
            )

            raf.seek(40)

            raf.writeIntLE(
                dataSize.toInt()
            )
        }
    }

    private fun RandomAccessFile.writeIntLE(
        value: Int
    ) {

        write(
            byteArrayOf(
                (value and 0xff).toByte(),
                ((value shr 8) and 0xff).toByte(),
                ((value shr 16) and 0xff).toByte(),
                ((value shr 24) and 0xff).toByte()
            )
        )
    }

    private fun formatSrtTime(
        milliseconds: Long
    ): String {

        val hours =
            milliseconds / 3_600_000

        val minutes =
            (milliseconds % 3_600_000) /
                60_000

        val seconds =
            (milliseconds % 60_000) /
                1_000

        val millis =
            milliseconds % 1_000
        return String.format(
            "%02d:%02d:%02d,%03d",
            hours,
            minutes,
            seconds,
            millis
        )
    }
}
