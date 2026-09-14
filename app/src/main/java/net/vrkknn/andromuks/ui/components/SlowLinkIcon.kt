package net.vrkknn.andromuks.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.vrkknn.andromuks.ui.theme.scaledTweenMs

/**
 * Header icon for a socket that is connected but slow (`SyncRepository.linkSlow`). Deliberately static
 * and in `tertiary`: it is a caveat about freshness, not an error, so it must not pulse like the red
 * CloudOff shown when there is no connection at all. See docs/OFFLINE_INDICATOR.md.
 */
@Composable
fun SlowLinkIcon(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(scaledTweenMs(300))),
        exit = fadeOut(animationSpec = tween(scaledTweenMs(300))),
    ) {
        Icon(
            imageVector = Icons.Filled.NetworkCheck,
            contentDescription = "Slow server connection",
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(20.dp),
        )
    }
}
