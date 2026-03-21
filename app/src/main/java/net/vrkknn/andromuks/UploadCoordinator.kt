package net.vrkknn.andromuks

/**
 * Upload progress and in-flight upload tracking for [AppViewModel].
 */
internal class UploadCoordinator(private val vm: AppViewModel) {

    fun hasUploadInProgress(roomId: String): Boolean = with(vm) {
        uploadInProgressCount[roomId] ?: 0 > 0
    }

    fun getUploadType(roomId: String): String = with(vm) {
        val types = uploadTypesPerRoom[roomId] ?: return "media"
        when {
            types.contains("video") -> "video"
            types.contains("image") -> "image"
            types.contains("audio") -> "audio"
            types.contains("file") -> "file"
            else -> "media"
        }
    }

    fun getUploadRetryCount(roomId: String): Int = with(vm) {
        uploadRetryCounts[roomId] ?: 0
    }

    fun setUploadRetryCount(roomId: String, count: Int) = with(vm) {
        if (count > 0) {
            uploadRetryCounts[roomId] = count
        } else {
            uploadRetryCounts.remove(roomId)
        }
    }

    fun setUploadProgress(roomId: String, key: String, progress: Float) = with(vm) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: setUploadProgress for room $roomId: $key = $progress")
        val currentProgress = uploadProgressPerRoom[roomId]?.toMutableMap() ?: mutableMapOf()
        currentProgress[key] = progress
        uploadProgressPerRoom[roomId] = currentProgress
    }

    fun getUploadProgress(roomId: String): Map<String, Float> = with(vm) {
        uploadProgressPerRoom[roomId] ?: emptyMap()
    }

    fun beginUpload(roomId: String, uploadType: String = "image") = with(vm) {
        val current = uploadInProgressCount[roomId] ?: 0
        uploadInProgressCount[roomId] = current + 1

        val types = uploadTypesPerRoom.getOrPut(roomId) { mutableSetOf() }
        types.add(uploadType)

        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Begin upload for room $roomId (type: $uploadType, count: ${uploadInProgressCount[roomId]})")
    }

    fun endUpload(roomId: String, uploadType: String = "image") = with(vm) {
        val current = uploadInProgressCount[roomId] ?: 0
        if (current > 0) {
            val newCount = current - 1
            if (newCount == 0) {
                uploadInProgressCount.remove(roomId)
                uploadTypesPerRoom.remove(roomId)
                uploadRetryCounts.remove(roomId)
                uploadProgressPerRoom.remove(roomId)
            } else {
                uploadInProgressCount[roomId] = newCount
            }
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: End upload for room $roomId (type: $uploadType, count: $newCount)")
        }
    }
}
