package com.ordertaking.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool

/**
 * The app's own sound effects. They play on the media volume (the one the tablet's volume
 * buttons change while the app is open), so they don't depend on notification settings.
 */
class Sounds(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val newOrderId = pool.load(context, R.raw.new_order, 1)
    private val orderReadyId = pool.load(context, R.raw.order_ready, 1)

    /** Kitchen: a new order just arrived. */
    fun newOrder() = play(newOrderId)

    /** Cashier: the kitchen marked an order as done. */
    fun orderReady() = play(orderReadyId)

    /** True when the media volume is all the way down, so nobody would hear new orders. */
    fun isMuted(): Boolean = audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0

    private fun play(id: Int) {
        pool.play(id, 1f, 1f, 1, 0, 1f)
    }
}
