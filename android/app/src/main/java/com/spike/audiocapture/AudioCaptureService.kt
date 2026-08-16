package com.spike.audiocapture

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * SPIKE ONLY — proves out AudioPlaybackCaptureConfiguration against real apps.
 * Not production code: no error recovery, no chunked streaming to JS bridge yet.
 *
 * Flow:
 *  1. Activity gets a MediaProjection result Intent from the system permission dialog.
 *  2. That Intent is handed to this foreground service (required on API 29+ for
 *     any ongoing use of MediaProjection).
 *  3. Service builds an AudioRecord using AudioPlaybackCaptureConfiguration,
 *     which taps the OS audio mixer output rather than a specific app.
 *  4. Raw PCM is written to a WAV file in app-external files dir so you can
 *     pull it via `adb pull` and listen to confirm capture actually worked.
 *
 * TEST PROCEDURE:
 *  - Start this service, then play audio in the target app (YouTube, Spotify,
 *    Zoom, browser tab, etc.), then stop the service.
 *  - Pull the WAV: adb pull /sdcard/Android/data/com.spike.audiocapture/files/capture.wav
 *  - Real audio in the file = capture works for that app.
 *  - Silence/near-silence = that app is likely marking itself
 *    ALLOW_CAPTURE_BY_NONE (Risk #1) or using AudioAttributes not covered by
 *    our addMatchingUsage() calls below.
 */
class AudioCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "audio_capture_spike"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val TAG = "AudioCaptureSpike"

        private const val SAMPLE_RATE = 16000 // whisper.cpp wants 16kHz mono eventually
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var isCapturing = false
    private var pcmFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity_RESULT_CANCELED) ?: return START_NOT_STICKY
        val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.e(TAG, "Missing/invalid MediaProjection permission result")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, resultData)

        startCapture()
        return START_STICKY
    }

    private fun startCapture() {
        val projection = mediaProjection ?: return

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AUDIO_FORMAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .build()

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBufSize * 4)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        pcmFile = File(getExternalFilesDir(null), "capture_raw.pcm")
        val out = FileOutputStream(pcmFile)

        isCapturing = true
        audioRecord?.startRecording()

        captureThread = Thread {
            val buffer = ByteArray(minBufSize)
            var totalBytes = 0L
            while (isCapturing) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    out.write(buffer, 0, read)
                    totalBytes += read
                    // Cheap silence check every ~1s of audio so you can watch logcat
                    // live instead of waiting to pull the file.
                    if (totalBytes % (SAMPLE_RATE * 2) < buffer.size) {
                        val peak = buffer.take(read).maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                        Log.d(TAG, "captured=${totalBytes}B peakByte=$peak ${if (peak < 3) "(near-silent — check ALLOW_CAPTURE_BY_NONE)" else ""}")
                    }
                }
            }
            out.flush()
            out.close()
            writeWavHeader()
        }
        captureThread?.start()
        Log.i(TAG, "Capture started -> ${pcmFile?.absolutePath}")
    }

    /** Wraps the raw PCM in a WAV header after capture stops, so it's directly playable. */
    private fun writeWavHeader() {
        val pcm = pcmFile ?: return
        if (!pcm.exists()) return
        val wavFile = File(pcm.parentFile, "capture.wav")
        val pcmSize = pcm.length()
        val byteRate = SAMPLE_RATE * 2 // 16-bit mono
        RandomAccessFile(wavFile, "rw").use { raf ->
            raf.writeBytes("RIFF")
            raf.write(intToLE((36 + pcmSize).toInt()))
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.write(intToLE(16))
            raf.write(shortToLE(1)) // PCM
            raf.write(shortToLE(1)) // mono
            raf.write(intToLE(SAMPLE_RATE))
            raf.write(intToLE(byteRate))
            raf.write(shortToLE(2)) // block align
            raf.write(shortToLE(16)) // bits per sample
            raf.writeBytes("data")
            raf.write(intToLE(pcmSize.toInt()))
            raf.write(pcm.readBytes())
        }
        Log.i(TAG, "WAV written -> ${wavFile.absolutePath} (${pcmSize} bytes PCM)")
    }

    private fun intToLE(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(), ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte())
    private fun shortToLE(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Audio Capture Spike", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio capture spike running")
            .setContentText("Recording system audio for testing")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    override fun onDestroy() {
        isCapturing = false
        captureThread?.join(500)
        audioRecord?.stop()
        audioRecord?.release()
        mediaProjection?.stop()
        super.onDestroy()
    }
}

// Small alias so RESULT_CANCELED is readable without importing Activity just for this constant
private const val Activity_RESULT_CANCELED = 0
