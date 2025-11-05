package net.vrkknn.andromuks.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity for storing account_data from sync_complete messages.
 * 
 * Account data includes:
 * - m.direct: Direct message room mappings
 * - io.element.recent_emoji: Recently used emojis
 * - m.push_rules: Push notification rules
 * - Various other user settings and preferences
 * 
 * We store the entire account_data JSON to preserve all data types.
 */
@Entity(tableName = "account_data")
data class AccountDataEntity(
    @PrimaryKey val key: String = "account_data", // Single row, always use this key
    val accountDataJson: String // Full account_data JSON object as string
)

