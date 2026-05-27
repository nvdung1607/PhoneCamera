package com.example.phonecamera.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "rtsp_guard_prefs")

@Serializable
data class CameraConfig(
    val id: Int,
    val name: String,
    val host: String,
    val port: Int = 8080,
    /** true nếu camera này là điện thoại chạy ứng dụng Phone Camera (thêm qua NSD) */
    val isPhoneCamera: Boolean = false,
    val pinCode: String = ""
) {
    fun toRtspUrl(): String {
        return if (pinCode.isNotEmpty()) {
            "rtsp://admin:$pinCode@$host:$port"
        } else {
            "rtsp://$host:$port"
        }
    }
}

class CameraRepository(private val context: Context) {

    companion object {
        private val CAMERAS_KEY = stringPreferencesKey("cameras_json")
        const val MAX_CAMERAS = 4
        private val json = Json { ignoreUnknownKeys = true }
    }

    val camerasFlow: Flow<List<CameraConfig>> = context.dataStore.data.map { prefs ->
        val raw = prefs[CAMERAS_KEY] ?: return@map emptyList()
        try { json.decodeFromString<List<CameraConfig>>(raw) } catch (e: Exception) { emptyList() }
    }

    suspend fun saveCamera(config: CameraConfig) {
        context.dataStore.edit { prefs ->
            val current = try {
                json.decodeFromString<MutableList<CameraConfig>>(prefs[CAMERAS_KEY] ?: "[]")
            } catch (e: Exception) { mutableListOf() }

            val index = current.indexOfFirst { it.id == config.id }
            if (index >= 0) current[index] = config else current.add(config)
            prefs[CAMERAS_KEY] = json.encodeToString<List<CameraConfig>>(current)
        }
    }

    suspend fun deleteCamera(id: Int) {
        context.dataStore.edit { prefs ->
            val current = try {
                json.decodeFromString<MutableList<CameraConfig>>(prefs[CAMERAS_KEY] ?: "[]")
            } catch (e: Exception) { mutableListOf() }

            current.removeAll { it.id == id }
            prefs[CAMERAS_KEY] = json.encodeToString<List<CameraConfig>>(current)
        }
    }

    // Storing and retrieving streamer PIN persistently
    private val STREAMER_PIN_KEY = stringPreferencesKey("streamer_pin")

    suspend fun saveStreamerPin(pin: String) {
        context.dataStore.edit { prefs ->
            prefs[STREAMER_PIN_KEY] = pin
        }
    }

    suspend fun getSavedStreamerPin(): String? {
        return context.dataStore.data.map { prefs ->
            prefs[STREAMER_PIN_KEY]
        }.firstOrNull()
    }
}
