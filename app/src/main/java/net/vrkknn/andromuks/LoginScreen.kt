package net.vrkknn.andromuks

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.utils.performHttpLogin
import okhttp3.OkHttpClient

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

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
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
        ) {
            Text(text = if (appViewModel.isLoading) "Logging in..." else "Login")
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (appViewModel.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
