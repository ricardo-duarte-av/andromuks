package net.vrkknn.andromuks

import org.json.JSONArray

/**
 * Activity log, cache stats, and byte formatting — for [AppViewModel].
 */
internal class DiagnosticsCoordinator(private val vm: AppViewModel) {

    companion object {
        private const val MAX_LOG_ENTRIES = 100
        private const val ACTIVITY_LOG_PREFS_NAME = "AndromuksActivityLogPrefs"
        private const val ACTIVITY_LOG_PREFS_KEY = "activity_log"
    }

    fun logActivity(event: String, networkType: String? = null) {
        with(vm) {
            val lower = event.lowercase()
            val isCommandNoise =
                lower.startsWith("command ") ||
                    lower.startsWith("command acknowledged") ||
                    lower.startsWith("matrix server error") ||
                    lower.startsWith("matrix server confirmed")
            if (isCommandNoise) {
                return@with
            }

            val entry = AppViewModel.ActivityLogEntry(
                timestamp = System.currentTimeMillis(),
                event = event,
                networkType = networkType
            )
            synchronized(activityLogLock) {
                activityLog.add(entry)
                if (activityLog.size > MAX_LOG_ENTRIES) {
                    activityLog.removeAt(0)
                }
            }
        }
        saveActivityLogToStorage()
    }

    fun getActivityLog(): List<AppViewModel.ActivityLogEntry> = with(vm) {
        synchronized(activityLogLock) { activityLog.toList() }
    }

    internal fun loadActivityLogFromStorage(context: android.content.Context? = null) = with(vm) {
        val ctx = context ?: appContext
        ctx?.let { ctx ->
            try {
                val prefs = ctx.getSharedPreferences(ACTIVITY_LOG_PREFS_NAME, android.content.Context.MODE_PRIVATE)
                val logJson = prefs.getString(ACTIVITY_LOG_PREFS_KEY, null)

                if (logJson != null) {
                    val logArray = JSONArray(logJson)
                    synchronized(activityLogLock) {
                        activityLog.clear()

                        for (i in 0 until logArray.length()) {
                            val entryJson = logArray.getJSONObject(i)
                            activityLog.add(AppViewModel.ActivityLogEntry.fromJson(entryJson))
                        }

                        if (activityLog.size > MAX_LOG_ENTRIES) {
                            val entriesToKeep = activityLog.takeLast(MAX_LOG_ENTRIES)
                            activityLog.clear()
                            activityLog.addAll(entriesToKeep)
                        }

                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Loaded ${activityLog.size} activity log entries from storage")
                    }
                } else {
                    val legacyPrefs = ctx.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
                    val legacyLogJson = legacyPrefs.getString(ACTIVITY_LOG_PREFS_KEY, null)
                    if (legacyLogJson != null) {
                        val logArray = JSONArray(legacyLogJson)
                        synchronized(activityLogLock) {
                            activityLog.clear()
                            for (i in 0 until logArray.length()) {
                                val entryJson = logArray.getJSONObject(i)
                                activityLog.add(AppViewModel.ActivityLogEntry.fromJson(entryJson))
                            }
                            if (activityLog.size > MAX_LOG_ENTRIES) {
                                val entriesToKeep = activityLog.takeLast(MAX_LOG_ENTRIES)
                                activityLog.clear()
                                activityLog.addAll(entriesToKeep)
                            }
                        }
                        this@DiagnosticsCoordinator.saveActivityLogToStorage()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Failed to load activity log from storage", e)
            }
        }
    }

    internal fun saveActivityLogToStorage() = with(vm) {
        appContext?.let { context ->
            try {
                val prefs = context.getSharedPreferences(ACTIVITY_LOG_PREFS_NAME, android.content.Context.MODE_PRIVATE)
                val logArray = JSONArray()

                val entriesToSave: List<AppViewModel.ActivityLogEntry> = synchronized(activityLogLock) {
                    if (activityLog.size > MAX_LOG_ENTRIES) {
                        activityLog.takeLast(MAX_LOG_ENTRIES).toList()
                    } else {
                        activityLog.toList()
                    }
                }

                entriesToSave.forEach { entry ->
                    logArray.put(entry.toJson())
                }

                prefs.edit()
                    .putString(ACTIVITY_LOG_PREFS_KEY, logArray.toString())
                    .commit()

            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Failed to save activity log to storage", e)
            }
        }
    }

    fun getCacheStatistics(context: android.content.Context): Map<String, String> {
        val stats = mutableMapOf<String, String>()

        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val freeMemory = runtime.freeMemory()
        val totalMemory = runtime.totalMemory()
        val memoryUsagePercent = (usedMemory.toDouble() / maxMemory.toDouble() * 100).toInt()

        stats["app_ram_usage"] = formatBytes(usedMemory)
        stats["app_ram_max"] = formatBytes(maxMemory)
        stats["app_ram_free"] = formatBytes(freeMemory)
        stats["app_ram_total"] = formatBytes(totalMemory)
        stats["app_ram_usage_percent"] = "$memoryUsagePercent%"

        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "Memory Stats: Used=${formatBytes(usedMemory)}, Free=${formatBytes(freeMemory)}, Max=${formatBytes(maxMemory)}, Usage=$memoryUsagePercent%")

        val timelineCacheStats = RoomTimelineCache.getCacheStats()
        val totalTimelineEvents = timelineCacheStats["total_events_cached"] as? Int ?: 0
        val estimatedTimelineMemory = totalTimelineEvents * 1.5 * 1024
        stats["timeline_memory_cache"] = formatBytes(estimatedTimelineMemory.toLong())
        stats["timeline_event_count"] = "$totalTimelineEvents events"

        val flattenedCount = ProfileCache.getFlattenedCacheSize()
        val roomMemberCount = RoomMemberCache.getAllMembers().values.sumOf { it.size }
        val globalCount = ProfileCache.getGlobalCacheSize()
        val estimatedProfileMemory = (flattenedCount + roomMemberCount + globalCount) * 350L
        val perRoomProfilesCount = flattenedCount + roomMemberCount
        val globalProfilesCount = globalCount
        val estimatedPerRoomProfileMemory = perRoomProfilesCount * 350L
        val estimatedGlobalProfileMemory = globalProfilesCount * 350L
        stats["user_profiles_memory_cache"] = formatBytes(estimatedProfileMemory)
        stats["user_profiles_count"] = "${flattenedCount + roomMemberCount + globalCount} profiles"
        stats["user_profiles_room_memory_cache"] = formatBytes(estimatedPerRoomProfileMemory)
        stats["user_profiles_room_count"] = "$perRoomProfilesCount profiles"
        stats["user_profiles_global_memory_cache"] = formatBytes(estimatedGlobalProfileMemory)
        stats["user_profiles_global_count"] = "$globalProfilesCount profiles"

        val profileDiskSize = 0L
        stats["user_profiles_disk_cache"] = formatBytes(profileDiskSize)

        val mediaMemoryCacheSize = try {
            val imageLoader = net.vrkknn.andromuks.utils.ImageLoaderSingleton.get(context)
            imageLoader.memoryCache
            val rt = Runtime.getRuntime()
            (rt.maxMemory() * 0.25).toLong()
        } catch (e: Exception) {
            0L
        }
        stats["media_memory_cache"] = formatBytes(mediaMemoryCacheSize)
        stats["media_memory_cache_max"] = "Max: ${formatBytes(mediaMemoryCacheSize)}"

        val mediaDiskCacheSize = try {
            val cacheDir = java.io.File(context.cacheDir, "image_cache")
            if (cacheDir.exists() && cacheDir.isDirectory) {
                cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
        stats["media_disk_cache"] = formatBytes(mediaDiskCacheSize)

        return stats
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0

        return when {
            gb >= 1.0 -> String.format("%.2f GB", gb)
            mb >= 1.0 -> String.format("%.2f MB", mb)
            kb >= 1.0 -> String.format("%.2f KB", kb)
            else -> "$bytes B"
        }
    }
}
