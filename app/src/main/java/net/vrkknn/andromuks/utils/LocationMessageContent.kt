package net.vrkknn.andromuks.utils

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest

/**
 * Parses a geo URI (geo:lat,lon or geo:lat,lon;u=accuracy or geo:lat,lon,alt) into
 * a (latitude, longitude) pair, or returns null if the URI is not valid.
 */
fun parseGeoUri(geoUri: String): Pair<Double, Double>? {
    if (!geoUri.startsWith("geo:")) return null
    val coords = geoUri.removePrefix("geo:").split(";").first().split(",")
    return try {
        val lat = coords[0].toDouble()
        val lon = coords[1].toDouble()
        Pair(lat, lon)
    } catch (_: Exception) {
        null
    }
}

/**
 * Builds a Google Maps Static API thumbnail URL for a lat/lon pin.
 * Requires the Maps Static API to be enabled in GCP console for the provided key.
 */
fun buildStaticMapUrl(latitude: Double, longitude: Double, apiKey: String): String {
    val marker = "color:red|$latitude,$longitude"
    return "https://maps.googleapis.com/maps/api/staticmap" +
            "?center=$latitude,$longitude" +
            "&zoom=14" +
            "&size=400x200" +
            "&markers=${Uri.encode(marker)}" +
            "&key=$apiKey"
}

/**
 * Renders a location message bubble (m.location / MSC3488).
 *
 * Shows a Google Maps Static API thumbnail (loaded by Coil) with the coordinates
 * and a tap target that opens the location in the system maps app.
 *
 * @param contentColor The text/icon color — pass the bubble's content color so it
 *   always contrasts with the bubble background regardless of theme or sender.
 * @param onCaptionClick Tap handler for the caption row below the map thumbnail. When
 *   non-null (e.g. a thread message) it overrides the default "open in Maps" so the
 *   caption can open the thread instead. The map thumbnail always opens Maps.
 */
@Composable
fun LocationMessageContent(
    geoUri: String,
    body: String,
    mapsApiKey: String,
    contentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onCaptionClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coords = remember(geoUri) { parseGeoUri(geoUri) }

    val openInMaps = {
        if (coords != null) {
            val mapsUri = Uri.parse("geo:${coords.first},${coords.second}?q=${coords.first},${coords.second}")
            val intent = Intent(Intent.ACTION_VIEW, mapsUri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
                val webUri = Uri.parse("https://maps.google.com/?q=${coords.first},${coords.second}")
                context.startActivity(Intent(Intent.ACTION_VIEW, webUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
    }

    Column(modifier = modifier.width(IntrinsicSize.Min)) {
        if (coords != null) {
            val staticMapUrl = remember(coords, mapsApiKey) {
                buildStaticMapUrl(coords.first, coords.second, mapsApiKey)
            }
            val imageRequest = remember(staticMapUrl) {
                ImageRequest.Builder(context)
                    .data(staticMapUrl)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()
            }
            AsyncImage(
                model = imageRequest,
                contentDescription = "Map showing location",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .clickable { openInMaps() }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { (onCaptionClick ?: openInMaps)() }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.LocationOn,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (body.isNotBlank() && body != "Location") body else "Location",
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor
                )
                if (coords != null) {
                    Text(
                        text = "%.5f, %.5f".format(coords.first, coords.second),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f)
                    )
                }
            }
            Icon(
                imageVector = Icons.Filled.OpenInNew,
                contentDescription = "Open in Maps",
                tint = contentColor.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
