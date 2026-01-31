package net.vrkknn.andromuks

import androidx.compose.runtime.Immutable

@Immutable
data class BridgeInfo(
    val stateKey: String?,
    val bridgeBot: String?,
    val creator: String?,
    val roomType: String?,
    val roomTypeV2: String?,
    val channel: BridgeChannelInfo?,
    val protocol: BridgeProtocolInfo?
) {
    val displayName: String?
        get() = protocol?.displayName
            ?: protocol?.id
            ?: channel?.displayName
            ?: channel?.id

    val avatarUrl: String?
        get() = protocol?.avatarUrl ?: channel?.avatarUrl

    val hasRenderableIcon: Boolean
        get() = !avatarUrl.isNullOrBlank() || !displayName.isNullOrBlank()
}

@Immutable
data class BridgeChannelInfo(
    val id: String?,
    val displayName: String?,
    val avatarUrl: String?,
    val receiver: String?
)

@Immutable
data class BridgeProtocolInfo(
    val id: String?,
    val displayName: String?,
    val avatarUrl: String?,
    val externalUrl: String?
)

