package com.spike.audiocapture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.ActivityCallback

/**
 * SPIKE ONLY. Exposes two JS-callable methods:
 *   AudioCapture.requestAndStart()  -> triggers system permission dialog, then starts service
 *   AudioCapture.stop()             -> stops the foreground service
 *
 * From web/JS (once wired into the Capacitor bridge):
 *   import { registerPlugin } from '@capacitor/core';
 *   const AudioCapture = registerPlugin('AudioCapture');
 *   await AudioCapture.requestAndStart();
 *   // ... play audio in another app ...
 *   await AudioCapture.stop();
 */
@CapacitorPlugin(name = "AudioCapture")
class AudioCapturePlugin : Plugin() {

    @PluginMethod
    fun requestAndStart(call: PluginCall) {
        saveCall(call)
        val mpm = activity.getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(call, mpm.createScreenCaptureIntent(), "handleProjectionResult")
    }

    @ActivityCallback
    private fun handleProjectionResult(call: PluginCall?, result: androidx.activity.result.ActivityResult) {
        val savedCall = call ?: return
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            savedCall.reject("User denied screen/audio capture permission")
            return
        }
        val serviceIntent = Intent(context, AudioCaptureService::class.java).apply {
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.data)
        }
        context.startForegroundService(serviceIntent)
        savedCall.resolve()
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        context.stopService(Intent(context, AudioCaptureService::class.java))
        call.resolve()
    }

    @PluginMethod
    fun getRecording(call: PluginCall) {
        val wavFile = java.io.File(context.getExternalFilesDir(null), "capture.wav")
        if (!wavFile.exists()) {
            call.reject("No recording found yet — capture at least once first.")
            return
        }
        val bytes = wavFile.readBytes()
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val ret = com.getcapacitor.JSObject()
        ret.put("base64", base64)
        call.resolve(ret)
    }
}
