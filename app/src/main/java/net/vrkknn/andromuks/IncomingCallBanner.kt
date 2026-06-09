package net.vrkknn.andromuks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import net.vrkknn.andromuks.ui.theme.scaledSpring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

@Composable
fun IncomingCallBanner(appViewModel: AppViewModel) {
    val info = appViewModel.incomingCallInfo ?: return

    val callerProfile = appViewModel.getUserProfile(info.callerId, info.roomId)
    val callerName = callerProfile?.displayName?.takeIf { it.isNotBlank() }
        ?: info.callerId.substringBefore(":").removePrefix("@")
    val roomName = appViewModel.getRoomById(info.roomId)?.name
        ?: info.roomId.substringBefore(":").removePrefix("!")
    val isVideo = info.callIntent != "m.voice"

    // Auto-dismiss when the notification's own lifetime expires.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(info) {
        while (System.currentTimeMillis() < info.expiresAt) {
            delay(500)
            now = System.currentTimeMillis()
        }
        appViewModel.dismissIncomingCall()
    }

    val green = Color(0xFF2E7D32)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(20f),
        contentAlignment = Alignment.TopCenter
    ) {
        AnimatedVisibility(
            visible = true,
            enter = slideInVertically(initialOffsetY = { -it }, animationSpec = scaledSpring(stiffness = Spring.StiffnessMediumLow)) +
                fadeIn(animationSpec = scaledSpring(stiffness = Spring.StiffnessMediumLow)),
            exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = scaledSpring(stiffness = Spring.StiffnessMediumLow)) +
                fadeOut(animationSpec = scaledSpring(stiffness = Spring.StiffnessMediumLow))
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 48.dp),
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isVideo) Icons.Filled.Videocam else Icons.Filled.Call,
                        contentDescription = null,
                        tint = green,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "$callerName is calling",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = roomName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { appViewModel.dismissIncomingCall() },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Filled.CallEnd, contentDescription = "Dismiss")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            appViewModel.dismissIncomingCall()
                            appViewModel.startCall(info.roomId)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = green)
                    ) {
                        Icon(
                            imageVector = if (isVideo) Icons.Filled.Videocam else Icons.Filled.Call,
                            contentDescription = "Join"
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Join")
                    }
                }
            }
        }
    }
}
