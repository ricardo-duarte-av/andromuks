package net.vrkknn.andromuks

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.ui.theme.AndromuksTheme
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Callback
import okhttp3.Call
import okhttp3.Response
import okio.IOException
import org.json.JSONObject

@Composable
fun LoginScreen(navController: NavController, modifier: Modifier = Modifier) {
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val sharedPreferences = remember {
        context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
    }
    val client = remember { OkHttpClient.Builder().build() }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.url_label)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(R.string.user_label)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text(stringResource(R.string.password_label)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
            visualTransformation = PasswordVisualTransformation()
        )
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                isLoading = true
                statusMessage = null
                val authUrlForHttpRequest = buildAuthHttpUrl(url)
                Log.d("LoginScreen", "Attempting HTTPS login to: $authUrlForHttpRequest with user: $username")

                val credentials = okhttp3.Credentials.basic(username, pass)

                val requestBody = "".toRequestBody(null)
                val request = Request.Builder()
                    .url(authUrlForHttpRequest)
                    .header("Authorization", credentials)
                    .post(requestBody)
                    .build()

                Log.d("LoginScreen", "Request: $request with Authorization header")

                client.newCall(request).enqueue(object: Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        scope.launch {
                            isLoading = false
                            statusMessage = "Login failed: ${e.message}"
                        }
                        Log.e("LoginScreen", "HTTPS Login onFailure", e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val responseBodyString = response.body.string()
                        if (response.isSuccessful) {
                            try {
                                val jsonResponse = JSONObject(responseBodyString)
                                val receivedToken = jsonResponse.optString("token", null)
                                if (receivedToken != null) {
                                    with(sharedPreferences.edit()) {
                                        putString("gomuks_auth_token", receivedToken)
                                        putString("server_base_url", url)
                                        apply()
                                    }
                                    Log.d("LoginScreen", "Token and server base URL saved to SharedPreferences.")
                                    scope.launch {
                                        statusMessage = "Login successful! Connecting to WebSocket..."
                                    }

                                    connectToWebSocket(
                                        baseServerUrl = url,
                                        httpClient = client,
                                        scope = scope,
                                        sharedPreferences = sharedPreferences,
                                        onConnectionAttemptFinished = {
                                            scope.launch { isLoading = false }
                                        },
                                        navigateToLogin = {
                                            scope.launch {
                                                statusMessage = "WebSocket connection failed. Please check logs or server."
                                            }
                                        },
                                        navigateToRoomList = {
                                            scope.launch {
                                                navController.navigate("roomlist") {
                                                    popUpTo("login") { inclusive = true }
                                                }
                                            }
                                        }
                                    )
                                } else {
                                    scope.launch {
                                        isLoading = false
                                        statusMessage = "Login successful, but token not found in response."
                                    }
                                }
                            } catch (e: Exception) {
                                scope.launch {
                                    isLoading = false
                                    statusMessage = "Login successful, but error parsing token: ${e.message}"
                                }
                            }
                        } else {
                            scope.launch {
                                isLoading = false
                                statusMessage = "Login failed: ${response.message} (Code: ${response.code})"
                            }
                        }
                    }
                })
            },
        modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading && url.isNotBlank() && username.isNotBlank() && pass.isNotBlank()
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text("Logging in...")
            } else {
                Text(stringResource(R.string.login_button_text))
            }
        }
        statusMessage?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = it)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LoginScreenPreview() {
    AndromuksTheme {
        LoginScreen(navController = rememberNavController())
    }
}
