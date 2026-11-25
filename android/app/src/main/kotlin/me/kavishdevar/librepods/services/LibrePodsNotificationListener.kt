package me.kavishdevar.librepods.services

import android.service.notification.NotificationListenerService

/**
 * Minimal listener solely to allow MediaSessionManager.getActiveSessions()
 * so MediaController can distinguish real playback (e.g. Spotify playing) from
 * silent startup AudioTracks (e.g. Spotify cold-launch).
 */
class LibrePodsNotificationListener : NotificationListenerService()
