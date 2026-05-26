package com.example.phonecamera.streamer

import com.example.phonecamera.utils.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.net.SocketException

/**
 * Server TCP đơn giản để nhận lệnh điều khiển từ máy Viewer.
 *
 * Protocol (text-based, mỗi lệnh một dòng):
 *   HELLO <tên thiết bị>         → viewer kết nối, đăng ký tên
 *   SET_QUALITY <360|720|1080>   → yêu cầu đổi độ phân giải
 *   BYE <tên thiết bị>           → viewer ngắt kết nối
 *
 * Response: "OK" hoặc "ERROR <lý do>"
 */
class ControlServer(val port: Int = CONTROL_PORT) {

    companion object {
        const val CONTROL_PORT = 8081
    }

    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null

    fun start(scope: CoroutineScope, onCommand: (Command) -> Unit) {
        if (serverJob?.isActive == true) return
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                val ss = ServerSocket(port).also { serverSocket = it }
                AppLog.d("ControlServer started on port $port")
                while (isActive) {
                    try {
                        val client = ss.accept()
                        val clientIp = client.inetAddress.hostAddress ?: "?"
                        val line = java.io.BufferedReader(java.io.InputStreamReader(client.inputStream)).readLine()?.trim() ?: continue
                        AppLog.d("ControlServer ← [$clientIp] $line")

                        val cmd = parseCommand(line, clientIp)
                        val response = if (cmd != null) { onCommand(cmd); "OK" } else "ERROR unknown command"
                        client.outputStream.write("$response\n".toByteArray())
                        client.close()
                    } catch (e: SocketException) {
                        if (isActive) AppLog.w("ControlServer client error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (serverJob?.isActive == true) AppLog.e("ControlServer error: ${e.message}")
            }
        }
    }

    fun stop() {
        serverJob?.cancel()
        serverJob = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        AppLog.d("ControlServer stopped")
    }

    private fun parseCommand(line: String, fromIp: String): Command? {
        val parts = line.split(" ", limit = 2)
        return when (parts[0].uppercase()) {
            "HELLO"       -> Command.Hello(deviceName = parts.getOrElse(1) { fromIp }, ip = fromIp)
            "BYE"         -> Command.Bye(deviceName = parts.getOrElse(1) { fromIp }, ip = fromIp)
            "SET_QUALITY" -> {
                val h = parts.getOrElse(1) { "" }.trim().toIntOrNull()
                if (h != null) Command.SetQuality(heightP = h, fromIp = fromIp) else null
            }
            else -> null
        }
    }

    sealed class Command {
        data class Hello(val deviceName: String, val ip: String) : Command()
        data class Bye(val deviceName: String, val ip: String) : Command()
        data class SetQuality(val heightP: Int, val fromIp: String) : Command()
    }
}
