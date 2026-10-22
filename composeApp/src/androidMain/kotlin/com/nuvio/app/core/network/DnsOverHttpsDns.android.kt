package com.nuvio.app.core.network

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress

/**
 * Resolves through the DNS over HTTPS provider chosen in settings, or the system resolver when
 * none is. It reads the setting on every lookup, so a change applies to new connections at once.
 */
object DnsOverHttpsDns : Dns {
    private val lock = Any()
    private var resolvedProvider: DnsOverHttpsProvider? = null
    private var resolver: Dns = Dns.SYSTEM

    override fun lookup(hostname: String): List<InetAddress> = currentResolver().lookup(hostname)

    private fun currentResolver(): Dns {
        val provider = DnsOverHttpsSettingsRepository.snapshot().provider
        synchronized(lock) {
            if (provider != resolvedProvider) {
                resolver = provider.toOkHttpDns()
                resolvedProvider = provider
            }
            return resolver
        }
    }
}

// The resolver's own requests go to the provider's bootstrap addresses, never through itself.
private val bootstrapClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        .build()
}

fun DnsOverHttpsProvider.toOkHttpDns(): Dns {
    val url = endpointUrl ?: return Dns.SYSTEM
    return DnsOverHttps.Builder()
        .client(bootstrapClient)
        .url(url.toHttpUrl())
        .bootstrapDnsHosts(bootstrapHosts())
        .build()
}

private fun DnsOverHttpsProvider.bootstrapHosts(): List<InetAddress> =
    when (this) {
        DnsOverHttpsProvider.Google -> listOf("8.8.8.8", "8.8.4.4")
        DnsOverHttpsProvider.Cloudflare -> listOf("1.1.1.1", "1.0.0.1")
        DnsOverHttpsProvider.AdGuard -> listOf("94.140.14.14", "94.140.15.15")
        DnsOverHttpsProvider.DnsWatch -> listOf("84.200.69.80", "84.200.70.40")
        DnsOverHttpsProvider.Quad9 -> listOf("9.9.9.9", "149.112.112.112")
        DnsOverHttpsProvider.DnsSb -> listOf("185.222.222.222", "45.11.45.11")
        DnsOverHttpsProvider.CanadianShield -> listOf("149.112.121.10", "149.112.122.10")
        DnsOverHttpsProvider.None -> emptyList()
    }.map { address ->
        InetAddress.getByName(address)
    }
