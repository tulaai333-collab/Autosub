package com.example.autosub

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var selectedVideo: Uri? = null

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                selectedVideo = uri

                findViewById<TextView>(R.id.txtVideo).text =
                    "Video: ${getFileName(uri)}"

                findViewById<TextView>(R.id.txtStatus).text =
                    "Đã chọn video. Hãy nhập phụ đề."
            }
        }

    private val createSrt =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/x-subrip")
        ) { uri ->

            if (uri != null) {

                val text =
                    findViewById<EditText>(R.id.edtSubtitle)
                        .text.toString()

                val srt = makeSrt(text)

                contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(
                        srt.toByteArray(Charsets.UTF_8)
                    )
                }

                findViewById<TextView>(R.id.txtStatus).text =
                    "Đã xuất phụ đề thành công."

                Toast.makeText(
                    this,
                    "Xuất SRT thành công",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnChooseVideo)
            .setOnClickListener {
                pickVideo.launch("video/*")
            }

        findViewById<Button>(R.id.btnExport)
            .setOnClickListener {

                if (selectedVideo == null) {
                    Toast.makeText(
                        this,
                        "Hãy chọn video trước.",
                        Toast.LENGTH_SHORT
                    ).show()

                    return@setOnClickListener
                }

                val text =
                    findViewById<EditText>(R.id.edtSubtitle)
                        .text.toString()
                        .trim()

                if (text.isEmpty()) {
                    Toast.makeText(
                        this,
                        "Hãy nhập phụ đề.",
                        Toast.LENGTH_SHORT
                    ).show()

                    return@setOnClickListener
                }

                createSrt.launch("autosub.srt")
            }
        }
    }

    private fun makeSrt(text: String): String {

        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val result = StringBuilder()

        lines.forEachIndexed { index, line ->

            val start = index * 4
            val end = start + 4

            result.append(index + 1)
                .append("\n")

            result.append(formatTime(start))
                .append(" --> ")
                .append(formatTime(end))
                .append("\n")

            result.append(line)
                .append("\n\n")
        }

        return result.toString()
    }

    private fun formatTime(seconds: Int): String {

        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return String.format(
            "%02d:%02d:%02d,000",
            hours,
            minutes,
            secs
        )
    }

    private fun getFileName(uri: Uri): String {

        var name = "video"

        contentResolver.query(
            uri,
            null,
            null,
            null,
            null
        )?.use { cursor ->

            val index =
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

            if (cursor.moveToFirst() && index >= 0) {
                name = cursor.getString(index)
            }
        }

        return name
    }
}
