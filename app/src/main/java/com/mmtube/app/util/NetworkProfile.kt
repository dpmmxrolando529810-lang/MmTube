package com.mmtube.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Learns which speed profile worked best on each kind of connection. */
object NetworkProfile {

    private const val PREFS = "mmtube_network_profile"

    fun current(context: Context): String = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        val caps = network?.let { manager.getNetworkCapabilities(it) }
        when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
            else -> "other"
        }
    }.getOrDefault("other")

    fun learned(context: Context, network: String): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(network, null)

    fun remember(context: Context, network: String, profile: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(network, profile).apply()
    }
}
