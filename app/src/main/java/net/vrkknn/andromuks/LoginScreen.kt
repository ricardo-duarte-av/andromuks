package net.vrkknn.andromuks

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.utils.performHttpLogin
import okhttp3.OkHttpClient

@Composable
fun rememberImeVisible(): Boolean {
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val imeHeight = ime.getBottom(density)
    return imeHeight > 0
}

@Composable
fun LoginScreen(navController: NavController, modifier: Modifier = Modifier, appViewModel: AppViewModel = viewModel()) {
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val client = remember { OkHttpClient.Builder().build() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val sharedPreferences = remember {
        context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
    }

    val imeVisible = rememberImeVisible()
    val logoScale by animateFloatAsState(targetValue = if (imeVisible) 1f else 2f, label = "logoScale")

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top: flexible space for logo
            Box(
                modifier = Modifier
                    .then(
                        if (imeVisible) Modifier.weight(1f)
                        else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Only the logo scales
                androidx.compose.foundation.Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                    contentDescription = "Gomuks Icon",
                    modifier = Modifier
                        .fillMaxWidth(0.8f) // large base size
                        .aspectRatio(1f)
                        .graphicsLayer {
                            scaleX = logoScale
                            scaleY = logoScale
                        },
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground)
                )
            }

            // Middle: static title
            Text(
                text = "Andromuks",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 16.dp)
            )

            // Bottom: login form
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Homeserver URL") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        appViewModel.isLoading = true
                        performHttpLogin(
                            url = url,
                            username = username,
                            password = password,
                            client = client,
                            scope = scope,
                            sharedPreferences = sharedPreferences,
                            onSuccess = {
                                scope.launch {
                                    appViewModel.isLoading = false
                                    navController.navigate("auth_check")
                                }
                            },
                            onFailure = {
                                scope.launch {
                                    appViewModel.isLoading = false
                                }
                            }
                        )
                    },
                    enabled = url.isNotBlank() && username.isNotBlank() && password.isNotBlank() && !appViewModel.isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = if (appViewModel.isLoading) "Logging in..." else "Login")
                }
                if (appViewModel.isLoading) {
                    Spacer(modifier = Modifier.height(16.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}
