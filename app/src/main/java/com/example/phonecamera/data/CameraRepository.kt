package com.example.phonecamera.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
    val username: String = "",
    val password: String = ""
) {
    /** Returns a fully formed RTSP URL from this config */
    fun toRtspUrl(): String {
        return if (username.isNotBlank()) {
            "rtsp://$username:$password@$host:$port"
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
        try {
            json.decodeFromString<List<CameraConfig>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveCamera(config: CameraConfig) {
        context.dataStore.edit { prefs ->
            val current = try {
                val raw = prefs[CAMERAS_KEY] ?: "[]"
                json.decodeFromString<MutableList<CameraConfig>>(raw)
            } catch (e: Exception) {
                mutableListOf()
            }
            val index = current.indexOfFirst { it.id == config.id }
            if (index >= 0) {
                current[index] = config
            } else {
                current.add(config)
            }
            prefs[CAMERAS_KEY] = json.encodeToString<List<CameraConfig>>(current)
        }
    }

    suspend fun deleteCamera(id: Int) {
        context.dataStore.edit { prefs ->
            val current = try {
                val raw = prefs[CAMERAS_KEY] ?: "[]"
                json.decodeFromString<MutableList<CameraConfig>>(raw)
            } catch (e: Exception) {
                mutableListOf()
            }
            current.removeAll { it.id == id }
            prefs[CAMERAS_KEY] = json.encodeToString<List<CameraConfig>>(current)
        }
    }
}
