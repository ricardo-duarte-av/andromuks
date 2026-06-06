package net.vrkknn.andromuks

import android.Manifest
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.utils.buildStaticMapUrl
import java.util.Locale
import kotlin.coroutines.resume

private enum class LocationPickerPhase { PICKING, PREVIEW }

private data class SearchResult(val displayName: String, val latLng: LatLng)

private fun List<Address>.toSearchResults(): List<SearchResult> = mapNotNull { addr ->
    if (!addr.hasLatitude() || !addr.hasLongitude()) return@mapNotNull null
    val line0 = addr.getAddressLine(0) ?: return@mapNotNull null
    val featureName = addr.featureName?.takeIf { it.isNotBlank() && !line0.startsWith(it) }
    SearchResult(
        displayName = if (featureName != null) "$featureName – $line0" else line0,
        latLng = LatLng(addr.latitude, addr.longitude)
    )
}

private suspend fun geocodeQuery(
    query: String,
    geocoder: Geocoder,
    biasCenter: LatLng
): List<SearchResult> = withContext(Dispatchers.IO) {
    try {
        // Try biased search first (±1.5° ≈ 150 km around current map center).
        // The bounds-biased overload has no async variant, so we call it on IO regardless
        // of API level — the deprecation only forbids calling it on the main thread.
        val delta = 1.5
        @Suppress("DEPRECATION")
        val biased = geocoder.getFromLocationName(
            query, 8,
            biasCenter.latitude - delta, biasCenter.longitude - delta,
            biasCenter.latitude + delta, biasCenter.longitude + delta
        ) ?: emptyList()

        if (biased.isNotEmpty()) return@withContext biased.toSearchResults()

        // Nothing nearby — fall back to unbiased global search
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocationName(query, 8) { addresses ->
                    cont.resume(addresses.toSearchResults())
                }
            }
        } else {
            @Suppress("DEPRECATION")
            (geocoder.getFromLocationName(query, 8) ?: emptyList()).toSearchResults()
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Full-screen overlay for picking and captioning a location (MSC3488).
 * Rendered as an in-screen overlay so the room timeline stays alive.
 *
 * Phase 1 — PICKING: Interactive map with address/POI search (via Android Geocoder),
 *   result markers, POI tapping, GPS jump, and a fixed center pin.
 * Phase 2 — PREVIEW: Static map thumbnail + optional caption before sending.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationPickerOverlay(
    onDismiss: () -> Unit,
    onSendLocation: (latitude: Double, longitude: Double, caption: String) -> Unit
) {
    val context = LocalContext.current
    val mapsApiKey = remember { context.getString(R.string.google_maps_api_key) }
    val geocoder = remember { Geocoder(context, Locale.getDefault()) }
    val focusManager = LocalFocusManager.current

    var phase by remember { mutableStateOf(LocationPickerPhase.PICKING) }
    var centerLatLng by remember { mutableStateOf(LatLng(38.7167, -9.1333)) }
    var caption by remember { mutableStateOf("") }
    var isLocating by remember { mutableStateOf(false) }

    // Search state
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchFieldFocused by remember { mutableStateOf(false) }
    val showDropdown = searchFieldFocused && searchResults.isNotEmpty()

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(centerLatLng, 14f)
    }

    // Track map center only when there are no pinned search results
    LaunchedEffect(cameraPositionState.position) {
        if (searchResults.isEmpty()) centerLatLng = cameraPositionState.position.target
    }

    // Debounced Geocoder search
    LaunchedEffect(searchQuery) {
        if (searchQuery.length < 3) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        delay(400)
        if (!Geocoder.isPresent()) return@LaunchedEffect
        isSearching = true
        val results = geocodeQuery(searchQuery, geocoder, biasCenter = centerLatLng)
        searchResults = results
        isSearching = false
    }

    // Fit camera to show all search result markers
    LaunchedEffect(searchResults) {
        if (searchResults.isEmpty()) return@LaunchedEffect
        if (searchResults.size == 1) {
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(searchResults[0].latLng, 15f)
            )
            centerLatLng = searchResults[0].latLng
        } else {
            val builder = LatLngBounds.Builder()
            searchResults.forEach { builder.include(it.latLng) }
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngBounds(builder.build(), 120)
            )
        }
    }

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    fun goToCurrentLocation() {
        isLocating = true
        val cts = CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { location: Location? ->
                isLocating = false
                if (location != null) {
                    val ll = LatLng(location.latitude, location.longitude)
                    centerLatLng = ll
                    cameraPositionState.move(CameraUpdateFactory.newLatLngZoom(ll, 16f))
                } else {
                    Toast.makeText(context, "Could not get current location", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener {
                isLocating = false
                Toast.makeText(context, "Location unavailable", Toast.LENGTH_SHORT).show()
            }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) goToCurrentLocation()
        else Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
    }

    fun requestLocationAndGo() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        if (granted) goToCurrentLocation()
        else locationPermissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    LaunchedEffect(Unit) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) goToCurrentLocation()
    }

    BackHandler {
        when {
            showDropdown -> { focusManager.clearFocus(); searchResults = emptyList() }
            phase == LocationPickerPhase.PREVIEW -> phase = LocationPickerPhase.PICKING
            else -> onDismiss()
        }
    }

    val hasLocationPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    fun selectResult(result: SearchResult) {
        centerLatLng = result.latLng
        cameraPositionState.move(CameraUpdateFactory.newLatLngZoom(result.latLng, 15f))
        searchQuery = result.displayName
        focusManager.clearFocus()
        searchResults = emptyList()
    }

    when (phase) {
        LocationPickerPhase.PICKING -> {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Share Location") },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    )
                },
                floatingActionButton = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        SmallFloatingActionButton(
                            onClick = { requestLocationAndGo() },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            if (isLocating) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.MyLocation, contentDescription = "My location")
                            }
                        }
                        ExtendedFloatingActionButton(
                            onClick = {
                                focusManager.clearFocus()
                                phase = LocationPickerPhase.PREVIEW
                            },
                            icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                            text = { Text("Use this location") }
                        )
                    }
                }
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    // POI click via MapEffect (onPoiClick removed from GoogleMap params in maps-compose 4+)
                    var pendingPoi by remember {
                        mutableStateOf<com.google.android.gms.maps.model.PointOfInterest?>(null)
                    }
                    LaunchedEffect(pendingPoi) {
                        val poi = pendingPoi ?: return@LaunchedEffect
                        centerLatLng = poi.latLng
                        cameraPositionState.move(CameraUpdateFactory.newLatLngZoom(poi.latLng, 16f))
                        searchQuery = poi.name
                        focusManager.clearFocus()
                        searchResults = emptyList()
                        pendingPoi = null
                    }

                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = cameraPositionState,
                        uiSettings = MapUiSettings(
                            zoomControlsEnabled = true,
                            myLocationButtonEnabled = false,
                            compassEnabled = true
                        ),
                        properties = MapProperties(isMyLocationEnabled = hasLocationPermission),
                        onMapClick = {
                            focusManager.clearFocus()
                            searchResults = emptyList()
                            searchQuery = ""
                        }
                    ) {
                        MapEffect { map ->
                            map.setOnPoiClickListener { poi -> pendingPoi = poi }
                        }

                        // Search result markers
                        searchResults.forEach { result ->
                            Marker(
                                state = MarkerState(position = result.latLng),
                                title = result.displayName.substringBefore(" – "),
                                snippet = result.displayName.substringAfter(" – ", ""),
                                onClick = { selectResult(result); true }
                            )
                        }
                    }

                    // Center pin — shows the currently selected send location
                    // Hidden while multiple results are shown (user hasn't chosen one yet)
                    if (searchResults.size <= 1) {
                        Icon(
                            imageVector = Icons.Filled.LocationOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(40.dp)
                                .offset(y = (-20).dp) // pin tip at center
                        )
                    }

                    // Floating search card + results dropdown
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .zIndex(1f)
                    ) {
                        Card(
                            shape = RoundedCornerShape(28.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (isSearching) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.Filled.Search,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                TextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = { Text("Search address or place…") },
                                    modifier = Modifier
                                        .weight(1f)
                                        .onFocusChanged { searchFieldFocused = it.isFocused },
                                    singleLine = true,
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                        unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                                    ),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(
                                        onSearch = { focusManager.clearFocus() }
                                    )
                                )
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            searchQuery = ""
                                            searchResults = emptyList()
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Clear,
                                            contentDescription = "Clear",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Dropdown list of results
                        if (showDropdown) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                shape = RoundedCornerShape(16.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                            ) {
                                LazyColumn {
                                    itemsIndexed(searchResults) { index, result ->
                                        ListItem(
                                            headlineContent = {
                                                val parts = result.displayName.split(" – ", limit = 2)
                                                if (parts.size == 2) {
                                                    Column {
                                                        Text(parts[0], style = MaterialTheme.typography.bodyMedium)
                                                        Text(
                                                            parts[1],
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                } else {
                                                    Text(result.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                                }
                                            },
                                            leadingContent = {
                                                Icon(
                                                    Icons.Filled.LocationOn,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            },
                                            modifier = Modifier.clickable { selectResult(result) }
                                        )
                                        if (index < searchResults.lastIndex) {
                                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Coordinate readout — only when not showing the dropdown
                    if (!showDropdown && searchResults.size <= 1) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 88.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = MaterialTheme.shapes.medium,
                            tonalElevation = 4.dp
                        ) {
                            Text(
                                text = "%.6f, %.6f".format(centerLatLng.latitude, centerLatLng.longitude),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        LocationPickerPhase.PREVIEW -> {
            val staticMapUrl = remember(centerLatLng, mapsApiKey) {
                buildStaticMapUrl(centerLatLng.latitude, centerLatLng.longitude, mapsApiKey)
            }
            val imageRequest = remember(staticMapUrl) {
                ImageRequest.Builder(context)
                    .data(staticMapUrl)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()
            }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Send Location") },
                        navigationIcon = {
                            IconButton(onClick = { phase = LocationPickerPhase.PICKING }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = {
                                    onSendLocation(
                                        centerLatLng.latitude,
                                        centerLatLng.longitude,
                                        caption.trim()
                                    )
                                }
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                            }
                        }
                    )
                }
            ) { paddingValues ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = "Location preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )

                    Text(
                        text = if (searchQuery.isNotBlank()) searchQuery
                        else "%.6f, %.6f".format(centerLatLng.latitude, centerLatLng.longitude),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = caption,
                        onValueChange = { caption = it },
                        label = { Text("Caption (optional)") },
                        placeholder = { Text("Add a description…") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                onSendLocation(
                                    centerLatLng.latitude,
                                    centerLatLng.longitude,
                                    caption.trim()
                                )
                            }
                        )
                    )
                }
            }
        }
    }
}
