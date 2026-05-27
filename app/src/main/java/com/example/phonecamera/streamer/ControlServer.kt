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

    fun start(scope: CoroutineScope, onCommand: (Command) -> String?) {
        AppLog.d("start()")
        if (serverJob?.isActive == true) return
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                val ss = ServerSocket(port).also { serverSocket = it }
                AppLog.d("ControlServer started on port $port")
                while (isActive) {
                    try {
                        val client = ss.accept()
                        launch(Dispatchers.IO) {
                            try {
                                client.soTimeout = 3000
                                val clientIp = client.inetAddress.hostAddress ?: "?"
                                val reader = java.io.BufferedReader(java.io.InputStreamReader(client.inputStream))
                                val line = reader.readLine()?.trim()
                                if (line != null) {
                                    AppLog.net("<-- RECV COMMAND from [$clientIp]: '$line'")
                                    val cmd = parseCommand(line, clientIp)
                                    val response = if (cmd != null) {
                                        val err = onCommand(cmd)
                                        if (err == null) "OK" else "ERROR $err"
                                    } else {
                                        "ERROR unknown command"
                                    }
                                    AppLog.net("--> SEND RESPONSE to [$clientIp]: '$response'")
                                    client.outputStream.write("$response\n".toByteArray())
                                    client.outputStream.flush()
                                }
                            } catch (e: Exception) {
                                AppLog.w("ControlServer handling client error: ${e.message}")
                            } finally {
                                try { client.close() } catch (_: Exception) {}
                            }
                        }
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
        AppLog.d("stop()")
        serverJob?.cancel()
        serverJob = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        AppLog.d("ControlServer stopped")
    }

    private fun parseCommand(line: String, fromIp: String): Command? {
        AppLog.d("parseCommand(line=$line, fromIp=$fromIp)")
        val parts = line.split(" ")
        if (parts.isEmpty()) return null
        return when (parts[0].uppercase()) {
            "HELLO" -> {
                val deviceName = parts.getOrNull(1) ?: fromIp
                val pin = parts.getOrNull(2) ?: ""
                Command.Hello(deviceName = deviceName, pin = pin, ip = fromIp)
            }
            "BYE" -> {
                val deviceName = parts.getOrNull(1) ?: fromIp
                val pin = parts.getOrNull(2) ?: ""
                Command.Bye(deviceName = deviceName, pin = pin, ip = fromIp)
            }
            "SET_QUALITY" -> {
                val h = parts.getOrNull(1)?.toIntOrNull()
                val pin = parts.getOrNull(2) ?: ""
                if (h != null) Command.SetQuality(heightP = h, pin = pin, fromIp = fromIp) else null
            }
            "SET_FPS" -> {
                val fps = parts.getOrNull(1)?.toIntOrNull()
                val pin = parts.getOrNull(2) ?: ""
                if (fps != null) Command.SetFps(fps = fps, pin = pin, fromIp = fromIp) else null
            }
            else -> null
        }
    }

    sealed class Command {
        abstract val pin: String
        data class Hello(val deviceName: String, override val pin: String, val ip: String) : Command()
        data class Bye(val deviceName: String, override val pin: String, val ip: String) : Command()
        data class SetQuality(val heightP: Int, override val pin: String, val fromIp: String) : Command()
        data class SetFps(val fps: Int, override val pin: String, val fromIp: String) : Command()
    }
}
