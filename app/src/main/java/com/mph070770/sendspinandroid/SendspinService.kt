package com.mph070770.sendspinandroid

import android.app.*
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.content.Context
import android.os.Binder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SendspinService : Service() {
    private val tag = "SendspinService"
    private val binder = LocalBinder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var client: SendspinPcmClient? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val _uiState = MutableStateFlow(PlayerViewModel.UiState())
    val uiState: StateFlow<PlayerViewModel.UiState> = _uiState

    // Detect low-memory devices to disable expensive features (initialized in onCreate)
    private var isLowMemoryDevice = false
    private var isTV = false
    
    private fun checkIsLowMemoryDevice(): Boolean {
        return try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memInfo)
            val lowMemory = memInfo?.totalMem ?: 0L < 2_000_000_000L  // Less than 2GB total RAM
            if (lowMemory) {
                Log.i(tag, "Low-memory device detected: disabling artwork and action buttons")
            }
            lowMemory
        } catch (e: Exception) {
            Log.w(tag, "Failed to check device memory", e)
            false
        }
    }

    private fun checkIsTV(): Boolean {
        return try {
            val uiMode = resources.configuration.uiMode
            val isTV = (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
            if (isTV) {
                Log.i(tag, "TV device detected: using simplified UI and auto-discovery")
            }
            isTV
        } catch (e: Exception) {
            Log.w(tag, "Failed to check device type", e)
            false
        }
    }

    // Track notification state to avoid redundant updates
    private var lastNotificationState: NotificationState? = null

    // Network connectivity receiver for auto-reconnect
    private var connectivityReceiver: BroadcastReceiver? = null
    private var lastNetworkState: Boolean = false

    private data class NotificationState(
        val trackTitle: String?,
        val trackArtist: String?,
        val playbackState: String?,
        val hasController: Boolean,
        val supportedCommands: Set<String>,
        val artworkBitmap: Any? // Using Any to avoid bitmap comparison issues
    )

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "sendspin_playback"

        fun startService(context: Context, wsUrl: String, clientId: String, clientName: String) {
            val intent = Intent(context, SendspinService::class.java).apply {
                putExtra("wsUrl", wsUrl)
                putExtra("clientId", clientId)
                putExtra("clientName", clientName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, SendspinService::class.java))
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): SendspinService = this@SendspinService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(tag, "Service created")
        
        // Initialize low-memory detection now that context is ready
        isTV = checkIsTV()
        isLowMemoryDevice = checkIsLowMemoryDevice()
        
        createNotificationChannel()

        // Acquire wake lock to keep CPU running during playback
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SendspinService::WakeLock"
        ).apply {
            setReferenceCounted(false)
        }

        // Register network connectivity receiver
        registerNetworkReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(tag, "Service started")
        // Start foreground immediately
        startForeground(NOTIFICATION_ID, createNotification())

        intent?.let {
            val wsUrl = it.getStringExtra("wsUrl") ?: return@let
            val clientId = it.getStringExtra("clientId") ?: return@let
            val clientName = it.getStringExtra("clientName") ?: return@let

            // Disconnect existing connection before creating new one
            if (client != null) {
                Log.i(tag, "Disconnecting existing client before reconnecting")
                disconnect()
            }

            connect(wsUrl, clientId, clientName)
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    override fun onDestroy() {
        Log.i(tag, "Service destroyed")
        disconnect()

        // Unregister network connectivity receiver
        unregisterNetworkReceiver()

        // Release wake lock
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null

        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sendspin Playback",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Sendspin music playback - keeps service running"
                setShowBadge(false)
                enableVibration(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val state = _uiState.value

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Always show track info if available, fallback to status
        val title = if (state.trackTitle.isNullOrBlank()) "Sendspin Player" else state.trackTitle
        val subtitle = if (state.trackArtist.isNullOrBlank()) {
            when {
                state.connected && state.trackTitle != null -> "Now Playing"
                state.connected -> "Connected"
                else -> "Not connected"
            }
        } else {
            state.trackArtist
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(false)

        // Add album art only on non-low-memory devices
        if (!isLowMemoryDevice) {
            state.artworkBitmap?.let { bitmap ->
                builder.setLargeIcon(bitmap)
            }
        }

        // Track which action indices we add
        val actionIndices = mutableListOf<Int>()

        // Add media controls only on non-low-memory devices
        if (!isLowMemoryDevice && state.hasController) {
            // Previous
            if (state.supportedCommands.contains("previous")) {
                val prevIntent = createMediaActionIntent("previous")
                builder.addAction(
                    android.R.drawable.ic_media_previous,
                    "Previous",
                    prevIntent
                )
                actionIndices.add(actionIndices.size)
            }

            // Play/Pause
            if (state.playbackState == "playing" && state.supportedCommands.contains("pause")) {
                val pauseIntent = createMediaActionIntent("pause")
                builder.addAction(
                    android.R.drawable.ic_media_pause,
                    "Pause",
                    pauseIntent
                )
                actionIndices.add(actionIndices.size)
            } else if (state.supportedCommands.contains("play")) {
                val playIntent = createMediaActionIntent("play")
                builder.addAction(
                    android.R.drawable.ic_media_play,
                    "Play",
                    playIntent
                )
                actionIndices.add(actionIndices.size)
            }

            // Next
            if (state.supportedCommands.contains("next")) {
                val nextIntent = createMediaActionIntent("next")
                builder.addAction(
                    android.R.drawable.ic_media_next,
                    "Next",
                    nextIntent
                )
                actionIndices.add(actionIndices.size)
            }
        }

        // Use media style with only the actions that were actually added
        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
        if (actionIndices.isNotEmpty()) {
            mediaStyle.setShowActionsInCompactView(*actionIndices.toIntArray())
        }
        builder.setStyle(mediaStyle)

        return builder.build()
    }

    private fun createMediaActionIntent(action: String): PendingIntent {
        val intent = Intent(this, SendspinService::class.java).apply {
            putExtra("media_action", action)
        }
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateNotification() {
        val state = _uiState.value
        val currentState = NotificationState(
            trackTitle = state.trackTitle,
            trackArtist = state.trackArtist,
            playbackState = state.playbackState,
            hasController = state.hasController,
            supportedCommands = state.supportedCommands,
            artworkBitmap = state.artworkBitmap
        )

        // Only update notification if something relevant changed
        if (lastNotificationState != currentState) {
            lastNotificationState = currentState
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, createNotification())
        }
    }

    fun connect(wsUrl: String, clientId: String, clientName: String) {
        // Don't create a new connection if we're already connected to the same server
        if (client != null &&
            _uiState.value.wsUrl == wsUrl &&
            _uiState.value.clientId == clientId &&
            _uiState.value.connected) {
            Log.i(tag, "Already connected to this server, ignoring duplicate connect request")
            return
        }

        disconnect()

        // Acquire wake lock when connecting
        wakeLock?.acquire()

        _uiState.value = _uiState.value.copy(
            wsUrl = wsUrl,
            clientId = clientId,
            clientName = clientName,
            status = "connecting...",
            connected = true
        )

        client = SendspinPcmClient(
            wsUrl = wsUrl,
            clientId = clientId,
            clientName = clientName,
            context = this,
            onUiUpdate = { patch ->
                _uiState.value = patch(_uiState.value)
                updateNotification()
            }
        ).also {
            it.setPlayoutOffsetMs(_uiState.value.playoutOffsetMs)
        }

        scope.launch {
            client?.connect()
        }
    }

    fun disconnect() {
        client?.close("user_disconnect")
        client = null

        // Release wake lock when disconnecting
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }

        _uiState.value = _uiState.value.copy(connected = false, status = "disconnected")
        updateNotification()
    }

    fun setPlayoutOffsetMs(ms: Long) {
        val clamped = ms.coerceIn(-1000L, 1000L)
        _uiState.value = _uiState.value.copy(playoutOffsetMs = clamped)
        client?.setPlayoutOffsetMs(clamped)
    }

    // Controller commands
    fun sendPlay() = client?.sendControllerCommand("play")
    fun sendPause() = client?.sendControllerCommand("pause")
    fun sendStop() = client?.sendControllerCommand("stop")
    fun sendNext() = client?.sendControllerCommand("next")
    fun sendPrevious() = client?.sendControllerCommand("previous")

    fun setGroupVolume(volume: Int) {
        val clamped = volume.coerceIn(0, 100)
        client?.sendControllerCommand("volume", volume = clamped)
    }

    fun setGroupMute(muted: Boolean) {
        client?.sendControllerCommand("mute", mute = muted)
    }

    // Player (local device) volume controls
    fun setPlayerVolume(volume: Int) {
        client?.setPlayerVolume(volume)
    }

    fun setPlayerMute(muted: Boolean) {
        client?.setPlayerMute(muted)
    }

    fun clearPlayerVolumeFlag() {
        _uiState.value = _uiState.value.copy(playerVolumeFromServer = false)
    }

    fun clearPlayerMutedFlag() {
        _uiState.value = _uiState.value.copy(playerMutedFromServer = false)
    }

    // Network connectivity monitoring
    private fun registerNetworkReceiver() {
        connectivityReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val isCurrentlyConnected = isNetworkAvailable()
                
                // Only act on state changes
                if (isCurrentlyConnected != lastNetworkState) {
                    lastNetworkState = isCurrentlyConnected
                    
                    if (isCurrentlyConnected) {
                        Log.i(tag, "Network restored, attempting auto-reconnect")
                        // Get current connection parameters
                        val currentState = _uiState.value
                        if (!currentState.wsUrl.isBlank() && 
                            !currentState.clientId.isBlank() && 
                            !currentState.clientName.isBlank()) {
                            // Reconnect with existing parameters
                            scope.launch {
                                // Give network a moment to stabilize
                                delay(1000)
                                connect(currentState.wsUrl, currentState.clientId, currentState.clientName)
                            }
                        }
                    } else {
                        Log.i(tag, "Network lost")
                        updateUiState { it.copy(status = "network_lost", connected = false) }
                    }
                }
            }
        }

        try {
            val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                registerReceiver(connectivityReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(connectivityReceiver, filter)
            }
            
            // Initialize network state
            lastNetworkState = isNetworkAvailable()
            Log.i(tag, "Network receiver registered. Current state: $lastNetworkState")
        } catch (e: Exception) {
            Log.w(tag, "Failed to register network receiver", e)
        }
    }

    private fun unregisterNetworkReceiver() {
        connectivityReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.i(tag, "Network receiver unregistered")
            } catch (e: Exception) {
                Log.w(tag, "Failed to unregister network receiver", e)
            }
        }
        connectivityReceiver = null
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
                
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                networkInfo?.isConnectedOrConnecting == true
            }
        } catch (e: Exception) {
            Log.w(tag, "Error checking network availability", e)
            false
        }
    }

    private fun updateUiState(block: (PlayerViewModel.UiState) -> PlayerViewModel.UiState) {
        _uiState.value = block(_uiState.value)
    }
}