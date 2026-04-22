package com.example.phonecamera.data.nsd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

/**
 * NsdHelper wraps Android's NsdManager for mDNS service registration and discovery.
 *
 * Service type: "_rtspguard._tcp"
 * TXT record equivalent: the service name encodes the device model.
 *
 * Usage:
 *   Camera phone → NsdHelper.registerService(port)
 *   Viewer phone → NsdHelper.discoverServices(onFound, onLost)
 */
class NsdHelper(private val context: Context) {

    companion object {
        private const val TAG = "RTSPGuard.NSD"
        const val SERVICE_TYPE = "_rtspguard._tcp."
        // Sanitize model name for NSD service name (letters, digits, hyphens only)
        fun deviceServiceName(): String {
            val raw = Build.MODEL.replace(Regex("[^A-Za-z0-9\\-]"), "-").trim('-')
            return if (raw.length > 60) raw.take(60) else raw.ifEmpty { "RTSPGuard" }
        }
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    // ─── Registration (Camera phone) ───────────────────────────────────────

    private var registrationListener: NsdManager.RegistrationListener? = null

    fun registerService(port: Int) {
        if (registrationListener != null) return // Already registered

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = deviceServiceName()
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "Registration failed: $code")
                registrationListener = null
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "Unregistration failed: $code")
            }
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "Registered: ${info.serviceName}")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d(TAG, "Unregistered: ${info.serviceName}")
                registrationListener = null
            }
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    fun unregisterService() {
        registrationListener?.let {
            try { nsdManager.unregisterService(it) } catch (e: Exception) { Log.w(TAG, e) }
            registrationListener = null
        }
    }

    // ─── Discovery (Viewer phone) ───────────────────────────────────────────

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /**
     * Start discovering Phone Camera devices on the LAN.
     * [onFound] is called on background thread — post to main thread if updating UI.
     * [onLost] is called when a camera goes offline.
     */
    fun discoverServices(
        onFound: (DiscoveredCamera) -> Unit,
        onLost: (serviceId: String) -> Unit
    ) {
        if (discoveryListener != null) return // Already running

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, code: Int) {
                Log.e(TAG, "Discovery failed to start: $code")
                discoveryListener = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, code: Int) {
                Log.e(TAG, "Discovery failed to stop: $code")
            }
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started")
            }
            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped")
                discoveryListener = null
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                resolveService(serviceInfo, onFound)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                onLost(serviceInfo.serviceName)
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (e: Exception) { Log.w(TAG, e) }
            discoveryListener = null
        }
    }

    /** Stop both registration and discovery. */
    fun stopAll() {
        unregisterService()
        stopDiscovery()
    }

    // ─── Internal ──────────────────────────────────────────────────────────

    private fun resolveService(
        serviceInfo: NsdServiceInfo,
        onResolved: (DiscoveredCamera) -> Unit
    ) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "Resolve failed for ${info.serviceName}: $code")
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress ?: return
                val camera = DiscoveredCamera(
                    serviceId = info.serviceName,
                    displayName = info.serviceName.replace('-', ' '),
                    host = host,
                    port = info.port
                )
                Log.d(TAG, "Resolved: $camera")
                onResolved(camera)
            }
        })
    }
}
