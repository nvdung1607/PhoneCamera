package com.example.phonecamera.network

import com.example.phonecamera.utils.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Client TCP phụ trách gửi các lệnh điều khiển từ máy Viewer sang máy Streamer.
 * Đảm bảo nguyên lý Đơn nhiệm (Single Responsibility Principle - SOLID).
 */
class CameraControlClient {

    /**
     * Gửi một lệnh dạng text qua Socket TCP tới địa chỉ IP của máy Streamer.
     * @param host Địa chỉ IP của máy Streamer
     * @param command Chuỗi lệnh (VD: "HELLO Pixel-7", "SET_QUALITY 720")
     * @param timeoutMs Thời gian chờ kết nối và đọc tối đa
     * @return Phản hồi từ Server ("OK" hoặc "ERROR ..."), hoặc null nếu xảy ra lỗi
     */
    suspend fun sendCommand(
        host: String,
        command: String,
        timeoutMs: Int = 3000
    ): String? = withContext(Dispatchers.IO) {
        AppLog.net("--> SEND COMMAND to $host: '$command'")
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, ControlServer.CONTROL_PORT), timeoutMs)
                socket.soTimeout = timeoutMs

                val writer = java.io.PrintWriter(socket.getOutputStream(), true)
                writer.println(command)

                val reader = java.io.BufferedReader(java.io.InputStreamReader(socket.getInputStream()))
                val response = reader.readLine()?.trim()
                AppLog.net("<-- RECV RESPONSE from $host: '$response'")
                response
            }
        } catch (e: Exception) {
            AppLog.e("Failed to send command '$command' to $host: ${e.message}")
            null
        }
    }

    suspend fun sayHello(host: String, deviceName: String, pinCode: String): String? {
        AppLog.d("sayHello(host=$host, deviceName=$deviceName, pinCode=$pinCode)")
        return sendCommand(host, "HELLO $deviceName $pinCode")
    }

    suspend fun sayBye(host: String, deviceName: String, pinCode: String): String? {
        AppLog.d("sayBye(host=$host, deviceName=$deviceName, pinCode=$pinCode)")
        return sendCommand(host, "BYE $deviceName $pinCode")
    }

    suspend fun setQuality(host: String, heightP: Int, pinCode: String): String? {
        AppLog.d("setQuality(host=$host, heightP=$heightP, pinCode=$pinCode)")
        return sendCommand(host, "SET_QUALITY $heightP $pinCode")
    }

    suspend fun setFps(host: String, fps: Int, pinCode: String): String? {
        AppLog.d("setFps(host=$host, fps=$fps, pinCode=$pinCode)")
        return sendCommand(host, "SET_FPS $fps $pinCode")
    }

    suspend fun setBitrate(host: String, bitrateBps: Int, pinCode: String): String? {
        AppLog.d("setBitrate(host=$host, bitrateBps=$bitrateBps, pinCode=$pinCode)")
        return sendCommand(host, "SET_BITRATE $bitrateBps $pinCode")
    }
}
