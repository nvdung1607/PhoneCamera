package com.example.phonecamera.data.nsd

/**
 * Represents a camera discovered via mDNS/NSD on the local network.
 */
data class DiscoveredCamera(
    val serviceId: String,   // Unique NSD service name (used for dedup)
    val displayName: String, // Build.MODEL of the camera phone
    val host: String,        // Resolved IP address
    val port: Int            // RTSP port (typically 8080)
) {
    val rtspUrl: String get() = "rtsp://$host:$port"
}
