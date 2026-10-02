package com.thelightphone.sdk

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether the phone can reach the internet right now, over anything: Wi-Fi, mobile data,
 * Ethernet. Tools can't ask Android's ConnectivityManager themselves (getSystemService is
 * blocked), so this answers the one question a tool needs before telling the user "no
 * connection": is there a network Android has checked really reaches the internet?
 * Needs ACCESS_NETWORK_STATE in lighttool.toml.
 */
class LightNetwork internal constructor(context: Context) {

    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /** True when the default network reaches the internet. True too if Android can't be asked (never a false "offline"). */
    val isOnline: Boolean
        get() {
            val cm = connectivity ?: return true
            return try {
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                isOnline(
                    hasNetwork = caps != null,
                    hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                    validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                )
            } catch (e: SecurityException) {
                true // ACCESS_NETWORK_STATE not declared: don't claim the phone is offline
            }
        }
}

/** A default network that offers the internet and that Android has checked really reaches it. */
internal fun isOnline(hasNetwork: Boolean, hasInternet: Boolean, validated: Boolean): Boolean =
    hasNetwork && hasInternet && validated
