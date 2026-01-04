package net.vrkknn.andromuks

import android.util.Log
import net.vrkknn.andromuks.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * EmojiPacksCache - Singleton cache for custom emoji packs
 * 
 * This singleton stores custom emoji packs loaded from the server.
 * It allows any AppViewModel instance to access emoji packs, even when opening from notifications.
 * 
 * Structure: List<AppViewModel.EmojiPack>
 */
object EmojiPacksCache {
    private const val TAG = "EmojiPacksCache"
    
    // Thread-safe list storing emoji packs
    private val packsList = mutableListOf<AppViewModel.EmojiPack>()
    private val cacheLock = Any()
    
    /**
     * Set all emoji packs
     */
    fun setAll(packs: List<AppViewModel.EmojiPack>) {
        synchronized(cacheLock) {
            packsList.clear()
            packsList.addAll(packs)
            if (BuildConfig.DEBUG) Log.d(TAG, "EmojiPacksCache: setAll - updated cache with ${packs.size} packs")
        }
    }
    
    /**
     * Get all emoji packs
     */
    fun getAll(): List<AppViewModel.EmojiPack> {
        return synchronized(cacheLock) {
            packsList.toList() // Return a copy
        }
    }
    
    /**
     * Add or update an emoji pack
     */
    fun updatePack(pack: AppViewModel.EmojiPack) {
        synchronized(cacheLock) {
            val existingIndex = packsList.indexOfFirst { 
                it.roomId == pack.roomId && it.packName == pack.packName 
            }
            if (existingIndex >= 0) {
                packsList[existingIndex] = pack
                if (BuildConfig.DEBUG) Log.d(TAG, "EmojiPacksCache: updatePack - updated pack ${pack.packName} in room ${pack.roomId}")
            } else {
                packsList.add(pack)
                if (BuildConfig.DEBUG) Log.d(TAG, "EmojiPacksCache: updatePack - added new pack ${pack.packName} in room ${pack.roomId}")
            }
        }
    }
    
    /**
     * Remove an emoji pack
     */
    fun removePack(roomId: String, packName: String) {
        synchronized(cacheLock) {
            val removed = packsList.removeAll { 
                it.roomId == roomId && it.packName == packName 
            }
            if (removed && BuildConfig.DEBUG) {
                Log.d(TAG, "EmojiPacksCache: removePack - removed pack $packName from room $roomId")
            }
        }
    }
    
    /**
     * Get the number of emoji packs
     */
    fun getCount(): Int {
        return synchronized(cacheLock) {
            packsList.size
        }
    }
    
    /**
     * Clear all emoji packs from the cache
     */
    fun clear() {
        synchronized(cacheLock) {
            packsList.clear()
            if (BuildConfig.DEBUG) Log.d(TAG, "EmojiPacksCache: Cleared all emoji packs")
        }
    }
}

