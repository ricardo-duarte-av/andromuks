package net.vrkknn.andromuks

import android.util.Log
import net.vrkknn.andromuks.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * StickerPacksCache - Singleton cache for sticker packs
 * 
 * This singleton stores sticker packs loaded from the server.
 * It allows any AppViewModel instance to access sticker packs, even when opening from notifications.
 * 
 * Structure: List<AppViewModel.StickerPack>
 */
object StickerPacksCache {
    private const val TAG = "StickerPacksCache"
    
    // Thread-safe list storing sticker packs
    private val packsList = mutableListOf<AppViewModel.StickerPack>()
    private val cacheLock = Any()
    
    /**
     * Set all sticker packs
     */
    fun setAll(packs: List<AppViewModel.StickerPack>) {
        synchronized(cacheLock) {
            packsList.clear()
            packsList.addAll(packs)
            if (BuildConfig.DEBUG) Log.d(TAG, "StickerPacksCache: setAll - updated cache with ${packs.size} packs")
        }
    }
    
    /**
     * Get all sticker packs
     */
    fun getAll(): List<AppViewModel.StickerPack> {
        return synchronized(cacheLock) {
            packsList.toList() // Return a copy
        }
    }
    
    /**
     * Add or update a sticker pack
     */
    fun updatePack(pack: AppViewModel.StickerPack) {
        synchronized(cacheLock) {
            val existingIndex = packsList.indexOfFirst { 
                it.roomId == pack.roomId && it.packName == pack.packName 
            }
            if (existingIndex >= 0) {
                packsList[existingIndex] = pack
                if (BuildConfig.DEBUG) Log.d(TAG, "StickerPacksCache: updatePack - updated pack ${pack.packName} in room ${pack.roomId}")
            } else {
                packsList.add(pack)
                if (BuildConfig.DEBUG) Log.d(TAG, "StickerPacksCache: updatePack - added new pack ${pack.packName} in room ${pack.roomId}")
            }
        }
    }
    
    /**
     * Remove a sticker pack
     */
    fun removePack(roomId: String, packName: String) {
        synchronized(cacheLock) {
            val removed = packsList.removeAll { 
                it.roomId == roomId && it.packName == packName 
            }
            if (removed && BuildConfig.DEBUG) {
                Log.d(TAG, "StickerPacksCache: removePack - removed pack $packName from room $roomId")
            }
        }
    }
    
    /**
     * Get the number of sticker packs
     */
    fun getCount(): Int {
        return synchronized(cacheLock) {
            packsList.size
        }
    }
    
    /**
     * Clear all sticker packs from the cache
     */
    fun clear() {
        synchronized(cacheLock) {
            packsList.clear()
            if (BuildConfig.DEBUG) Log.d(TAG, "StickerPacksCache: Cleared all sticker packs")
        }
    }
}

