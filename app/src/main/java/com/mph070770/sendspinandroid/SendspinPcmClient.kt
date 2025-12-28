package com.mph070770.sendspinandroid

import android.app.ActivityManager
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SendspinPcmClient(
    private val wsUrl: String,
    private val clientId: String,
    private val clientName: String,
    private val onUiUpdate: ((PlayerViewModel.UiState) -> PlayerViewModel.UiState) -> Unit,
    private val context: android.content.Context
) {
    private val tag = "SendspinPcmClient"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val okHttp = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)  // Increased from 10s to 30s to be more tolerant
        .build()

    private var ws: WebSocket? = null

    private val isConnected = AtomicBoolean(false)
    private var handshakeComplete: Boolean = false
    
    // Detect low-memory devices to disable expensive features
    private val isLowMemoryDevice = checkIsLowMemoryDevice()
    
    private fun checkIsLowMemoryDevice(): Boolean {
        return try {
            val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memInfo)
            val lowMemory = memInfo?.totalMem ?: 0L < 2_000_000_000L  // Less than 2GB total RAM
            if (lowMemory) {
                Log.i(tag, "Low-memory device detected: disabling metadata, artwork, and action buttons")
            }
            lowMemory
        } catch (e: Exception) {
            Log.w(tag, "Failed to check device memory", e)
            false
        }
    }

    private val clock = ClockSync()
    private val jitter = AudioJitterBuffer()
    private val output: PcmAudioOutput = PcmAudioOutput()

    private var timeLoopJob: Job? = null
    private var playoutJob: Job? = null
    private var statsJob: Job? = null

    private var codec: String = ""
    private var sampleRate: Int = 48000
    private var channels: Int = 2
    private var bitDepth: Int = 16

    private var opusDecoder: OpusDecoder? = null

    private var playAtServerUs: Long = Long.MIN_VALUE

    // Pipeline delay offset (µs). Compensates for Android audio pipeline latency + codec decode latency.
    @Volatile private var playoutOffsetUs: Long = -120_000L  // Default -120ms
    
    // Track codec decode latency to adjust playoutOffsetUs dynamically
    private var decodeLatencyUs: Long = 0L  // Running average of decode time
    private val decodeLatencySamples = mutableListOf<Long>()
    private val maxDecodeLatencySamples = 30  // Keep rolling average of last 30 frames

    // Track last sent error state to prevent spam
    private var lastErrorStateSent: Long = 0L
    private val errorStateThrottleMs = 1000L  // Only send error state once per second

    fun setPlayoutOffsetMs(ms: Long) {
        val clamped = ms.coerceIn(-1000L, 1000L)
        playoutOffsetUs = clamped * 1000L
        Log.i(tag, "playoutOffset=${clamped}ms")
    }

    suspend fun connect() {
        val req = Request.Builder().url(wsUrl).build()

        onUiUpdate { it.copy(status = "connecting...", connected = false) }

        ws = okHttp.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(tag, "WS open")
                isConnected.set(true)
                handshakeComplete = false
                onUiUpdate { it.copy(status = "ws_open", connected = true) }
                sendClientHello()
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handleText(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handleBinary(bytes.toByteArray())

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(tag, "WS closed code=$code reason=$reason")
                teardown("closed: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "WS failure: ${t.message}", t)
                teardown("failure: ${t.message}")
            }
        })
    }

    fun close(reason: String) {
        try { sendClientGoodbye(reason) } catch (_: Throwable) { }
        ws?.close(1000, reason)
        teardown("client_close:$reason")
    }

    private fun teardown(status: String) {
        isConnected.set(false)
        handshakeComplete = false

        timeLoopJob?.cancel(); timeLoopJob = null
        playoutJob?.cancel(); playoutJob = null
        statsJob?.cancel(); statsJob = null

        output.stop()
        jitter.clear()
        opusDecoder = null
        playAtServerUs = Long.MIN_VALUE

        onUiUpdate {
            it.copy(
                status = status,
                connected = false,
                activeRoles = "",
                streamDesc = "",
                playbackState = "",
                groupName = "",
                queuedChunks = 0,
                bufferAheadMs = 0,
                // Clear metadata
                trackTitle = null,
                trackArtist = null,
                albumTitle = null,
                albumArtist = null,
                trackYear = null,
                trackNumber = null,
                artworkUrl = null,
                artworkBitmap = null,
                trackProgress = null,
                trackDuration = null,
                playbackSpeed = null,
                repeatMode = null,
                shuffleEnabled = null
            )
        }
    }

    /**
     * Measure and track decode latency for compensation.
     * Updates decodeLatencyUs with rolling average of codec decode times.
     * This ensures playback timing accounts for actual codec processing delay.
     */
    private fun recordDecodeLatency(decodeTimeUs: Long) {
        decodeLatencySamples.add(decodeTimeUs)
        if (decodeLatencySamples.size > maxDecodeLatencySamples) {
            decodeLatencySamples.removeAt(0)
        }
        
        // Calculate moving average
        decodeLatencyUs = decodeLatencySamples.average().toLong()
    }

    private fun sendJson(type: String, payload: JSONObject) {
        val obj = JSONObject().put("type", type).put("payload", payload)
        val json = obj.toString()
        if (type!="client/time") Log.i(tag, ">>>> SEND: $type | ${json.take(200)}${if (json.length > 200) "..." else ""}")
        ws?.send(json)
    }

    private fun buildPlayerSupportObject(): JSONObject {
        val supportedFormats = JSONArray()
            // Prefer Opus for bandwidth efficiency
            .put(JSONObject().put("codec", "opus").put("channels", 2).put("sample_rate", 48000).put("bit_depth", 16))
            .put(JSONObject().put("codec", "opus").put("channels", 2).put("sample_rate", 44100).put("bit_depth", 16))
            // Fallback to PCM
            .put(JSONObject().put("codec", "pcm").put("channels", 2).put("sample_rate", 48000).put("bit_depth", 16))
            .put(JSONObject().put("codec", "pcm").put("channels", 2).put("sample_rate", 44100).put("bit_depth", 16))

        val supportedCommands = JSONArray().put("volume").put("mute")

        return JSONObject()
            .put("supported_formats", supportedFormats)
            .put("buffer_capacity", 2_000_000)
            .put("supported_commands", supportedCommands)
    }

    private fun buildArtworkSupportObject(): JSONObject {
        // Support one artwork channel (album art)
        val channel = JSONObject()
            .put("source", "album")
            .put("format", "jpeg")
            .put("media_width", 800)
            .put("media_height", 800)

        val channels = JSONArray().put(channel)

        return JSONObject().put("channels", channels)
    }

    private fun sendClientHello() {
        val hello = JSONObject()
            .put("client_id", clientId)
            .put("name", clientName)
            .put("version", 1)
            .put("device_info", JSONObject()
                .put("product_name", android.os.Build.MODEL)
                .put("manufacturer", android.os.Build.MANUFACTURER)
                .put("software_version", context.packageManager.getPackageInfo(context.packageName, 0).versionName))
            .put("supported_roles", JSONArray()
                .put("player@v1")
                .put("controller@v1")
                .put("metadata@v1")
                .apply {
                    // Only declare artwork support on non-low-memory devices
                    if (!isLowMemoryDevice) {
                        put("artwork@v1")
                    }
                })

        val playerSupport = buildPlayerSupportObject()
        hello.put("player@v1_support", playerSupport)
        hello.put("player_support", playerSupport)  // Legacy field for compatibility

        // Only declare artwork support on non-low-memory devices
        if (!isLowMemoryDevice) {
            val artworkSupport = buildArtworkSupportObject()
            hello.put("artwork@v1_support", artworkSupport)
            hello.put("artwork_support", artworkSupport)  // Legacy field for compatibility
        }

        sendJson("client/hello", hello)
        onUiUpdate { it.copy(status = "sent client/hello") }
    }

    fun sendControllerCommand(command: String, volume: Int? = null, mute: Boolean? = null) {
        val controller = JSONObject().put("command", command)
        volume?.let { controller.put("volume", it) }
        mute?.let { controller.put("mute", it) }
        sendJson("client/command", JSONObject().put("controller", controller))
    }

    fun setPlayerVolume(volume: Int) {
        val clamped = volume.coerceIn(0, 100)
        Log.i(tag, "setPlayerVolume: $clamped (local control)")
        sendClientStatePlayer(volume = clamped, muted = null)

        // Update local state immediately without triggering server updates
        onUiUpdate { it.copy(playerVolume = clamped, playerVolumeFromServer = false) }
    }

    fun setPlayerMute(muted: Boolean) {
        Log.i(tag, "setPlayerMute: $muted (local control)")
        sendClientStatePlayer(volume = null, muted = muted)

        // Update local state immediately without triggering server updates
        onUiUpdate { it.copy(playerMuted = muted, playerMutedFromServer = false) }
    }

    private fun sendClientStatePlayer(volume: Int? = null, muted: Boolean? = null) {
        val player = JSONObject().put("state", "synchronized")
        volume?.let { player.put("volume", it) }
        muted?.let { player.put("muted", it) }
        sendJson("client/state", JSONObject().put("player", player))
    }

    private fun sendClientGoodbye(reason: String) {
        sendJson("client/goodbye", JSONObject().put("reason", reason))
    }

    private fun sendClientStateSynchronized(volume: Int = 100, muted: Boolean = false) {
        val player = JSONObject().put("state", "synchronized").put("volume", volume).put("muted", muted)
        sendJson("client/state", JSONObject().put("player", player))
    }

    private fun sendClientStateError(volume: Int = 100, muted: Boolean = true) {
        // Throttle error state messages to prevent spam
        val now = System.currentTimeMillis()
        if (now - lastErrorStateSent < errorStateThrottleMs) {
            return
        }
        lastErrorStateSent = now

        val player = JSONObject().put("state", "error").put("volume", volume).put("muted", muted)
        sendJson("client/state", JSONObject().put("player", player))
    }

    private fun startTimeSyncLoop() {
        timeLoopJob?.cancel()
        timeLoopJob = scope.launch {
            while (isActive && isConnected.get()) {
                sendJson("client/time", JSONObject().put("client_transmitted", nowUs()))
                delay(250L)
            }
        }
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive && isConnected.get()) {
                val snapshot = jitter.snapshot(clock.estimatedOffsetUs())
                Log.i(
                    tag,
                    "stats: offset=${clock.estimatedOffsetUs()}us drift=${String.format("%.3f", clock.estimatedDriftPpm())}ppm " +
                            "rtt~=${clock.estimatedRttUs()}us queued=${snapshot.queuedChunks} ahead~=${snapshot.bufferAheadMs}ms " +
                            "codec=$codec decodeLatency=${decodeLatencyUs / 1000}µs playoutOffset=${playoutOffsetUs / 1000}ms"
                )
                delay(3000L)
            }
        }
    }

    private fun startPlayoutLoop() {
        playoutJob?.cancel()
        playoutJob = scope.launch {
            // Target buffer for initial start (lower when joining mid-stream)
            val targetBufferMs = 200L
            val minBufferMs = 200L  // Wait for 200ms buffer before starting playback

            // Normal "too-late" drop once we're running.
            val lateDropUs = 50_000L

            // Make offset changes audible by catching up (dropping) or slowing down (waiting).
            val dropLateUs = 80_000L
            val targetLateUs = 20_000L
            val maxEarlySleepMs = 50L

            // âœ… NEW: if output is stopped and the queue head is very late, we must drop until near-now,
            // otherwise bufferAheadMs stays negative and we never restart (queue grows forever).
            val restartKeepWithinUs = 20_000L      // bring head within 20ms late
            val restartMinAheadMs = -20L           // allow small negative ahead at start
            val restartMinQueued = 1              // if we have any data after dropping, we can start

            while (isActive && isConnected.get()) {
                val offUs = clock.estimatedOffsetUs()
                val snapshot = jitter.snapshot(offUs)

                onUiUpdate {
                    it.copy(
                        queuedChunks = snapshot.queuedChunks,
                        bufferAheadMs = snapshot.bufferAheadMs,
                        lateDrops = snapshot.lateDrops,
                        offsetUs = offUs,
                        driftPpm = clock.estimatedDriftPpm(),
                        rttUs = clock.estimatedRttUs()
                    )
                }

                if (!output.isStarted()) {
                    if (codec != "pcm" && codec != "opus") {
                        delay(50)
                        continue
                    }

                    // âœ… NEW: prevent deadlock when head is late (negative ahead) by dropping late chunks now.
                    if (snapshot.queuedChunks > 0 && snapshot.bufferAheadMs < restartMinAheadMs) {
                        val dropped = jitter.dropWhileLate(nowUs(), offUs, restartKeepWithinUs)
                        if (dropped > 0) {
                            Log.w(tag, "restart-catchup: dropped=$dropped head was late (ahead~${snapshot.bufferAheadMs}ms)")
                        }
                    }

                    val snap2 = jitter.snapshot(offUs)

                    // âœ… CHANGED: Start immediately if we have minimum buffer, or if target is reached
                    // This allows joining mid-stream to start quickly
                    val canStart =
                        (snap2.queuedChunks >= restartMinQueued) &&
                                (snap2.bufferAheadMs >= minBufferMs || snap2.bufferAheadMs >= restartMinAheadMs)

                    if (canStart) {
                        output.start(sampleRate, channels, bitDepth)

                        // Initialize Opus decoder if needed
                        if (codec == "opus") {
                            opusDecoder = try {
                                OpusDecoder(sampleRate, channels)
                            } catch (e: Exception) {
                                Log.e(tag, "Failed to create Opus decoder", e)
                                sendClientStateError()
                                delay(100)
                                continue
                            }
                        }

                        sendClientStateSynchronized()
                        Log.i(tag, "Audio output started sr=$sampleRate ch=$channels bd=$bitDepth codec=$codec (buffered=${snap2.bufferAheadMs}ms)")
                    } else {
                        delay(10)
                        continue
                    }
                }

                val chunk = jitter.pollPlayable(nowUs(), offUs, lateDropUs)
                if (chunk == null) {
                    if (jitter.isEmpty()) {
                        sendClientStateError()
                        output.flushSilence(20)
                    }
                    delay(2)
                    continue
                }

                // Decode if Opus, measuring latency
                val pcmData = if (codec == "opus") {
                    val decodeStart = nowUs()
                    val decoded = opusDecoder?.decode(chunk.pcmData) ?: ByteArray(0)
                    recordDecodeLatency(nowUs() - decodeStart)
                    decoded
                } else {
                    chunk.pcmData
                }

                if (pcmData.isEmpty()) {
                    Log.w(tag, "Empty PCM data after decode")
                    continue
                }

                val effectiveServerTsUs =
                    if (playAtServerUs != Long.MIN_VALUE) maxOf(chunk.serverTimestampUs, playAtServerUs)
                    else chunk.serverTimestampUs

                // Calculate effective playout offset: pipeline delay + measured codec decode latency
                val totalPlayoutOffsetUs = playoutOffsetUs - decodeLatencyUs

                val localPlayUs = (effectiveServerTsUs - offUs) + totalPlayoutOffsetUs
                val now = nowUs()
                val earlyUs = localPlayUs - now

                // If we're behind by a lot, drop chunks to catch up (audible effect).
                if (earlyUs < -dropLateUs) {
                    var dropped = 1
                    while (true) {
                        val next = jitter.pollPlayable(nowUs(), offUs, Long.MAX_VALUE) ?: break
                        val nextPcm = if (codec == "opus") {
                            val decodeStart = nowUs()
                            val decoded = opusDecoder?.decode(next.pcmData) ?: ByteArray(0)
                            recordDecodeLatency(nowUs() - decodeStart)
                            decoded
                        } else {
                            next.pcmData
                        }

                        val nextLocalPlayUs = (next.serverTimestampUs - offUs) + playoutOffsetUs
                        val nextEarlyUs = nextLocalPlayUs - nowUs()
                        dropped++
                        if (nextEarlyUs >= -targetLateUs) {
                            if (nextPcm.isNotEmpty()) {
                                output.writePcm(nextPcm)
                            }
                            break
                        }
                    }
                    Log.w(tag, "catch-up: late=${(-earlyUs) / 1000}ms dropped=$dropped playoutOffset=${playoutOffsetUs / 1000}ms")
                    continue
                }

                if (earlyUs > 5_000) {
                    delay((earlyUs / 1000).coerceAtMost(maxEarlySleepMs))
                }

                output.writePcm(pcmData)
            }
        }
    }

    private fun handleText(text: String) {
        try {
            val obj = JSONObject(text)
            val type = obj.optString("type", "")
            val payload = obj.optJSONObject("payload") ?: JSONObject()

            if (type!="server/time") Log.i(tag, "<<<< RECV: $type | ${text.take(200)}${if (text.length > 200) "..." else ""}")

            when (type) {
                "server/hello" -> {
                    handshakeComplete = true
                    val activeRoles = payload.optJSONArray("active_roles")?.let { arr ->
                        (0 until arr.length()).joinToString(",") { arr.getString(it) }
                    } ?: ""

                    val hasController = activeRoles.contains("controller")
                    val hasMetadata = activeRoles.contains("metadata")
                    val hasArtwork = activeRoles.contains("artwork")
                    Log.i(tag, "Active roles: $activeRoles, hasController: $hasController, hasMetadata: $hasMetadata, hasArtwork: $hasArtwork")

                    onUiUpdate {
                        it.copy(
                            status = "server/hello",
                            activeRoles = activeRoles,
                            hasController = hasController,
                            hasMetadata = hasMetadata,
                            hasArtwork = hasArtwork
                        )
                    }

                    startTimeSyncLoop()
                    startPlayoutLoop()
                    startStatsLoop()

                    // Send initial state with actual Android volume
                    val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                    val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                    val currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                    val volumePercent = (currentVolume * 100 / maxVolume).coerceIn(0, 100)

                    sendClientStateSynchronized(volume = volumePercent, muted = false)
                }

                "server/time" -> {
                    val clientTx = payload.getLong("client_transmitted")
                    val sRecv = payload.getLong("server_received")
                    val sTx = payload.getLong("server_transmitted")
                    val clientRx = nowUs()
                    clock.onServerTime(clientTx, clientRx, sRecv, sTx)
                }

                "stream/start" -> {
                    val player = payload.optJSONObject("player")
                    if (player != null) {
                        codec = player.optString("codec", codec)
                        sampleRate = player.optInt("sample_rate", sampleRate)
                        channels = player.optInt("channels", channels)
                        bitDepth = player.optInt("bit_depth", bitDepth)
                        playAtServerUs =
                            if (player.has("play_at")) player.optLong("play_at", Long.MIN_VALUE) else Long.MIN_VALUE

                        onUiUpdate {
                            it.copy(
                                status = "stream/start",
                                streamDesc = "$codec ${sampleRate}Hz ${channels}ch ${bitDepth}bit"
                            )
                        }

                        output.stop()
                        jitter.clear()
                        opusDecoder = null
                    }
                }

                "stream/clear" -> {
                    jitter.clear()
                    opusDecoder?.reset()
                }

                "stream/end" -> {
                    output.stop()
                    jitter.clear()
                    opusDecoder = null
                    playAtServerUs = Long.MIN_VALUE
                    onUiUpdate { it.copy(status = "stream/end", streamDesc = "") }
                }

                "group/update" -> {
                    val playbackState = payload.optString("playback_state", "")
                    val groupName = payload.optString("group_name", "")
                    onUiUpdate { it.copy(playbackState = playbackState, groupName = groupName) }
                }

                "server/state" -> {
                    val controller = payload.optJSONObject("controller")
                    if (controller != null) {
                        val volume = controller.optInt("volume", 100)
                        val muted = controller.optBoolean("muted", false)
                        val supportedCommands = controller.optJSONArray("supported_commands")?.let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }.toSet()
                        } ?: emptySet()

                        onUiUpdate {
                            it.copy(
                                groupVolume = volume,
                                groupMuted = muted,
                                supportedCommands = supportedCommands
                            )
                        }
                    }

                    // Handle metadata updates - ONLY update fields that are present
                    val metadata = payload.optJSONObject("metadata")
                    if (metadata != null) {
                        onUiUpdate { currentState ->
                            var newState = currentState

                            if (metadata.has("timestamp")) {
                                newState = newState.copy(metadataTimestamp = metadata.getLong("timestamp"))
                            }

                            if (metadata.has("title")) {
                                newState = newState.copy(trackTitle = if (metadata.isNull("title")) null else metadata.getString("title"))
                            }

                            if (metadata.has("artist")) {
                                newState = newState.copy(trackArtist = if (metadata.isNull("artist")) null else metadata.getString("artist"))
                            }

                            if (metadata.has("album")) {
                                newState = newState.copy(albumTitle = if (metadata.isNull("album")) null else metadata.getString("album"))
                            }

                            if (metadata.has("album_artist")) {
                                newState = newState.copy(albumArtist = if (metadata.isNull("album_artist")) null else metadata.getString("album_artist"))
                            }

                            if (metadata.has("year")) {
                                newState = newState.copy(trackYear = if (metadata.isNull("year")) null else metadata.getInt("year"))
                            }

                            if (metadata.has("track")) {
                                newState = newState.copy(trackNumber = if (metadata.isNull("track")) null else metadata.getInt("track"))
                            }

                            if (metadata.has("artwork_url")) {
                                val artworkUrl = if (metadata.isNull("artwork_url")) null else metadata.getString("artwork_url")
                                val currentUrl = newState.artworkUrl

                                newState = newState.copy(artworkUrl = artworkUrl)

                                // Only fetch artwork on non-low-memory devices
                                if (!isLowMemoryDevice && artworkUrl != currentUrl && artworkUrl != null) {
                                    // Clear old bitmap when URL changes
                                    if (currentUrl != null) {
                                        newState = newState.copy(artworkBitmap = null)
                                    }

                                    Log.i(tag, "Fetching artwork from URL: $artworkUrl")
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val url = java.net.URL(artworkUrl)
                                            val connection = url.openConnection()
                                            connection.connectTimeout = 5000
                                            connection.readTimeout = 5000
                                            val inputStream = connection.getInputStream()
                                            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                                            inputStream.close()

                                            if (bitmap != null) {
                                                Log.i(tag, "Downloaded artwork from URL: ${bitmap.width}x${bitmap.height}")
                                                val config = bitmap.config ?: android.graphics.Bitmap.Config.ARGB_8888
                                                val mutableBitmap = bitmap.copy(config, true)
                                                onUiUpdate { state ->
                                                    // Only update if the URL is still the same (avoid race conditions)
                                                    if (state.artworkUrl == artworkUrl) {
                                                        state.copy(artworkBitmap = mutableBitmap)
                                                    } else {
                                                        state
                                                    }
                                                }
                                            } else {
                                                Log.w(tag, "Failed to decode artwork from URL")
                                            }
                                        } catch (e: Exception) {
                                            Log.e(tag, "Error downloading artwork from URL", e)
                                        }
                                    }
                                }
                            }

                            // Parse progress object
                            if (metadata.has("progress")) {
                                val progress = metadata.optJSONObject("progress")
                                if (progress != null) {
                                    newState = newState.copy(
                                        trackProgress = progress.getLong("track_progress"),
                                        trackDuration = progress.getLong("track_duration"),
                                        playbackSpeed = progress.getInt("playback_speed")
                                    )
                                } else {
                                    // progress is null - clear it
                                    newState = newState.copy(
                                        trackProgress = null,
                                        trackDuration = null,
                                        playbackSpeed = null
                                    )
                                }
                            }

                            if (metadata.has("repeat")) {
                                newState = newState.copy(repeatMode = if (metadata.isNull("repeat")) null else metadata.getString("repeat"))
                            }

                            if (metadata.has("shuffle")) {
                                newState = newState.copy(shuffleEnabled = if (metadata.isNull("shuffle")) null else metadata.getBoolean("shuffle"))
                            }

                            newState
                        }

                        Log.i(tag, "Metadata update received")
                    }
                }

                "server/command" -> {
                    val player = payload.optJSONObject("player")
                    if (player != null) {
                        val command = player.optString("command", "")
                        when (command) {
                            "volume" -> {
                                val volume = player.optInt("volume", 100)
                                Log.i(tag, "server/command volume: $volume (server commanded)")
                                // Update UI state AND notify the onUiUpdate callback so ViewModel can set system volume
                                onUiUpdate { it.copy(playerVolume = volume, playerVolumeFromServer = true) }
                                // Echo back in state
                                sendClientStatePlayer(volume = volume, muted = null)
                            }
                            "mute" -> {
                                val muted = player.optBoolean("mute", false)
                                Log.i(tag, "server/command mute: $muted (server commanded)")
                                // Update UI state AND notify the onUiUpdate callback
                                onUiUpdate { it.copy(playerMuted = muted, playerMutedFromServer = true) }
                                // Echo back in state
                                sendClientStatePlayer(volume = null, muted = muted)
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(tag, "Bad JSON: ${t.message}", t)
        }
    }

    private fun handleBinary(data: ByteArray) {
        if (!handshakeComplete) return
        if (data.isEmpty()) return

        val type = data[0].toInt() and 0xFF

        when (type) {
            4 -> {
                // Audio chunk (player role)
                if (codec != "pcm" && codec != "opus") return
                if (data.size < 1 + 8 + 1) return

                val tsServerUs = readInt64BE(data, 1)
                val encodedData = data.copyOfRange(1 + 8, data.size)

                jitter.offer(tsServerUs, encodedData, clock.estimatedOffsetUs(), nowUs())
            }
            8 -> {
                // Artwork channel 0 (album art)
                if (data.size < 1 + 8) {
                    // Empty image - clear artwork
                    Log.i(tag, "Clearing artwork")
                    onUiUpdate { it.copy(artworkBitmap = null) }
                    return
                }

                val tsServerUs = readInt64BE(data, 1)
                val imageData = data.copyOfRange(1 + 8, data.size)

                Log.i(tag, "Received artwork binary message, size=${imageData.size} bytes")

                // Decode image in background
                scope.launch(Dispatchers.Default) {
                    try {
                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                        if (bitmap != null) {
                            Log.i(tag, "Decoded artwork: ${bitmap.width}x${bitmap.height}")
                            // Create a mutable copy to ensure it's a new instance that triggers recomposition
                            val config = bitmap.config ?: android.graphics.Bitmap.Config.ARGB_8888
                            val mutableBitmap = bitmap.copy(config, true)
                            onUiUpdate { state ->
                                Log.i(tag, "Updating UI state with new artwork")
                                state.copy(artworkBitmap = mutableBitmap)
                            }
                        } else {
                            Log.w(tag, "Failed to decode artwork bitmap - BitmapFactory returned null")
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Error decoding artwork", e)
                    }
                }
            }
        }
    }

    private fun readInt64BE(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (buf[off + i].toLong() and 0xFFL)
        return v
    }

    private fun nowUs(): Long = System.nanoTime() / 1000L
}