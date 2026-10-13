package com.shilapi.xcertplay.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * The address the iPhone is told to connect to: a routable IPv4 address when the interface has one,
 * otherwise its link-local IPv6 address with the interface scope attached.
 */
internal fun wirelessHostAddress(addresses: List<InetAddress>, interfaceIndex: Int): InetAddress? {
    val selected = addresses.firstOrNull { it is Inet4Address && usableIpv4(it) }
        ?: addresses.firstOrNull { it is Inet6Address && it.isLinkLocalAddress }
        ?: return null
    if (selected is Inet6Address && interfaceIndex > 0) {
        return Inet6Address.getByAddress(null, selected.address, interfaceIndex)
    }
    return selected
}

/** Station LAN discovery must cover IPv4 multicast as well as scoped link-local IPv6. */
internal fun existingWifiHostAddresses(addresses: List<InetAddress>, interfaceIndex: Int): List<InetAddress> {
    val ipv4 = addresses.firstOrNull { it is Inet4Address && usableIpv4(it) }
    val ipv6 = if (interfaceIndex > 0) {
        addresses.filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress }?.let {
            Inet6Address.getByAddress(null, it.address, interfaceIndex)
        }
    } else {
        null
    }
    return listOfNotNull(ipv4, ipv6)
}

private fun usableIpv4(address: InetAddress): Boolean =
    !address.isLoopbackAddress && !address.isLinkLocalAddress &&
        !address.isAnyLocalAddress && !address.isMulticastAddress
