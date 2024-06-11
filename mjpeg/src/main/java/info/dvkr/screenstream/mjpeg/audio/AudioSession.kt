package info.dvkr.screenstream.mjpeg.audio

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Handler
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.nio.ByteBuffer
import java.util.ArrayDeque

public class AudioSession {

    private var TAG = "AudioSession"

    public interface AudioSessionCallback {
        public fun onAacData(data: ByteArray)
    }

    //    AUDIO相关
    private var systemAudioRecorder: SystemAudioRecorder? = null
    private var screenCapturerManager: ScreenCapturerManager? = null
    private var REQUEST_AUDIO_RECORD_CODE: Int = 1000


    private var minBufferSize = 0

    //不能修改，会影响到csd_0的计算
    //https://blog.csdn.net/lavender1626/article/details/80431902?spm=1001.2014.3001.5502
    private val sampleRate = 16000
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val aacProfile = MediaCodecInfo.CodecProfileLevel.AACObjectLC
    private val channels = 1


    private val bitRate = 64000


    private var encoder: MediaCodec? = null
    private var mime = MediaFormat.MIMETYPE_AUDIO_AAC

    public var recordQueue: ArrayDeque<AudioSamples> = ArrayDeque()

    //是否是异步编码
    //目前只能用同步，异步测试不通过，原因未知
    private var asyncEncode = false
    public lateinit var codecInputBuffers: Array<ByteBuffer>
    public lateinit var codecOutputBuffers: Array<ByteBuffer>
    public var bufferInfo: MediaCodec.BufferInfo = MediaCodec.BufferInfo()

    private var isRecording = false

    public var listener: AudioSessionCallback? = null


    public fun start(mediaProjection: MediaProjection) {
        Log.d(TAG, "start: ")
        if (isRecording) {
            return
        }
        isRecording = true
        if (!startAudioRecord(mediaProjection)) {
            return
        }
        createEncoderMediaCodec()

    }

    public fun stop() {
        Log.d(TAG, "stop: ")
        if (!isRecording) {
            return
        }
        isRecording = false
        releaseEncoderMediaCodec()
        stopAudioRecord()
    }

    public fun prepareStream(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf<String>(Manifest.permission.RECORD_AUDIO),
            REQUEST_AUDIO_RECORD_CODE
        )

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            screenCapturerManager =
                ScreenCapturerManager(activity)
        }
    }

    private fun startAudioRecord(mediaProjection: MediaProjection): Boolean {

        screenCapturerManager?.startForeground()

        systemAudioRecorder = SystemAudioRecorder()
        systemAudioRecorder?.audioSamplesReadyCallback = samplesReadyCallback
        systemAudioRecorder?.startRecording(mediaProjection)

//            RemoteWebSocketServer.startWebsocketServer()

        return true

    }

    private fun stopAudioRecord() {
        screenCapturerManager?.endForeground()

        systemAudioRecorder?.stopRecording()
        systemAudioRecorder = null

//        RemoteWebSocketServer.stopWebsocketServer()
    }

    private var samplesReadyCallback: SystemAudioRecorder.SamplesReadyCallback = object : SystemAudioRecorder.SamplesReadyCallback {
        override fun onAudioRecordSamplesReady(samples: AudioSamples?) {
            if (!isRecording ||samples == null) {
                return
            }

//            testPlayAudio(samples)

            if (asyncEncode) {
//                Log.d(TAG, "onAudioRecordSamplesReady: recordQueue=${recordQueue.size}")
                synchronized(recordQueue) {
                    recordQueue.add(samples)
                }
            }
            else {
                synchronized(this) {
                    if (encoder == null) {
                        return
                    }
                    try {
                        val codecInputBufferIndex: Int = encoder!!.dequeueInputBuffer((10 * 1000).toLong())
                        if (codecInputBufferIndex < 0) {
                            return
                        }
                        val codecBuffer = codecInputBuffers[codecInputBufferIndex]
                        codecBuffer.clear()
                        codecBuffer.put(samples.data)
                        encoder!!.queueInputBuffer(
                            codecInputBufferIndex,
                            0,
                            samples.data.size,
                            0,
                            if (isRecording) 0 else MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )

                        handleCodecOutput(encoder!!, codecOutputBuffers, bufferInfo)
                    }
                    catch (e: Exception) {
                        Log.e(TAG, "onAudioRecordSamplesReady: ", e)
                    }
                }

            }

        }

        private var audioTrack: AudioTrack? = null
        private var handler: Handler = Handler()
        fun testPlayAudio(audioFrame: AudioSamples) {
            if (audioTrack == null) {
                val audioAttributes: AudioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH) // defines the type of content being played
                    .setUsage(AudioAttributes.USAGE_MEDIA) // defines the purpose of why audio is being played in the app
                    .build()

                val audioFormat: AudioFormat = AudioFormat.Builder()
                    .setEncoding(this@AudioSession.audioFormat) // we plan on reading byte arrays of data, so use the corresponding encoding
                    .setSampleRate(audioFrame.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()

                val bufferSizePlaying =
                    AudioTrack.getMinBufferSize(audioFrame.sampleRate, AudioFormat.CHANNEL_OUT_STEREO, audioFrame.audioFormat)

                audioTrack = AudioTrack(
                    audioAttributes,
                    audioFormat,
                    bufferSizePlaying,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )
                audioTrack?.play()
            }
            handler.postDelayed({

                val result = audioTrack?.write(audioFrame.data, 0, audioFrame.data.size)
                Log.d(TAG, "testPlayAudio: $result")
            }, 3000)

        }
    }

    private fun createEncoderMediaCodec() {
        synchronized(this) {
            minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, audioFormat
            ) * 4
            encoder = createEncoderMediaCodec(minBufferSize)
            encoder!!.start()

            if (!asyncEncode) {
                codecInputBuffers = encoder!!.inputBuffers
                codecOutputBuffers = encoder!!.outputBuffers
            }
        }

    }

    private fun releaseEncoderMediaCodec() {
        synchronized(this) {
            encoder?.stop()
            encoder?.release()
            encoder = null
        }

    }

    private fun onEncodedData(data: ByteArray) {
        if (data.isEmpty() || listener == null) {
            return
        }
        listener?.onAacData(data)
//        if (data.isEmpty() || RemoteWebSocketServer.getInstance() == null) {
//            return
//        }

//        Log.d(TAG, "onEncodedData: Data size=${data.size}")
//        RemoteWebSocketServer.getInstance().sendMessage(data)
    }

    @Throws(IOException::class)
    private fun createEncoderMediaCodec(bufferSize: Int): MediaCodec {
        val mediaCodec = MediaCodec.createEncoderByType(mime)
        val mediaFormat = MediaFormat()

        mediaFormat.setString(MediaFormat.KEY_MIME, mime)
        mediaFormat.setInteger(
            MediaFormat.KEY_SAMPLE_RATE,
            sampleRate
        )
        mediaFormat.setInteger(
            MediaFormat.KEY_CHANNEL_COUNT,
            channels
        )
        mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
        mediaFormat.setInteger(
            MediaFormat.KEY_BIT_RATE,
            bitRate
        )
        mediaFormat.setInteger(
            MediaFormat.KEY_AAC_PROFILE,
            aacProfile
        )

        try {
            if (asyncEncode) {
                mediaCodec.setCallback(object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(mediaCodec: MediaCodec, index: Int) {
                        //One InputBuffer is available to decode
                        synchronized(recordQueue) {
                            if (recordQueue.size == 0) {
                                return
                            }
                            Log.d(TAG, "onInputBufferAvailable: recordQueue.size=${recordQueue.size}")

                            while (true) {
                                if (recordQueue.size > 0) {
                                    val sample: AudioSamples = recordQueue.removeFirst()
                                    val buffer = mediaCodec.getInputBuffer(index)
                                    buffer.put(sample.data, 0, sample.data.size)
                                    mediaCodec.queueInputBuffer(index, 0, sample.data.size, 0, 0)
                                    break
                                }
                            }
                        }
                    }

                    override fun onOutputBufferAvailable(
                        mediaCodec: MediaCodec,
                        i: Int,
                        info: MediaCodec.BufferInfo
                    ) {
                        //DECODING PACKET ENDED
                        val outBuffer = mediaCodec.getOutputBuffer(i)

                        outBuffer.position(info.offset)
                        outBuffer.limit(info.offset + info.size)

                        val chunk = ByteArray(outBuffer.remaining())
                        outBuffer.get(chunk) // Read the buffer all at once
                        assert(outBuffer.remaining() == 0)
                        outBuffer.clear()
                        //                    audioTrack.write(chunk, info.offset, info.offset + info.size); // AudioTrack write data
                        //playing(chunk, info.offset, info.offset + info.size)

                        mediaCodec.releaseOutputBuffer(i, false)
                        onEncodedData(chunk)
                    }

                    override fun onError(mediaCodec: MediaCodec, e: MediaCodec.CodecException) {
                        Log.e(
                            TAG,
                            "MediaCodec error: " + e.message
                        )
                    }

                    override fun onOutputFormatChanged(
                        mediaCodec: MediaCodec,
                        mediaFormat: MediaFormat
                    ) {
                    }
                })
            }

            mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.w(TAG, e)
            mediaCodec.release()
            throw IOException(e)
        }

        return mediaCodec
    }

    @Throws(IOException::class)
    private fun handleCodecOutput(
        mediaCodec: MediaCodec,
        codecOutputBuffers: Array<ByteBuffer>,
        bufferInfo: MediaCodec.BufferInfo
    ) {
        var codecOutputBuffers = codecOutputBuffers
        var codecOutputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0)

        while (codecOutputBufferIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
            if (codecOutputBufferIndex >= 0) {
                val encoderOutputBuffer = codecOutputBuffers[codecOutputBufferIndex]

                encoderOutputBuffer.position(bufferInfo.offset)
                encoderOutputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                    val header: ByteArray = createAdtsHeader(bufferInfo.size - bufferInfo.offset)


                    //                    outputStream.write(header);
                    val data = ByteArray(encoderOutputBuffer.remaining())
                    encoderOutputBuffer[data]
                    //                    outputStream.write(data);

                    onEncodedData(data)
                }

                encoderOutputBuffer.clear()

                mediaCodec.releaseOutputBuffer(codecOutputBufferIndex, false)
            } else if (codecOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                codecOutputBuffers = mediaCodec.outputBuffers
            }

            codecOutputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0)
        }
    }

    private val SAMPLE_RATE_INDEX = 4

    private fun createAdtsHeader(length: Int): ByteArray {
        val frameLength = length + 7
        val adtsHeader = ByteArray(7)

        adtsHeader[0] = 0xFF.toByte() // Sync Word
        adtsHeader[1] = 0xF1.toByte() // MPEG-4, Layer (0), No CRC
        adtsHeader[2] = ((MediaCodecInfo.CodecProfileLevel.AACObjectLC - 1) shl 6).toByte()
        adtsHeader[2] =
            (adtsHeader[2].toInt() or (SAMPLE_RATE_INDEX.toByte()
                .toInt() shl 2)).toByte()
        adtsHeader[2] =
            (adtsHeader[2].toInt() or (channels.toByte()
                .toInt() shr 2)).toByte()
        adtsHeader[3] =
            (((channels and 3) shl 6) or ((frameLength shr 11) and 0x03)).toByte()
        adtsHeader[4] = ((frameLength shr 3) and 0xFF).toByte()
        adtsHeader[5] = (((frameLength and 0x07) shl 5) or 0x1f).toByte()
        adtsHeader[6] = 0xFC.toByte()

        return adtsHeader
    }
}