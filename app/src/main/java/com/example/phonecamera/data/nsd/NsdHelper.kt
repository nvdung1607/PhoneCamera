package com.example.phonecamera.data.nsd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.example.phonecamera.utils.AppLog

/**
 * Wrapper quanh Android NsdManager để đăng ký và tìm kiếm dịch vụ mDNS.
 *
 * - Camera phone → gọi [registerService] khi bắt đầu phát
 * - Viewer phone → gọi [discoverServices] để tìm camera trong mạng LAN
 */
class NsdHelper(private val context: Context) {

    companion object {
        const val SERVICE_TYPE = "_rtspguard._tcp."

        fun deviceServiceName(): String {
            val raw = Build.MODEL.replace(Regex("[^A-Za-z0-9\\-]"), "-").trim('-')
            return raw.take(60).ifEmpty { "RTSPGuard" }
        }
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    // ─── Đăng ký dịch vụ (Camera phone) ────────────────────────────────────

    private var registrationListener: NsdManager.RegistrationListener? = null

    fun registerService(port: Int) {
        if (registrationListener != null) return

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = deviceServiceName()
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                AppLog.e("NSD registration failed: $code")
                registrationListener = null
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                AppLog.e("NSD unregistration failed: $code")
            }
            override fun onServiceRegistered(info: NsdServiceInfo) {
                AppLog.d("NSD registered: ${info.serviceName}")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                AppLog.d("NSD unregistered: ${info.serviceName}")
                registrationListener = null
            }
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    fun unregisterService() {
        registrationListener?.let {
            try { nsdManager.unregisterService(it) } catch (e: Exception) { AppLog.w("NSD unregister error: ${e.message}") }
            registrationListener = null
        }
    }

    // ─── Tìm kiếm dịch vụ (Viewer phone) ───────────────────────────────────

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /**
     * Bắt đầu tìm kiếm camera trong mạng LAN.
     * Callback [onFound] và [onLost] được gọi trên background thread.
     */
    fun discoverServices(
        onFound: (DiscoveredCamera) -> Unit,
        onLost: (serviceId: String) -> Unit
    ) {
        if (discoveryListener != null) return

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, code: Int) {
                AppLog.e("NSD discovery failed to start: $code")
                discoveryListener = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, code: Int) {
                AppLog.e("NSD discovery failed to stop: $code")
            }
            override fun onDiscoveryStarted(serviceType: String) {
                AppLog.d("NSD discovery started")
            }
            override fun onDiscoveryStopped(serviceType: String) {
                AppLog.d("NSD discovery stopped")
                discoveryListener = null
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                AppLog.d("NSD service found: ${serviceInfo.serviceName}")
                resolveService(serviceInfo, onFound)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                AppLog.d("NSD service lost: ${serviceInfo.serviceName}")
                onLost(serviceInfo.serviceName)
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (e: Exception) { AppLog.w("NSD stop error: ${e.message}") }
            discoveryListener = null
        }
    }

    fun stopAll() {
        unregisterService()
        stopDiscovery()
    }

    // ─── Internal ───────────────────────────────────────────────────────────

    private fun resolveService(serviceInfo: NsdServiceInfo, onResolved: (DiscoveredCamera) -> Unit) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                AppLog.e("NSD resolve failed for ${info.serviceName}: $code")
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress ?: return
                val camera = DiscoveredCamera(
                    serviceId = info.serviceName,
                    displayName = info.serviceName.replace('-', ' '),
                    host = host,
                    port = info.port
                )
                AppLog.d("NSD resolved: $camera")
                onResolved(camera)
            }
        })
    }
}
