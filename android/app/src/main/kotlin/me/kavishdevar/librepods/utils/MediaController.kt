/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.utils

import android.content.SharedPreferences
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import kotlin.io.encoding.ExperimentalEncodingApi

// TODO: fragile play/pause, don't refactor unless needed. userPlayedMedia and iPaused etc. probably should not be here
object MediaController {
    private var initialVolume: Int? = null
    private lateinit var audioManager: AudioManager
    var iPausedTheMedia = false
    var userPlayedTheMedia = false
    private lateinit var sharedPreferences: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener

    var pausedWhileTakingOver = false
    var pausedForOtherDevice = false

    private var lastSelfActionAt: Long = 0L
    private const val SELF_ACTION_IGNORE_MS = 800L
    private const val PLAYBACK_DEBOUNCE_MS = 300L
    private var lastPlaybackCallbackAt: Long = 0L
    private var lastKnownIsMusicActive: Boolean? = null

    private const val PAUSED_FOR_OTHER_DEVICE_CLEAR_MS = 500L
    private val clearPausedForOtherDeviceRunnable = Runnable {
        pausedForOtherDevice = false
        Log.d("MediaController", "Cleared pausedForOtherDevice after timeout, resuming normal playback monitoring")
    }

    private var relativeVolume: Boolean = false
    private var conversationAwarenessVolume: Int = 2
    private var conversationAwarenessPauseMusic: Boolean = false

    var recentlyLostOwnership: Boolean = false

    private var lastPlayWithReplay: Boolean = false
    private var lastPlayTime: Long = 0L

    private var localMac: String? = null
    private var appContext: android.content.Context? = null

    private var musicPlayed: () -> Unit = {}
    private var musicStopped: () -> Unit = {}

    private val pendingSessionCheck = Runnable {
        if (!pausedWhileTakingOver && !recentlyLostOwnership && lastKnownIsMusicActive != true &&
            audioManager.isMusicActive && hasPlayingMediaSession()
        ) {
            Log.d("MediaController", "Trailing MediaSession check confirmed active playback; requesting takeOver")
            handler.removeCallbacks(pendingSustainedMediaCheck)
            lastKnownIsMusicActive = true
            musicPlayed()
        }
    }

    private val pendingSustainedMediaCheck = Runnable {
        val stillHasMedia = audioManager.activePlaybackConfigurations.any {
            it.audioAttributes?.usage == android.media.AudioAttributes.USAGE_MEDIA
        }
        if (!pausedWhileTakingOver && !recentlyLostOwnership && lastKnownIsMusicActive != true &&
            audioManager.isMusicActive && stillHasMedia
        ) {
            Log.d("MediaController", "Sustained USAGE_MEDIA check (1.4s) confirmed active playback (e.g. Instagram/browser); requesting takeOver")
            lastKnownIsMusicActive = true
            musicPlayed()
        }
    }

    private fun hasPlayingMediaSession(): Boolean {
        val ctx = appContext ?: return false
        return try {
            val msm = ctx.getSystemService(android.media.session.MediaSessionManager::class.java) ?: return false
            val listener = android.content.ComponentName(
                ctx,
                me.kavishdevar.librepods.services.LibrePodsNotificationListener::class.java
            )
            msm.getActiveSessions(listener).any {
                it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
            }
        } catch (_: SecurityException) {
            false
        } catch (e: Exception) {
            Log.w("MediaController", "couldn't query active media sessions", e)
            false
        }
    }

    fun onOwnershipLost() {
        handler.removeCallbacks(pendingSessionCheck)
        handler.removeCallbacks(pendingSustainedMediaCheck)
        recentlyLostOwnership = true
        lastKnownIsMusicActive = false
        handler.postDelayed({ recentlyLostOwnership = false }, 1500L)
        // Pause any actively playing MediaSession directly (more reliable than KEYCODE_MEDIA_PAUSE)
        val ctx = appContext
        var pausedViaSession = false
        if (ctx != null) {
            try {
                val msm = ctx.getSystemService(android.media.session.MediaSessionManager::class.java)
                val listener = android.content.ComponentName(
                    ctx,
                    me.kavishdevar.librepods.services.LibrePodsNotificationListener::class.java
                )
                msm?.getActiveSessions(listener)?.forEach { controller ->
                    if (controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) {
                        controller.transportControls.pause()
                        pausedViaSession = true
                    }
                }
            } catch (_: SecurityException) {
            } catch (e: Exception) {
                Log.w("MediaController", "couldn't pause via MediaSession", e)
            }
        }
        if (!pausedViaSession) {
            sendPause(force = true)
        }
        iPausedTheMedia = false
    }

    fun initialize(
        audioManager: AudioManager,
        sharedPreferences: SharedPreferences,
        localMac: String?,
        context: android.content.Context? = null,
        musicPlayed: () -> Unit,
        musicStopped: () -> Unit = {}
    ) {
        this.localMac = localMac
        if (context != null) this.appContext = context.applicationContext
        if (this::audioManager.isInitialized) {
            return
        }
        this.audioManager = audioManager
        this.sharedPreferences = sharedPreferences
        this.musicPlayed = musicPlayed
        this.musicStopped = musicStopped
        Log.d("MediaController", "Initializing MediaController")
        relativeVolume = sharedPreferences.getBoolean("relative_conversation_awareness_volume", false)
        conversationAwarenessVolume = sharedPreferences.getInt("conversation_awareness_volume", (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 0.4).toInt())
        conversationAwarenessPauseMusic = sharedPreferences.getBoolean("conversation_awareness_pause_music", false)

        preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "relative_conversation_awareness_volume" -> {
                    relativeVolume = sharedPreferences.getBoolean("relative_conversation_awareness_volume", false)
                }
                "conversation_awareness_volume" -> {
                    conversationAwarenessVolume = sharedPreferences.getInt("conversation_awareness_volume", (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.4).toInt())
                }
                "conversation_awareness_pause_music" -> {
                    conversationAwarenessPauseMusic = sharedPreferences.getBoolean("conversation_awareness_pause_music", false)
                }
            }
        }

        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        audioManager.registerAudioPlaybackCallback(cb, null)
    }

    val cb = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            super.onPlaybackConfigChanged(configs)
            val now = SystemClock.uptimeMillis()
            val isActive = audioManager.isMusicActive
            Log.d("MediaController", "Playback config changed, iPausedTheMedia: $iPausedTheMedia, isActive: $isActive, pausedForOtherDevice: $pausedForOtherDevice, lastKnownIsMusicActive: $lastKnownIsMusicActive")

            if (!isActive && lastPlayWithReplay && now - lastPlayTime < 2500L) {
                Log.d("MediaController", "Music paused shortly after play with replay; retrying play")
                lastPlayWithReplay = false
                sendPlay()
                lastKnownIsMusicActive = true
                return
            }

            val hasNewMusicOrMoviePreview = configs?.any { config ->
                val attrs = config.audioAttributes ?: return@any false
                val ct = attrs.contentType
                ct == android.media.AudioAttributes.CONTENT_TYPE_MUSIC ||
                    ct == android.media.AudioAttributes.CONTENT_TYPE_MOVIE ||
                    attrs.usage == android.media.AudioAttributes.USAGE_GAME ||
                    (attrs.usage == android.media.AudioAttributes.USAGE_MEDIA &&
                        ct == android.media.AudioAttributes.CONTENT_TYPE_UNKNOWN &&
                        hasPlayingMediaSession())
            } == true

            if (now - lastPlaybackCallbackAt < PLAYBACK_DEBOUNCE_MS && !(isActive && hasNewMusicOrMoviePreview && lastKnownIsMusicActive != true)) {
                Log.d("MediaController", "Ignoring playback callback due to debounce (${now - lastPlaybackCallbackAt}ms)")
                lastPlaybackCallbackAt = now
                return
            }
            lastPlaybackCallbackAt = now

            if (now - lastSelfActionAt < SELF_ACTION_IGNORE_MS) {
                Log.d("MediaController", "Ignoring playback callback because it's likely caused by our own action (${now - lastSelfActionAt}ms since last self-action)")
                lastKnownIsMusicActive = isActive
                return
            }

            Log.d("MediaController", "Configs received: ${configs?.size ?: 0} configurations")
            val currentActiveContentTypes = configs?.flatMap { config ->
                Log.d("MediaController", "Processing config: ${config}, audioAttributes: ${config.audioAttributes}")
                config.audioAttributes?.let { attrs ->
                    val contentType = attrs.contentType
                    Log.d("MediaController", "Config content type: $contentType")
                    listOf(contentType)
                } ?: run {
                    Log.d("MediaController", "Config has no audioAttributes")
                    emptyList()
                }
            }?.toSet() ?: emptySet()

            Log.d("MediaController", "Current active content types: $currentActiveContentTypes")

            val hasMusicOrMovie = currentActiveContentTypes.any { contentType ->
                contentType == android.media.AudioAttributes.CONTENT_TYPE_MUSIC ||
                contentType == android.media.AudioAttributes.CONTENT_TYPE_MOVIE
            }
            val hasGameUsage = configs?.any { config ->
                config.audioAttributes?.usage == android.media.AudioAttributes.USAGE_GAME
            } == true
            val hasUnknownMediaUsage = configs?.any { config ->
                val attrs = config.audioAttributes
                attrs?.usage == android.media.AudioAttributes.USAGE_MEDIA &&
                    attrs.contentType == android.media.AudioAttributes.CONTENT_TYPE_UNKNOWN
            } == true
            val hasPlayingSession = hasUnknownMediaUsage && hasPlayingMediaSession()
            val hasNewMusicOrMovie = hasMusicOrMovie || hasGameUsage || hasPlayingSession

            handler.removeCallbacks(pendingSessionCheck)
            handler.removeCallbacks(pendingSustainedMediaCheck)
            if (hasUnknownMediaUsage && !hasNewMusicOrMovie && isActive && lastKnownIsMusicActive != true) {
                handler.postDelayed(pendingSessionCheck, 200L)
                handler.postDelayed(pendingSustainedMediaCheck, 1400L)
            }

            Log.d("MediaController", "Has new music or movie: $hasNewMusicOrMovie (musicOrMovie=$hasMusicOrMovie, game=$hasGameUsage, playingSession=$hasPlayingSession)")

            if (pausedForOtherDevice) {
                handler.removeCallbacks(clearPausedForOtherDeviceRunnable)
                handler.postDelayed(clearPausedForOtherDeviceRunnable, PAUSED_FOR_OTHER_DEVICE_CLEAR_MS)

                if (isActive) {
                    Log.d("MediaController", "Detected play while pausedForOtherDevice; attempting to take over")
                    if (!recentlyLostOwnership && hasNewMusicOrMovie) {
                        pausedForOtherDevice = false
                        userPlayedTheMedia = true
                        if (!pausedWhileTakingOver) {
//                            TODO
//                            ServiceManager.getService()?.takeOver("music")
                            musicPlayed()
                        }
                    } else {
                        Log.d("MediaController", "Skipping take-over due to recent ownership loss or no new music/movie")
                    }
                } else {
                    Log.d("MediaController", "Still not active while pausedForOtherDevice; will clear state after timeout")
                }

                lastKnownIsMusicActive = isActive
                return
            }

            if (configs != null && !iPausedTheMedia) {
//                if (!localMac.isNullOrBlank()) {
//                    ServiceManager.getService()?.aacpManager?.sendMediaInformataion(
//                        localMac,
//                        isActive
//                    )
//                }
                Log.d("MediaController", "User changed media state themselves; will wait for ear detection pause before auto-play")
                handler.postDelayed({
                    userPlayedTheMedia = audioManager.isMusicActive
                    if (audioManager.isMusicActive) {
                        pausedForOtherDevice = false
                    }
                }, 7)
            }

            Log.d("MediaController", "pausedWhileTakingOver: $pausedWhileTakingOver")
            if (!pausedWhileTakingOver && isActive && hasNewMusicOrMovie) {
                if (lastKnownIsMusicActive != true) {
                    if (!recentlyLostOwnership) {
                        Log.d("MediaController", "Music/movie is active and not pausedWhileTakingOver; requesting takeOver")
//                        TODO
//                        ServiceManager.getService()?.takeOver("music")
                        musicPlayed()
                    } else {
                        Log.d("MediaController", "Skipping take-over due to recent ownership loss")
                    }
                }
            }

            val nowActiveMedia = hasNewMusicOrMovie && isActive
            if (lastKnownIsMusicActive == true && !nowActiveMedia) {
                musicStopped()
            }
            lastKnownIsMusicActive = nowActiveMedia
        }
    }

    @Synchronized
    fun getMusicActive(): Boolean {
        return audioManager.isMusicActive
    }

    @Synchronized
    fun sendPlayPause() {
        if (audioManager.isMusicActive) {
            Log.d("MediaController", "Sending pause because music is active")
            sendPause()
        } else {
            Log.d("MediaController", "Sending play because music is not active")
            sendPlay()
        }
    }

    @Synchronized
    fun sendPreviousTrack() {
        Log.d("MediaController", "Sending previous track")
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS
            )
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS
            )
        )
        lastSelfActionAt = SystemClock.uptimeMillis()
    }

    @Synchronized
    fun sendNextTrack() {
        Log.d("MediaController", "Sending next track")
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_NEXT
            )
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_NEXT
            )
        )
        lastSelfActionAt = SystemClock.uptimeMillis()
    }

    @Synchronized
    fun sendPause(force: Boolean = false) {
        Log.d("MediaController", "Sending pause with iPausedTheMedia: $iPausedTheMedia, userPlayedTheMedia: $userPlayedTheMedia, isMusicActive: ${audioManager.isMusicActive}, force: $force")
        if ((audioManager.isMusicActive) && (!userPlayedTheMedia || force)) {
            iPausedTheMedia = if (force) audioManager.isMusicActive else true
            userPlayedTheMedia = false
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_MEDIA_PAUSE
                )
            )
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_MEDIA_PAUSE
                )
            )
            lastSelfActionAt = SystemClock.uptimeMillis()
        }
    }

    @Synchronized
    fun sendPlay(replayWhenPaused: Boolean = false, force: Boolean = false) {
        Log.d("MediaController", "Sending play with iPausedTheMedia: $iPausedTheMedia, replayWhenPaused: $replayWhenPaused, force: $force")
        if (replayWhenPaused) {
            lastPlayWithReplay = true
            lastPlayTime = SystemClock.uptimeMillis()
        }
        if (iPausedTheMedia || force) { // very creative, ik. thanks.
            Log.d("MediaController", "Sending play and setting userPlayedTheMedia to false")
            userPlayedTheMedia = false
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_MEDIA_PLAY
                )
            )
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_MEDIA_PLAY
                )
            )
            lastSelfActionAt = SystemClock.uptimeMillis()
        }
        if (!audioManager.isMusicActive) {
            Log.d("MediaController", "Setting iPausedTheMedia to false")
            iPausedTheMedia = false
        }
        if (pausedWhileTakingOver) {
            Log.d("MediaController", "Setting pausedWhileTakingOver to false")
            pausedWhileTakingOver = false
        }
    }

//    @Synchronized
//    fun startSpeaking() {
//        Log.d("MediaController", "Starting speaking max vol: ${audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}, current vol: ${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)}, conversationAwarenessVolume: $conversationAwarenessVolume, relativeVolume: $relativeVolume")
//
//        if (initialVolume == null) {
//            initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
//            Log.d("MediaController", "Initial Volume: $initialVolume")
//            val targetVolume = if (relativeVolume) {
//                (initialVolume!! * conversationAwarenessVolume / 100)
//            } else if (initialVolume!! > (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * conversationAwarenessVolume / 100)) {
//                (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * conversationAwarenessVolume / 100)
//            } else {
//                initialVolume!!
//            }
//            smoothVolumeTransition(initialVolume!!, targetVolume)
//            if (conversationAwarenessPauseMusic) {
//                sendPause(force = true)
//            }
//        }
//        Log.d("MediaController", "Initial Volume: $initialVolume")
//    }

//    @Synchronized
//    fun stopSpeaking() {
//        Log.d("MediaController", "Stopping speaking, initialVolume: $initialVolume")
//        if (initialVolume != null) {
//            smoothVolumeTransition(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC), initialVolume!!)
//            if (conversationAwarenessPauseMusic) {
//                sendPlay()
//            }
//            initialVolume = null
//        }
//    }

    @Synchronized
    fun setVolume(volumePercent: Int) {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetVolume = maxVolume * volumePercent / 100

        smoothVolumeTransition(
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
            targetVolume,
        )
    }

    /**
     * Conversation awareness levels are a fixed share of the maximum volume, so on their own they
     * can sit *above* what the user was listening at. Never let ducking raise the volume.
     */
    @Synchronized
    fun duckVolume(volumePercent: Int) {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val ceiling = initialVolume ?: current
        smoothVolumeTransition(current, minOf(maxVolume * volumePercent / 100, ceiling))
    }

    @Synchronized
    fun startSpeaking() {
        if (initialVolume == null) {
            initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }

        Log.d(
            "MediaController",
            "Starting speaking, initial volume: $initialVolume",
        )
    }


    @Synchronized
    fun stopSpeaking() {
        val original = initialVolume ?: return

        smoothVolumeTransition(
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
            original,
        )

        initialVolume = null
    }

    // Bumped by every transition; a stale one stops instead of finishing. Without this, a ramp still
    // running towards the ducked level could finish after the restore and leave the volume there.
    @Volatile
    private var volumeTransitionGeneration = 0

    private fun smoothVolumeTransition(fromVolume: Int, toVolume: Int) {
        Log.d("MediaController", "Smooth volume transition from $fromVolume to $toVolume")
        val step = if (fromVolume < toVolume) 1 else -1
        val delay = 50L
        var currentVolume = fromVolume
        val generation = ++volumeTransitionGeneration

        handler.post(object : Runnable {
            override fun run() {
                if (generation != volumeTransitionGeneration) return
                if (currentVolume != toVolume) {
                    currentVolume += step
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
                    handler.postDelayed(this, delay)
                }
            }
        })
    }
}
