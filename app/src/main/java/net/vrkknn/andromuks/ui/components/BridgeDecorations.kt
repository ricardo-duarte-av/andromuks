package net.vrkknn.andromuks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import net.vrkknn.andromuks.BridgeInfo
import net.vrkknn.andromuks.utils.AvatarUtils
import net.vrkknn.andromuks.utils.ImageLoaderSingleton

@Composable
fun BridgeNetworkBadge(
    bridgeInfo: BridgeInfo,
    homeserverUrl: String,
    authToken: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    if (!bridgeInfo.hasRenderableIcon) {
        return
    }

    val badgeSize = 36.dp
    val networkName = bridgeInfo.displayName ?: "Bridge"
    val semanticsModifier = modifier.semantics {
        contentDescription = "Bridge network: $networkName"
    }

    if (onClick != null) {
        IconButton(
            onClick = onClick,
            modifier = semanticsModifier
        ) {
            AvatarImage(
                mxcUrl = bridgeInfo.avatarUrl,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                fallbackText = networkName,
                size = badgeSize,
                userId = bridgeInfo.protocol?.id ?: bridgeInfo.channel?.id,
                displayName = networkName,
                isVisible = true
            )
        }
    } else {
        Box(
            modifier = semanticsModifier
                .size(badgeSize)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            AvatarImage(
                mxcUrl = bridgeInfo.avatarUrl,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                fallbackText = networkName,
                size = badgeSize,
                userId = bridgeInfo.protocol?.id ?: bridgeInfo.channel?.id,
                displayName = networkName,
                isVisible = true
            )
        }
    }
}

@Composable
fun BridgeBackgroundLayer(
    bridgeInfo: BridgeInfo?,
    homeserverUrl: String,
    authToken: String,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 18.dp,
    alpha: Float = 0.08f
) {
    if (bridgeInfo == null) {
        return
    }

    val avatarSource = bridgeInfo.avatarUrl ?: return
    val context = LocalContext.current
    val imageLoader = remember { ImageLoaderSingleton.get(context) }
    val imageUrl = remember(avatarSource, homeserverUrl) {
        AvatarUtils.getFullImageUrl(context, avatarSource, homeserverUrl)
            ?: AvatarUtils.getAvatarUrl(context, avatarSource, homeserverUrl)
    } ?: return

    Box(modifier = modifier.fillMaxSize()) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageUrl)
                .apply {
                    size(coil.size.Size.ORIGINAL)
                    if (imageUrl.startsWith("http")) {
                        addHeader("Cookie", "gomuks_auth=$authToken")
                    }
                }
                .build(),
            imageLoader = imageLoader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(blurRadius)
                .alpha(alpha)
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.25f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.2f)
                        )
                    )
                )
        )
    }
}

