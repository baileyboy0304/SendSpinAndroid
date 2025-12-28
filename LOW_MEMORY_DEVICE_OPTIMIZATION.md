# Low-Memory Device Optimization

This document describes the optimizations implemented for Android Go and low-memory devices (< 2GB RAM).

## Overview

The app automatically detects devices with less than 2GB of RAM and disables expensive features to improve performance and reduce memory consumption.

## Optimizations Applied

### 1. **Artwork Handling** 
- **Disabled:** Artwork downloading and bitmap processing
- **Impact:** Eliminates network requests and memory overhead for image decoding
- **Benefit:** Reduces memory usage and network bandwidth

### 2. **Notification Action Buttons**
- **Disabled:** Previous, Play/Pause, Next buttons in notification
- **Reason:** Reduced memory footprint and simpler notification rendering
- **Users can:** Tap notification to open app for playback control

### 3. **Artwork in User Interface**
- **Disabled:** Large icon display in notifications
- **Impact:** Cleaner, simpler notification with less memory usage

### 4. **Artwork Role Declaration**
- **Not Declared:** Low-memory devices don't advertise `artwork@v1` support
- **Result:** Server doesn't waste bandwidth sending artwork data
- **Benefit:** Reduces network traffic and processing overhead

## Automatic Detection

Detection happens in two places:

### SendspinPcmClient
```kotlin
private val isLowMemoryDevice = checkIsLowMemoryDevice()

private fun checkIsLowMemoryDevice(): Boolean {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    activityManager?.getMemoryInfo(memInfo)
    return memInfo?.totalMem ?: 0L < 2_000_000_000L  // < 2GB
}
```

### SendspinService
Same detection logic applied to notification creation.

## What Still Works on Low-Memory Devices

✅ **Audio Playback** - Full functionality with Opus, FLAC, and PCM codecs
✅ **Metadata** - Track title, artist, album, year display (text only)
✅ **Playback Control** - Via app UI or via tap-to-open notification
✅ **Volume Control** - Full group and player volume adjustment
✅ **Playback State** - Play, pause, stop, next, previous commands
✅ **Clock Synchronization** - Accurate multi-room sync maintained
✅ **Repeat & Shuffle** - Full playback mode support

## Memory Savings

Estimated memory savings on low-memory devices:
- **Artwork Bitmap:** 4-10 MB (typical album art at 800x800px JPEG → decoded bitmap)
- **Artwork Network Buffer:** ~100 KB per transfer
- **Notification Rendering:** ~500 KB (reduced complexity)
- **Total Potential Savings:** 4-10 MB RAM + reduced network overhead

## Device Detection Threshold

Currently set to **< 2GB RAM** because:
- Android Go devices typically have 1-2GB RAM
- Devices with < 2GB struggle with bitmap processing and large notification caches
- Devices with > 2GB have sufficient resources for all features

This is a reasonable threshold that balances feature parity with performance on entry-level devices.

## Future Considerations

If needed, the threshold can be adjusted or made configurable:
```kotlin
// Adjust threshold (currently 2GB)
const val LOW_MEMORY_THRESHOLD_BYTES = 2_000_000_000L
```

Or add more granular feature flags:
```kotlin
private val skipArtwork = totalMem < 2_000_000_000L
private val skipNotificationActions = totalMem < 1_500_000_000L
private val skipMetadataDisplay = totalMem < 1_000_000_000L
```
