package net.vrkknn.andromuks

import androidx.compose.runtime.Immutable

/**
 * Parsed `m.bridge` / `uk.half-shot.bridge` state event (MSC2346).
 *
 * The spec defines THREE nested descriptors, from most generic to most specific:
 *   - [protocol] — the remote network *type* ("Discord", "WhatsApp"). Stable across bridges.
 *   - [network]  — the specific instance of that network (a Discord guild, an IRC server).
 *   - [channel]  — the individual room/channel on that network.
 *
 * Different bridges populate different subsets. mautrix-discord puts the Discord logo on
 * `protocol.avatar_url`; OOYE (`moe.cadence.ooye`) leaves protocol icon-less and puts the
 * *guild* icon on `network.avatar_url`. Hence the fallback chains below: identity questions
 * ("which network is this?") resolve protocol-first, while "give me something to draw"
 * walks protocol → network → channel.
 */
@Immutable
data class BridgeInfo(
    val stateKey: String?,
    val bridgeBot: String?,
    val creator: String?,
    val roomType: String?,
    val roomTypeV2: String?,
    val channel: BridgeChannelInfo?,
    val network: BridgeNetworkInfo?,
    val protocol: BridgeProtocolInfo?,
) {
    val displayName: String?
        get() = protocol?.displayName
            ?: protocol?.id
            ?: network?.displayName
            ?: network?.id
            ?: channel?.displayName
            ?: channel?.id

    /**
     * Best available icon. Protocol first (the network's own logo, shared by every room on it),
     * then the network instance (guild/server icon), then the channel.
     */
    val avatarUrl: String?
        get() = protocol?.avatarUrl ?: network?.avatarUrl ?: channel?.avatarUrl

    /** Deep link back to the remote network. Bridges scatter this across all three descriptors. */
    val externalUrl: String?
        get() = protocol?.externalUrl ?: network?.externalUrl ?: channel?.externalUrl

    /**
     * Stable grouping identity for the Bridges tab — the remote network *type*, not the instance.
     * Two rooms with this same value belong in the same bridge pseudo-space even when they came
     * from different bridge implementations or carry different icons (mautrix-discord and OOYE
     * both report `protocol.id == "discord"`).
     *
     * Falls back to the protocol display name, then the network id, so bridges that omit
     * `protocol.id` still group by *something* stabler than an avatar URL. Null only when the
     * event carries no protocol/network descriptor at all.
     */
    val protocolId: String?
        get() = protocol?.id ?: protocol?.displayName ?: network?.id

    val hasRenderableIcon: Boolean
        get() = !avatarUrl.isNullOrBlank() || !displayName.isNullOrBlank()
}

@Immutable
data class BridgeChannelInfo(val id: String?, val displayName: String?, val avatarUrl: String?, val receiver: String?, val externalUrl: String?)

@Immutable
data class BridgeNetworkInfo(val id: String?, val displayName: String?, val avatarUrl: String?, val externalUrl: String?)

@Immutable
data class BridgeProtocolInfo(val id: String?, val displayName: String?, val avatarUrl: String?, val externalUrl: String?)
