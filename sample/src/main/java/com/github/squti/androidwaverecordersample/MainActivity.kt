/*
 * MIT License
 *
 * Copyright (c) 2019 squti
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.squti.androidwaverecordersample

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.github.squti.androidwaverecorder.RecorderState
import com.github.squti.androidwaverecorder.WaveRecorder
import com.github.squti.androidwaverecordersample.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val PERMISSIONS_REQUEST_RECORD_AUDIO = 77

    private lateinit var waveRecorder: WaveRecorder
    private lateinit var outputFile: File

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)


        waveRecorder = WaveRecorder(
            coroutineScope = lifecycleScope,
            waveConfig = {
                sampleRate = 44100
                channels = AudioFormat.CHANNEL_IN_STEREO
                audioEncoding = AudioFormat.ENCODING_PCM_16BIT
            },
            onAmplitudeListener = {
                Log.i(TAG, "OnAmplitudeListener: $it")
            },
            onStateChangedListener = { _, newValue ->
                when (newValue) {
                    RecorderState.RECORDING -> onRecordingStarted()
                    RecorderState.STOPPED -> onRecordingStopped()
                    RecorderState.PAUSED -> pauseRecording()
                }
            },
            onTimeElapsed = {
                Log.e(TAG, "onCreate: time elapsed $it")
                binding.timeTextView.text = formatTimeUnit(it * 1000)
            },
        )

        binding.startStopRecordingButton.setOnClickListener {

            if (waveRecorder.recordingState != RecorderState.RECORDING) {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    )
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        PERMISSIONS_REQUEST_RECORD_AUDIO
                    )
                } else {
                    outputFile = waveRecorder.startRecording(
                        context = this,
                        fileName = "${UUID.randomUUID()}_${System.currentTimeMillis()}.wav"
                    )
                }
                Dispatchers.IO
            } else {
                waveRecorder.stopRecording()
            }
        }

        binding.pauseResumeRecordingButton.setOnClickListener {
            if (waveRecorder.recordingState != RecorderState.PAUSED) {
                waveRecorder.pauseRecording()
            } else {
                waveRecorder.resumeRecording()
            }
        }
        binding.showAmplitudeSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                binding.amplitudeTextView.text = "Amplitude : 0"
                binding.amplitudeTextView.visibility = View.VISIBLE
                waveRecorder.onAmplitudeListener = {
                    lifecycleScope.launch(Dispatchers.Main) {
                        binding.amplitudeTextView.text = "Amplitude : $it"
                    }
                }

            } else {
                binding.amplitudeTextView.visibility = View.GONE
            }
        }

        binding.noiseSuppressorSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            waveRecorder.noiseSuppressorActive = isChecked
            if (isChecked)
                Toast.makeText(this, "Noise Suppressor Activated", Toast.LENGTH_SHORT).show()

        }
    }

    private fun onRecordingStarted() {
        Log.d(TAG, waveRecorder.audioSessionId.toString())
        binding.messageTextView.visibility = View.GONE
        binding.recordingTextView.text = "Recording..."
        binding.recordingTextView.visibility = View.VISIBLE
        binding.startStopRecordingButton.text = "STOP"
        binding.pauseResumeRecordingButton.text = "PAUSE"
        binding.pauseResumeRecordingButton.visibility = View.VISIBLE
        binding.noiseSuppressorSwitch.isEnabled = false
    }

    private fun onRecordingStopped() {
        binding.recordingTextView.visibility = View.GONE
        binding.messageTextView.visibility = View.VISIBLE
        binding.pauseResumeRecordingButton.visibility = View.GONE
        binding.showAmplitudeSwitch.isChecked = false
        Toast.makeText(this, "File saved at : ${outputFile.absolutePath}", Toast.LENGTH_LONG).show()
        binding.startStopRecordingButton.text = "START"
        binding.noiseSuppressorSwitch.isEnabled = true
    }

    private fun pauseRecording() {
        binding.recordingTextView.text = "PAUSE"
        binding.pauseResumeRecordingButton.text = "RESUME"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSIONS_REQUEST_RECORD_AUDIO -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    outputFile = waveRecorder.startRecording(
                        context = this,
                        fileName = "${UUID.randomUUID()}_${System.currentTimeMillis()}.wav"
                    )
                }
                return
            }

            else -> {
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    private fun formatTimeUnit(timeInMilliseconds: Long): String {
        return try {
            String.format(
                Locale.getDefault(),
                "%02d:%02d",
                TimeUnit.MILLISECONDS.toMinutes(timeInMilliseconds),
                TimeUnit.MILLISECONDS.toSeconds(timeInMilliseconds) - TimeUnit.MINUTES.toSeconds(
                    TimeUnit.MILLISECONDS.toMinutes(timeInMilliseconds)
                )
            )
        } catch (e: Exception) {
            "00:00"
        }
    }
}
