package io.github.jkfran.frameartmode

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

/** This device's IPv4 addresses on the local network, for discovery. */
object LocalNetwork {
    data class Address(val address: String, val prefixLength: Int)

    /** A network as discovery sees it. */
    data class Candidate(
        /** Wi-Fi or Ethernet: where a TV can be. */
        val local: Boolean,
        val vpn: Boolean,
        val addresses: List<Address>,
    )

    fun ipv4Addresses(context: Context): List<Address> {
        val fromNetworks = try {
            fromLocalNetworks(context)
        } catch (_: RuntimeException) {
            emptyList()
        }
        return fromNetworks.ifEmpty { fromInterfaces() }
    }

    /**
     * The addresses of the Wi-Fi and Ethernet networks, never a VPN's. With a VPN up, the active
     * network is the VPN, and probing its subnet would send requests through the tunnel.
     */
    fun localAddresses(candidates: List<Candidate>): List<Address> =
        candidates.filter { it.local && !it.vpn }.flatMap { it.addresses }.distinct()

    private fun fromLocalNetworks(context: Context): List<Address> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()
        // A one-off read: the active network first, then the others (a VPN hides Wi-Fi or Ethernet).
        @Suppress("DEPRECATION")
        val networks = (listOfNotNull(connectivity.activeNetwork) + connectivity.allNetworks).distinct()
        return localAddresses(
            networks.map { network ->
                val capabilities = connectivity.getNetworkCapabilities(network)
                Candidate(
                    local = capabilities != null &&
                        (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)),
                    vpn = capabilities == null || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                    addresses = ipv4(connectivity.getLinkProperties(network)),
                )
            },
        )
    }

    private fun ipv4(links: LinkProperties?): List<Address> =
        links?.linkAddresses.orEmpty()
            .filter { it.address is Inet4Address && !it.address.isLoopbackAddress }
            .mapNotNull { link -> link.address.hostAddress?.let { Address(it, link.prefixLength) } }

    private fun fromInterfaces(): List<Address> {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        return interfaces
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }.getOrDefault(false) }
            .flatMap { it.interfaceAddresses }
            .filter { it.address is Inet4Address && it.address.isSiteLocalAddress }
            .mapNotNull { item -> item.address.hostAddress?.let { Address(it, item.networkPrefixLength.toInt()) } }
    }
}
