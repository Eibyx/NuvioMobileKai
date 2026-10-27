package com.nuvio.app.core.network

import android.content.Context
import android.content.SharedPreferences

internal actual object DnsPlatform {
    private const val preferencesName = "nuvio_network"
    private const val providerKey = "dns_provider"
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        preferences = prefs
        migrateKaiDnsOverHttps(context, prefs)
        NuvioDns.provider = DnsProvider.fromStorage(load())
    }

    /** Nuvio Kai 1.0.x kept its DNS over HTTPS choice in its own file; it carries over once. */
    private fun migrateKaiDnsOverHttps(context: Context, prefs: SharedPreferences) {
        if (prefs.contains(providerKey)) return
        val old = context.applicationContext.getSharedPreferences(kaiPreferencesName, Context.MODE_PRIVATE)
        val id = old.getString(kaiProviderKey, null) ?: return
        val provider = if (id == "none") DnsProvider.SYSTEM else DnsProvider.fromStorage(id)
        prefs.edit().putString(providerKey, provider.storageId).apply()
        old.edit().clear().apply()
    }

    private const val kaiPreferencesName = "nuvio_dns_over_https_settings"
    private const val kaiProviderKey = "dns_over_https_provider"

    actual val isSupported: Boolean = true

    actual fun load(): String? = preferences?.getString(providerKey, null)

    actual fun save(storageId: String) {
        preferences?.edit()?.putString(providerKey, storageId)?.apply()
    }

    actual fun apply(provider: DnsProvider) {
        NuvioDns.provider = provider
    }
}

internal actual fun platformHttpEngine(): io.ktor.client.engine.HttpClientEngine? =
    io.ktor.client.engine.okhttp.OkHttp.create { preconfigured = SharedOkHttpClient.base }
