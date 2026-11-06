package com.nuvio.app.features.cloudstorage

/**
 * Encrypted local storage for cloud accounts (Android Keystore AES-GCM / iOS Keychain, this device
 * only). Separate from the media servers' storage, which is cleared and synced on its own terms:
 * nothing written here is synced, exported or backed up.
 */
internal expect object CloudVault {
    fun read(key: String): String?
    fun write(key: String, value: String?)
    fun clear()
}
