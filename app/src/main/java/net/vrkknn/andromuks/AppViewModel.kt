package net.vrkknn.andromuks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

class AppViewModel : ViewModel() {
    var isLoading by mutableStateOf(false)

    fun showLoading() {
        isLoading = true
    }

    fun hideLoading() {
        isLoading = false
    }
}
