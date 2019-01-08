package io.imulab.astrea.service.flow.refresh.persistence

import com.fasterxml.jackson.annotation.JsonProperty
import io.imulab.astrea.sdk.client.VoidClient
import io.imulab.astrea.sdk.commons.toLocalDateTime
import io.imulab.astrea.sdk.commons.toUnixTimestamp
import io.imulab.astrea.sdk.event.RefreshTokenEvents
import io.imulab.astrea.sdk.oauth.assertType
import io.imulab.astrea.sdk.oauth.error.InvalidGrant
import io.imulab.astrea.sdk.oauth.request.OAuthAccessRequest
import io.imulab.astrea.sdk.oauth.request.OAuthRequest
import io.imulab.astrea.sdk.oauth.token.storage.RefreshTokenRepository
import io.imulab.astrea.sdk.oidc.request.OidcSession
import io.vertx.core.Vertx
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.redis.delAwait
import io.vertx.kotlin.redis.getAwait
import io.vertx.kotlin.redis.setWithOptionsAwait
import io.vertx.redis.RedisClient
import io.vertx.redis.op.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

class SubscribingRefreshTokenRepository(
    private val vertx: Vertx,
    private val redisClient: RedisClient,
    private val concurrency: Int = 2,
    private val refreshTokenLifespan: Duration
) : RefreshTokenRepository, CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = Executors.newFixedThreadPool(concurrency).asCoroutineDispatcher()

    init {
        vertx.eventBus().consumer<JsonObject>(RefreshTokenEvents.refreshTokenCreatedEvent) { m ->
            launch { savePersistenceForm(PersistenceForm(m.body())) }
        }
    }

    override suspend fun createRefreshTokenSession(token: String, request: OAuthRequest) {
        this.savePersistenceForm(PersistenceForm(token, request.assertType()))
    }

    private suspend fun savePersistenceForm(p: PersistenceForm) {
        redisClient.setWithOptionsAwait(
            p.token,
            Json.encode(p),
            SetOptions().setEX(refreshTokenLifespan.toMillis() / 1000)
        )
    }

    override suspend fun deleteRefreshTokenAssociatedWithRequest(requestId: String) {}

    override suspend fun deleteRefreshTokenSession(token: String) {
        redisClient.delAwait(token)
    }

    override suspend fun getRefreshTokenSession(token: String): OAuthRequest {
        val json = redisClient.getAwait(token) ?: throw InvalidGrant.invalid()
        return Json.decodeValue(json, PersistenceForm::class.java).toOAuthAccessRequest()
    }

    class PersistenceForm constructor() {
        @JsonProperty("0") var token: String = ""
        @JsonProperty("1") var clientId: String = ""
        @JsonProperty("2") var scopes: MutableSet<String> = mutableSetOf()
        @JsonProperty("3") var grantedScopes: MutableSet<String> = mutableSetOf()
        @JsonProperty("4") var subject: String = ""
        @JsonProperty("5") var obfuscatedSubject: String = ""
        @JsonProperty("6") var authTime: Long = 0
        @JsonProperty("7") var accessTokenClaims: Map<String, Any> = emptyMap()
        @JsonProperty("8") var idTokenClaims: Map<String, Any> = emptyMap()
        @JsonProperty("9") var acrValues: MutableList<String> = mutableListOf()
        @JsonProperty("10") var nonce: String = ""

        constructor(event: JsonObject) : this() {
            this.token = event.getString("token")
            this.clientId = event.getString("client_id")
            this.scopes.addAll(event.getJsonArray("scopes").map { it.toString() })
            this.grantedScopes.addAll(event.getJsonArray("granted_scopes").map { it.toString() })
            this.subject = event.getString("subject")
            this.obfuscatedSubject = event.getString("obfuscated_subject")
            this.authTime = event.getLong("auth_time")
            this.nonce = event.getString("nonce")
            this.acrValues.addAll(event.getJsonArray("acr_values").map { it.toString() })
            this.accessTokenClaims = event["access_token_claims"]
            this.idTokenClaims = event["id_token_claims"]
        }

        constructor(token: String, request: OAuthAccessRequest) : this() {
            this.token = token
            this.clientId = request.client.id
            this.scopes.addAll(request.scopes)
            this.grantedScopes.addAll(request.session.grantedScopes)
            this.subject = request.session.subject
            this.obfuscatedSubject = request.session.assertType<OidcSession>().obfuscatedSubject
            this.authTime = request.session.assertType<OidcSession>().authTime?.toUnixTimestamp() ?: 0
            this.nonce = request.session.assertType<OidcSession>().nonce
            this.acrValues.addAll(request.session.assertType<OidcSession>().acrValues)
            this.accessTokenClaims = request.session.accessTokenClaims
            this.idTokenClaims = request.session.assertType<OidcSession>().idTokenClaims
        }

        fun toOAuthAccessRequest(): OAuthAccessRequest = OAuthAccessRequest.Builder().also { b ->
            b.client = IdOnlyClient(this.clientId)
            b.scopes = this.scopes
            b.session = OidcSession().also { s ->
                s.grantedScopes = this.grantedScopes
                s.subject = this.subject
                s.obfuscatedSubject = this.obfuscatedSubject
                s.authTime = this.authTime.toLocalDateTime()
                s.nonce = this.nonce
                s.acrValues = this.acrValues
                s.accessTokenClaims = this.accessTokenClaims.toMutableMap()
                s.idTokenClaims = this.idTokenClaims.toMutableMap()
            }
        }.build()
    }
    private class IdOnlyClient(override val id: String): VoidClient()
}