package net.vrkknn.andromuks.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import net.vrkknn.andromuks.R

/**
 * Lightweight wrapper around [SoundPool] for playing the short pop-alert sound
 * whenever a new message bubble animates onto the screen.
 */
class MessageSoundPlayer(context: Context) {
    private val appContext = context.applicationContext

    private val soundPool: SoundPool
    private var soundId: Int = 0
    private var isLoaded = false

    init {
        val audioAttributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

        soundPool =
            SoundPool.Builder()
                .setAudioAttributes(audioAttributes)
                .setMaxStreams(2)
                .build()

        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0 && sampleId == soundId) {
                isLoaded = true
            }
        }

        soundId = soundPool.load(appContext, R.raw.popalert, 1)
    }

    fun play() {
        if (isLoaded) {
            soundPool.play(soundId, 1f, 1f, 0, 0, 1f)
        }
    }

    fun release() {
        soundPool.release()
    }
}

