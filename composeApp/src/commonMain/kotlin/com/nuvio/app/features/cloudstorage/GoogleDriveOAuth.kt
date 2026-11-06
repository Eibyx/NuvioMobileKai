package com.nuvio.app.features.cloudstorage

import com.nuvio.app.features.simkl.generateSimklPkceMaterial
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Receives the OAuth redirect on a one-shot loopback listener (Google's flow for installed apps). */
internal expect object CloudOAuthLoopback {
    val supported: Boolean

    /** Listens on 127.0.0.1, calls [onReady] with the redirect URI, returns the redirect's query. */
    suspend fun receive(onReady: (redirectUri: String) -> Unit): Map<String, String>
}

/**
 * Google sign-in with the user's own OAuth client (type "Desktop app"), PKCE and a loopback
 * redirect. Only the read-only Drive scope is asked for.
 */
internal object GoogleDriveOAuth {
    suspend fun signIn(
        http: CloudHttp,
        clientId: String,
        clientSecret: String?,
        openBrowser: (String) -> Unit,
    ): String {
        val pkce = generateSimklPkceMaterial()
        var redirect = ""
        val query = CloudOAuthLoopback.receive { redirectUri ->
            redirect = redirectUri
            openBrowser(
                cloudUrl(
                    GoogleDriveApi.AUTH_URL,
                    query = listOf(
                        "client_id" to clientId,
                        "redirect_uri" to redirectUri,
                        "response_type" to "code",
                        "scope" to GoogleDriveApi.SCOPE,
                        "code_challenge" to pkce.challenge,
                        "code_challenge_method" to "S256",
                        "state" to pkce.state,
                        "access_type" to "offline",
                        "prompt" to "consent",
                    ),
                ),
            )
        }
        if (query["state"] != pkce.state) throw CloudStorageException(CloudFailure.AUTH)
        val code = query["code"]?.takeIf { it.isNotBlank() } ?: throw CloudStorageException(CloudFailure.AUTH)
        val form = buildMap {
            put("code", code)
            put("client_id", clientId)
            clientSecret?.takeIf { it.isNotBlank() }?.let { put("client_secret", it) }
            put("code_verifier", pkce.verifier)
            put("redirect_uri", redirect)
            put("grant_type", "authorization_code")
        }
        val response = http.send(CloudHttpRequest("POST", GoogleDriveApi.TOKEN_URL, form = form))
        if (!response.isSuccess) throw CloudStorageException(CloudFailure.AUTH)
        return (Json.parseToJsonElement(response.body) as? JsonObject)
            ?.get("refresh_token")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotBlank() }
            ?: throw CloudStorageException(CloudFailure.AUTH)
    }
}
