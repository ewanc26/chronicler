package uk.ewancroft.chronicler.publish

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** Minimal XRPC client: identity resolution, app-password sessions, blobs and records. Blocking. */
class AtprotoClient(
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
    private val publicApi: String = "https://public.api.bsky.app",
    private val plcDirectory: String = "https://plc.directory",
) {
    private val json = Json { ignoreUnknownKeys = true }

    class XrpcException(message: String) : Exception(message)

    data class Session(val pds: String, val did: String, val accessJwt: String)

    data class StrongRef(val uri: String, val cid: String)

    /** Resolves a handle or DID to (did, PDS endpoint) unless [pdsOverride] is given. */
    fun resolve(identifier: String, pdsOverride: String?): Pair<String, String> {
        val did = if (identifier.startsWith("did:")) identifier else resolveHandle(identifier)
        val pds = pdsOverride?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: pdsFor(did)
        return did to pds
    }

    private fun resolveHandle(handle: String): String {
        // The handle's own well-known file first, then the public AppView.
        runCatching {
            val text = get("https://$handle/.well-known/atproto-did").trim()
            if (text.startsWith("did:")) return text
        }
        val res = json.parseToJsonElement(get("$publicApi/xrpc/com.atproto.identity.resolveHandle?handle=${enc(handle)}")).jsonObject
        return res["did"]?.jsonPrimitive?.content ?: throw XrpcException("Could not resolve handle $handle")
    }

    private fun pdsFor(did: String): String {
        val docUrl = when {
            did.startsWith("did:plc:") -> "$plcDirectory/$did"
            did.startsWith("did:web:") -> "https://${did.removePrefix("did:web:")}/.well-known/did.json"
            else -> throw XrpcException("Unsupported DID method: $did")
        }
        val doc = json.parseToJsonElement(get(docUrl)).jsonObject
        val service = doc["service"]?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it["id"]?.jsonPrimitive?.content?.endsWith("#atproto_pds") == true }
        return service?.get("serviceEndpoint")?.jsonPrimitive?.content?.trimEnd('/')
            ?: throw XrpcException("No PDS listed for $did")
    }

    fun login(identifier: String, appPassword: String, pdsOverride: String? = null): Session {
        val (did, pds) = resolve(identifier, pdsOverride)
        val body = buildJsonObject { put("identifier", did); put("password", appPassword) }
        val res = post(pds, "com.atproto.server.createSession", body.toString(), null)
        return Session(pds, res["did"]?.jsonPrimitive?.content ?: did, res["accessJwt"]!!.jsonPrimitive.content)
    }

    fun uploadBlob(session: Session, bytes: ByteArray, mimeType: String): JsonElement {
        val request = HttpRequest.newBuilder(URI.create("${session.pds}/xrpc/com.atproto.repo.uploadBlob"))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer ${session.accessJwt}")
            .header("Content-Type", mimeType)
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build()
        return send(request)["blob"] ?: throw XrpcException("uploadBlob returned no blob")
    }

    fun putRecord(session: Session, collection: String, rkey: String, record: JsonObject): StrongRef {
        val body = buildJsonObject {
            put("repo", session.did); put("collection", collection); put("rkey", rkey); put("record", record)
        }
        val res = post(session.pds, "com.atproto.repo.putRecord", body.toString(), session.accessJwt)
        return StrongRef(res["uri"]!!.jsonPrimitive.content, res["cid"]!!.jsonPrimitive.content)
    }

    private fun post(pds: String, method: String, body: String, token: String?): JsonObject {
        val builder = HttpRequest.newBuilder(URI.create("$pds/xrpc/$method"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        token?.let { builder.header("Authorization", "Bearer $it") }
        return send(builder.build())
    }

    private fun send(request: HttpRequest): JsonObject {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            // XRPC errors are {"error","message"}; never echo request bodies (they may hold credentials).
            val detail = runCatching { json.parseToJsonElement(response.body()).jsonObject }.getOrNull()
            throw XrpcException("${request.uri().path} failed (${response.statusCode()}): " +
                (detail?.get("error")?.jsonPrimitive?.content ?: "") + " " + (detail?.get("message")?.jsonPrimitive?.content ?: ""))
        }
        return json.parseToJsonElement(response.body()).jsonObject
    }

    private fun get(url: String): String {
        val response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw XrpcException("GET $url failed (${response.statusCode()})")
        return response.body()
    }

    private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)
}
