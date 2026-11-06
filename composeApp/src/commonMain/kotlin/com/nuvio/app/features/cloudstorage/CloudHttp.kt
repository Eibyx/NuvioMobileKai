package com.nuvio.app.features.cloudstorage

import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.core.network.readBoundedResponseBody
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.contentLength

internal class CloudHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val form: Map<String, String>? = null,
) {
    /** Header values and the query string can hold cookies or tokens: never print them. */
    override fun toString(): String = "CloudHttpRequest($method ${url.substringBefore('?')}, headers=${headers.keys})"
}

internal class CloudHttpResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: String,
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    val isSuccess: Boolean get() = status in 200..299
}

/** The HTTP the cloud providers use, so tests can answer requests without a network. */
internal fun interface CloudHttp {
    suspend fun send(request: CloudHttpRequest): CloudHttpResponse
}

internal object KtorCloudHttp : CloudHttp {
    private val client: HttpClient by lazy { createApiHttpClient() }

    override suspend fun send(request: CloudHttpRequest): CloudHttpResponse {
        val response = client.request(request.url) {
            method = HttpMethod.parse(request.method)
            request.headers.forEach { (name, value) -> header(name, value) }
            request.form?.let { form ->
                setBody(FormDataContent(Parameters.build { form.forEach { (key, value) -> append(key, value) } }))
            }
        }
        val body = readBoundedResponseBody(response.bodyAsChannel(), response.contentLength())
        return CloudHttpResponse(
            status = response.status.value,
            headers = response.headers.entries().associate { it.key to it.value },
            body = body,
        )
    }
}

internal fun cloudUrl(base: String, path: String = "", query: List<Pair<String, String>> = emptyList()): String =
    URLBuilder(base.trimEnd('/') + path).apply {
        query.forEach { (key, value) -> parameters.append(key, value) }
    }.buildString()
