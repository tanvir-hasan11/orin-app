package com.orin.app.security

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL

/**
 * Blocks non-HTTPS, loopback, link-local, private and metadata endpoints before
 * a request is made. Redirect targets are re-validated by the caller.
 */
object UrlSafety {
    private val blockedHosts = setOf(
        "localhost",
        "localhost.localdomain",
        "ip6-localhost",
        "metadata.google.internal",
    )

    fun validateSyntax(raw: String): URL {
        val url = URL(raw.trim())
        require(url.protocol.equals("https", ignoreCase = true)) {
            "Only HTTPS URLs are allowed."
        }
        val host = url.host.lowercase().trimEnd('.')
        require(host.isNotBlank()) { "URL has no host." }
        require(host !in blockedHosts) { "Host is not allowed." }
        require(!host.endsWith(".local")) { "Local hostnames are not allowed." }
        return url
    }

    fun validate(raw: String): URL {
        val url = validateSyntax(raw)
        val addresses = InetAddress.getAllByName(url.host)
        require(addresses.isNotEmpty()) { "Host did not resolve." }
        require(addresses.all(::isPublic)) { "Private or local addresses are not allowed." }
        return url
    }

    private fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return false
        }
        if (address is Inet6Address) {
            val first = address.address[0].toInt() and 0xff
            // fc00::/7 unique-local
            if ((first and 0xfe) == 0xfc) return false
        }
        return true
    }
}
