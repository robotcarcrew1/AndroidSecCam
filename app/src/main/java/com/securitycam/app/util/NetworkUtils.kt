package com.securitycam.app.util

import java.net.Inet4Address
import java.net.NetworkInterface

/** Finds this device's current WiFi/LAN IPv4 address, e.g. for building a local event link. */
fun findLanIpAddress(): String? {
    return try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}
