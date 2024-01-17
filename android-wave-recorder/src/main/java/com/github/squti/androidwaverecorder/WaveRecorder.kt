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

package com.github.squti.androidwaverecorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.IllegalStateException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.properties.Delegates

/**
 * The WaveRecorder class used to record Waveform audio file using AudioRecord class to get the audio stream in PCM encoding
 * and then convert it to WAVE file (WAV due to its filename extension) by adding appropriate headers. This class uses
 * Kotlin Coroutine with IO dispatcher to writing input data on storage asynchronously.
 * @property filePath the path of the file to be saved.
 */
class WaveRecorder (
    private val coroutineScope: CoroutineScope,
    /**
     * Configuration for recording audio file.
     */
    waveConfig: WaveConfig.() -> Unit = { },
    /**
     * Register a callback to be invoked in every recorded chunk of audio data
     * to get max amplitude of that chunk.
     */
    var onAmplitudeListener: (Int) -> Unit = { },
    /**
     * Register a callback to be invoked in recording state changes
     */
    var onStateChangedListener: (oldValue: RecorderState, newValue: RecorderState) -> Unit = { _, _ -> },
    /**
     * Register a callback to get elapsed recording time in seconds
     */
    var onTimeElapsed: (Long) -> Unit = { },
) {

    private lateinit var filePolicy: FilePolicy

    var waveConfig: WaveConfig = WaveConfig().apply(waveConfig)

    /**
     * Activates Noise Suppressor during recording if the device implements noise
     * suppression.
     */
    var noiseSuppressorActive: Boolean = false

    /**
     * The ID of the audio session this WaveRecorder belongs to.
     * The default value is -1 which means no audio session exist.
     */
    var audioSessionId: Int = -1
        private set

    var recordingState: RecorderState
            by Delegates.observable(RecorderState.STOPPED) { _, oldValue, newValue ->
                onStateChangedListener(oldValue, newValue)
            }
        private set

    private var audioRecorder: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var timeModulus = 1

    fun startRecording(
        context: Context,
        fileName: String,
    ): File = startRecording(
        filePolicy = FilePolicy.InternalStorage(context, fileName)
    )

    fun startRecording(
        filePath: String,
    ): File = startRecording(
        filePolicy = FilePolicy.ExternalStorage(filePath)
    )

    /**
     * Starts audio recording asynchronously and writes recorded data chunks on storage.
     */
    @SuppressLint("MissingPermission")
    fun startRecording(
        filePolicy: FilePolicy,
    ): File {
        if (recordingState != RecorderState.STOPPED) {
            throw IllegalStateException("Recording already started!")
        }
        this.filePolicy = filePolicy

        if (!isAudioRecorderInitialized()) {
            audioRecorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                waveConfig.sampleRate,
                waveConfig.channels,
                waveConfig.audioEncoding,
                AudioRecord.getMinBufferSize(
                    waveConfig.sampleRate,
                    waveConfig.channels,
                    waveConfig.audioEncoding
                )
            )
            timeModulus = bitPerSample(waveConfig.audioEncoding) * waveConfig.sampleRate / 8
            if (waveConfig.channels == AudioFormat.CHANNEL_IN_STEREO)
                timeModulus *= 2

            audioSessionId = audioRecorder?.audioSessionId ?: -1
        }

        recordingState = RecorderState.RECORDING

        audioRecorder?.startRecording()

        if (noiseSuppressorActive && audioSessionId != -1) {
            noiseSuppressor = NoiseSuppressor.create(audioSessionId)
        }

        coroutineScope.launch(Dispatchers.IO) {
            writeAudioDataToStorage(filePolicy)
        }

        return filePolicy.file
    }

    private suspend fun writeAudioDataToStorage(filePolicy: FilePolicy) {
        val bufferSize = AudioRecord.getMinBufferSize(
            waveConfig.sampleRate,
            waveConfig.channels,
            waveConfig.audioEncoding
        )
        val data = ByteArray(bufferSize)

        val file = filePolicy.file
        val outputStream = filePolicy.fileOutputStream

        while (recordingState == RecorderState.RECORDING && audioRecorder != null) {
            val operationStatus = audioRecorder?.read(data, 0, bufferSize) ?: return

            if (AudioRecord.ERROR_INVALID_OPERATION != operationStatus) {
                if (recordingState != RecorderState.PAUSED) outputStream.write(data)

                withContext(Dispatchers.Main) {
                    val audioLengthInSeconds: Long = file.length() / timeModulus
                    onAmplitudeListener(calculateAmplitudeMax(data))
                    onTimeElapsed(audioLengthInSeconds)
                }
            }
        }

        outputStream.close()
        noiseSuppressor?.release()
    }

    private fun calculateAmplitudeMax(data: ByteArray): Int {
        val shortData = ShortArray(data.size / 2)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            .get(shortData)

        return shortData.maxOrNull()?.toInt() ?: 0
    }

    /**
     * Stops audio recorder and release resources then writes recorded file headers.
     */
    fun stopRecording() {
        if (recordingState == RecorderState.STOPPED) {
            throw IllegalStateException("Recording already stopped!")
        }
        if (isAudioRecorderInitialized()) {
            audioRecorder?.stop()
            audioRecorder?.release()
            audioSessionId = -1
            WaveHeaderWriter(
                file = filePolicy.file,
                inputStream = filePolicy.fileInputStream,
                waveConfig = waveConfig
            ).writeHeader()
            recordingState = RecorderState.STOPPED
        }

    }

    private fun isAudioRecorderInitialized(): Boolean =
        audioRecorder != null && audioRecorder?.state == AudioRecord.STATE_INITIALIZED

    fun pauseRecording() {
        recordingState = RecorderState.PAUSED
    }

    fun resumeRecording() {
        recordingState = RecorderState.RECORDING
    }

    fun update(
        /**
         * Configuration for recording audio file.
         */
        waveConfig: (WaveConfig.() -> Unit)? = null,
        /**
         * Register a callback to be invoked in every recorded chunk of audio data
         * to get max amplitude of that chunk.
         */
        onAmplitudeListener: ((Int) -> Unit)? = null,
        /**
         * Register a callback to be invoked in recording state changes
         */
        onStateChangedListener: ((RecorderState, RecorderState) -> Unit)? = null,
        /**
         * Register a callback to get elapsed recording time in seconds
         */
        onTimeElapsed: ((Long) -> Unit)? = null,
    ) {
        if (waveConfig != null) {
            if (recordingState != RecorderState.STOPPED) {
                throw IllegalStateException("Please stop the recording before updating waveConfig!")
            }
            this.waveConfig = WaveConfig().apply(waveConfig)
            audioRecorder?.release()
            audioRecorder = null
        }
        if (onAmplitudeListener != null) {
            this.onAmplitudeListener = onAmplitudeListener
        }
        if (onStateChangedListener != null) {
            this.onStateChangedListener = onStateChangedListener
        }
        if (onTimeElapsed != null) {
            this.onTimeElapsed = onTimeElapsed
        }
    }

    sealed interface FilePolicy {

        val file: File
        val fileOutputStream: FileOutputStream
        val fileInputStream: FileInputStream

        open class InternalStorage(
            private val context: Context,
            private val fileName: String,
        ) : FilePolicy {
            override val file: File
                get() = context.getFileStreamPath(fileName)
            override val fileOutputStream: FileOutputStream
                get() = context.openFileOutput(fileName, Context.MODE_PRIVATE)
            override val fileInputStream: FileInputStream
                get() = context.openFileInput(fileName)


        }

        open class ExternalStorage(
            private val filePath: String
        ) : FilePolicy {
            override val file: File
                get() = File(filePath)
            override val fileOutputStream: FileOutputStream
                get() = File(filePath).outputStream()
            override val fileInputStream: FileInputStream
                get() = File(filePath).inputStream()

        }
    }
}
