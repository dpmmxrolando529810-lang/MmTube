package com.mmtube.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException

private val Context.historyDataStore by preferencesDataStore(name = "mmtube_history")

class HistoryStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val serializer = ListSerializer(HistoryItem.serializer())
    private val itemsKey = stringPreferencesKey("items")

    private val data = context.historyDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }

    val items: Flow<List<HistoryItem>> = data.map { prefs -> decode(prefs[itemsKey]) }

    suspend fun snapshot(): List<HistoryItem> = items.first()

    suspend fun add(item: HistoryItem) {
        update { current ->
            (listOf(item) + current.filterNot { it.id == item.id }).take(200)
        }
    }

    suspend fun remove(id: String) {
        update { current -> current.filterNot { it.id == id } }
    }

    suspend fun clear() {
        update { emptyList() }
    }

    private suspend fun update(transform: (List<HistoryItem>) -> List<HistoryItem>) {
        context.historyDataStore.edit { prefs ->
            prefs[itemsKey] = json.encodeToString(serializer, transform(decode(prefs[itemsKey])))
        }
    }

    private fun decode(raw: String?): List<HistoryItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }
}
