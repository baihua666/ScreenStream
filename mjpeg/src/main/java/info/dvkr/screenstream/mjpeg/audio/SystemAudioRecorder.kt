package info.dvkr.screenstream.mjpeg.audio

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodecInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.Volatile
import kotlin.math.max

public class SystemAudioRecorder {

    public interface SamplesReadyCallback {
        public fun onAudioRecordSamplesReady(samples: AudioSamples?)
    }

    private val TAG = "SystemAudioRecorder"

    private var audioRecord: AudioRecord? = null

    private var byteBuffer: ByteBuffer? = null

    private var audioThread: AudioRecordThread? = null

    private var executor: ScheduledExecutorService? = null
    private var future: ScheduledFuture<String>? = null

    public var audioSamplesReadyCallback: SamplesReadyCallback? = null

    public lateinit var emptyBytes: ByteArray


    // We ask for a native buffer size of BUFFER_SIZE_FACTOR * (minimum required
    // buffer size). The extra space is allocated to guard against glitches under
    // high load.
    private val BUFFER_SIZE_FACTOR: Int = 2

    // Requested size of each recorded buffer provided to the client.
    private val CALLBACK_BUFFER_SIZE_MS: Int = 10

    // Average number of callbacks per second.
    private val BUFFERS_PER_SECOND: Int =
        1000 / CALLBACK_BUFFER_SIZE_MS

    // Time to wait before checking recording status after start has been called. Tests have
    // shown that the result can sometimes be invalid (our own status might be missing) if we check
    // directly after start.
    private val CHECK_REC_STATUS_DELAY_MS: Int = 100

    // The AudioRecordJavaThread is allowed to wait for successful call to join()
    // but the wait times out afther this amount of time.
    private val AUDIO_RECORD_THREAD_JOIN_TIMEOUT_MS: Long = 2000

    private val sampleRate = 16000
//    private val sampleRate = 44100

    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val aacProfile = MediaCodecInfo.CodecProfileLevel.AACObjectLC


    public fun startRecording(mediaProjection: MediaProjection) : Int {
        val channels = 1
//        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO

        val bytesPerFrame: Int =
            channels * getBytesPerSample(audioFormat)
        val framesPerBuffer: Int =
            sampleRate / BUFFERS_PER_SECOND
        byteBuffer = ByteBuffer.allocateDirect(bytesPerFrame * framesPerBuffer)

        emptyBytes = ByteArray(byteBuffer!!.capacity())


//        val mediaProjectionManager = context.getSystemService(MediaProjectionManager::class.java)
//        val mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, intent)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return -1
        }

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
//        val minBufferSize = 4096


        val bufferSizeInBytes = max(
            (BUFFER_SIZE_FACTOR * minBufferSize).toDouble(),
            byteBuffer!!.capacity().toDouble()
        )
            .toInt()

        audioRecord = createAudioRecordOnQOrHigher(
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSizeInBytes,
            mediaProjection
        )

        Log.d(TAG, "startRecording")
        assert(audioRecord != null)
        assert(audioThread == null)
        try {
            audioRecord!!.startRecording()
        } catch (e: java.lang.IllegalStateException) {
            Log.e(TAG, "AudioRecord.startRecording failed: " + e.message)
            return -1
        }
        if (audioRecord!!.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            return -1
        }

        audioThread = AudioRecordThread("AudioRecordJavaThread")
        audioThread!!.start()

        return 0
    }

    public fun stopRecording(): Boolean {
        Log.d(TAG, "stopRecording")
//        assert(audioThread != null)
        if (audioThread == null) {
            return false
        }
        if (future != null) {
            if (!future!!.isDone) {
                // Might be needed if the client calls startRecording(), stopRecording() back-to-back.
                future!!.cancel(true /* mayInterruptIfRunning */)
            }
            future = null
        }
        audioThread!!.stopThread()
        if (!ThreadUtils.joinUninterruptibly(
                audioThread,
                AUDIO_RECORD_THREAD_JOIN_TIMEOUT_MS
            )
        ) {
            Log.e(
                TAG,
                "Join of AudioRecordJavaThread timed out"
            )
//            WebRtcAudioUtils.logAudioState(
//                org.webrtc.audio.WebRtcAudioRecord.TAG,
//                context,
//                audioManager
//            )
        }
        audioThread = null
//        effects.release()
        releaseAudioResources()
        return true
    }

    // Releases the native AudioRecord resources.
    private fun releaseAudioResources() {
        Log.d(TAG, "releaseAudioResources")
        if (audioRecord != null) {
            audioRecord!!.stop()
            audioRecord!!.release()
            audioRecord = null
        }
//        audioSourceMatchesRecordingSessionRef.set(null)
    }


    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private fun createAudioRecordOnQOrHigher(
        sampleRate: Int,
        channelConfig: Int,
        audioFormat: Int,
        bufferSizeInBytes: Int,
        mediaProjection: MediaProjection
    ): AudioRecord {
        Log.d(TAG, "createAudioRecordOnQOrHigher")
        val audioConfig =
            AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()

        return AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(bufferSizeInBytes)
            .setAudioPlaybackCaptureConfig(audioConfig)
            .build()
    }

    public fun getBytesPerSample(audioFormat: Int): Int {
        return when (audioFormat) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_IEC61937, AudioFormat.ENCODING_DEFAULT -> 2
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            AudioFormat.ENCODING_INVALID -> throw IllegalArgumentException("Bad audio format $audioFormat")
            else -> throw IllegalArgumentException("Bad audio format $audioFormat")
        }
    }

    // Use an ExecutorService to schedule a task after a given delay where the task consists of
    // checking (by logging) the current status of active recording sessions.
    private fun scheduleLogRecordingConfigurationsTask(audioRecord: AudioRecord) {
        Log.d(TAG, "scheduleLogRecordingConfigurationsTask")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return
        }

        val callable =
            Callable<String> {
                if (this.audioRecord === audioRecord) {
//                    logRecordingConfigurations(audioRecord, true /* verifyAudioConfig */)
                } else {
                    Log.d(TAG, "audio record has changed")
                }
                "Scheduled task is done"
            }

        executor = newDefaultScheduler()

        if (future != null && !future!!.isDone()) {
            future!!.cancel(true /* mayInterruptIfRunning */)
        }
        // Schedule call to logRecordingConfigurations() from executor thread after fixed delay.
        future = executor!!.schedule<String>(
            callable,
            CHECK_REC_STATUS_DELAY_MS.toLong(),
            TimeUnit.MILLISECONDS
        )
    };

    private val nextSchedulerId = AtomicInteger(0)


    public fun newDefaultScheduler(): ScheduledExecutorService {
        val nextThreadId = AtomicInteger(0)
        return Executors.newScheduledThreadPool(
            0
        ) { r ->

            /**
             * Constructs a new `Thread`
             */
            val thread = Executors.defaultThreadFactory().newThread(r)
            thread.name = String.format(
                "WebRtcAudioRecordScheduler-%s-%s",
                nextSchedulerId.getAndIncrement(),
                nextThreadId.getAndIncrement()
            )
            thread
        }
    }

    public inner class AudioRecordThread(name: String?) : Thread(name) {
        @Volatile
        private var keepAlive = true

        override fun run() {
            if (audioRecord == null || byteBuffer == null) {
                return
            }

            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            assert(audioRecord!!.recordingState == AudioRecord.RECORDSTATE_RECORDING)

            // Audio recording has started and the client is informed about it.
//            doAudioRecordStateCallback(org.webrtc.audio.WebRtcAudioRecord.AUDIO_RECORD_START)

            val lastTime = System.nanoTime()
            var audioTimestamp: AudioTimestamp? = null
            if (Build.VERSION.SDK_INT >= 24) {
                audioTimestamp = AudioTimestamp()
            }
            while (keepAlive) {
                val bytesRead: Int = audioRecord!!.read(byteBuffer, byteBuffer!!.capacity())
                if (bytesRead == byteBuffer!!.capacity()) {
//                    if (org.webrtc.audio.WebRtcAudioRecord.microphoneMute) {
//                        byteBuffer?.clear()
//                        byteBuffer?.put(emptyBytes)
//                    }
                    // It's possible we've been shut down during the read, and stopRecording() tried and
                    // failed to join this thread. To be a bit safer, try to avoid calling any native methods
                    // in case they've been unregistered after stopRecording() returned.
                    if (keepAlive) {
                        var captureTimeNs: Long = 0
                        if (Build.VERSION.SDK_INT >= 24) {
                            if (audioRecord!!.getTimestamp(
                                    audioTimestamp,
                                    AudioTimestamp.TIMEBASE_MONOTONIC
                                )
                                == AudioRecord.SUCCESS
                            ) {
                                captureTimeNs = audioTimestamp!!.nanoTime
                            }
                        }
//                        nativeDataIsRecorded(nativeAudioRecord, bytesRead, captureTimeNs)
                    }
                    if (info.dvkr.screenstream.mjpeg.BuildConfig.DEBUG) {
                        Log.d(TAG, "AudioRecord.read: $bytesRead")
                    }

                    if (audioSamplesReadyCallback != null) {
                        // Copy the entire byte buffer array. The start of the byteBuffer is not necessarily
                        // at index 0.
                        val data: ByteArray = Arrays.copyOfRange(
                            byteBuffer!!.array(), byteBuffer!!.arrayOffset(),
                            byteBuffer!!.capacity() + byteBuffer!!.arrayOffset()
                        )
                        audioSamplesReadyCallback!!.onAudioRecordSamplesReady(
                            AudioSamples(
                                audioRecord!!.audioFormat,
                                audioRecord!!.channelCount, audioRecord!!.sampleRate, data
                            )
                        )
                    }
                } else {
                    val errorMessage = "AudioRecord.read failed: $bytesRead"
                    Log.e(TAG, errorMessage)
                    if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                        keepAlive = false
//                        reportWebRtcAudioRecordError(errorMessage)
                    }
                }
            }

            try {
                if (audioRecord != null) {
                    audioRecord!!.stop()
//                    doAudioRecordStateCallback(org.webrtc.audio.WebRtcAudioRecord.AUDIO_RECORD_STOP)
                }
            } catch (e: IllegalStateException) {
                Log.e(
                    TAG,
                    "AudioRecord.stop failed: " + e.message
                )
            }
        }

        // Stops the inner thread loop and also calls AudioRecord.stop().
        // Does not block the calling thread.
        public fun stopThread() {
            Log.d(TAG, "stopThread")
            keepAlive = false
        }
    }
}