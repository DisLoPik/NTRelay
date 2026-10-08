package com.dislopik.ntrealy.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Android 17+ silently drops LAN traffic for apps targeting SDK 37 until the user
 * grants ACCESS_LOCAL_NETWORK. Older releases allow it with just INTERNET.
 */
fun Context.hasLocalNetworkAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) ==
        PackageManager.PERMISSION_GRANTED

/**
 * The Wi-Fi or Ethernet network, or null to leave routing to the OS. Not
 * activeNetwork: that is the VPN when one is up, or cellular on Wi-Fi without internet.
 */
@Suppress("DEPRECATION")
fun ConnectivityManager.localAreaNetwork(): Network? =
    allNetworks.firstOrNull { network ->
        val caps = getNetworkCapabilities(network) ?: return@firstOrNull false
        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }
