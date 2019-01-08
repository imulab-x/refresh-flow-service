package io.imulab.astrea.service.flow.refresh

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.grpc.ManagedChannelBuilder
import io.imulab.astrea.sdk.discovery.RemoteDiscoveryService
import io.imulab.astrea.sdk.discovery.SampleDiscovery
import io.imulab.astrea.sdk.oauth.handler.OAuthRefreshHandler
import io.imulab.astrea.sdk.oauth.handler.helper.AccessTokenHelper
import io.imulab.astrea.sdk.oauth.handler.helper.RefreshTokenHelper
import io.imulab.astrea.sdk.oauth.reserved.AuthenticationMethod
import io.imulab.astrea.sdk.oauth.token.JwtSigningAlgorithm
import io.imulab.astrea.sdk.oauth.token.storage.RefreshTokenRepository
import io.imulab.astrea.sdk.oauth.token.strategy.HmacSha2RefreshTokenStrategy
import io.imulab.astrea.sdk.oauth.token.strategy.JwtAccessTokenStrategy
import io.imulab.astrea.sdk.oauth.token.strategy.RefreshTokenStrategy
import io.imulab.astrea.sdk.oidc.discovery.Discovery
import io.imulab.astrea.sdk.oidc.discovery.OidcContext
import io.imulab.astrea.sdk.oidc.handler.OidcRefreshHandler
import io.imulab.astrea.sdk.oidc.token.JwxIdTokenStrategy
import io.imulab.astrea.service.flow.refresh.persistence.NoOpAccessTokenRepository
import io.imulab.astrea.service.flow.refresh.persistence.SubscribingRefreshTokenRepository
import io.imulab.astrea.service.flow.refresh.service.HealthVerticle
import io.imulab.astrea.service.flow.refresh.service.LocalJsonWebKeySetStrategy
import io.imulab.astrea.service.flow.refresh.service.RefreshGrpcService
import io.imulab.astrea.service.flow.refresh.service.RefreshServiceVerticle
import io.vertx.core.Vertx
import io.vertx.ext.healthchecks.HealthCheckHandler
import io.vertx.ext.healthchecks.Status
import io.vertx.redis.RedisClient
import io.vertx.redis.RedisOptions
import kotlinx.coroutines.runBlocking
import org.jose4j.jwk.JsonWebKeySet
import org.jose4j.keys.AesKey
import org.kodein.di.Kodein
import org.kodein.di.generic.bind
import org.kodein.di.generic.eagerSingleton
import org.kodein.di.generic.instance
import org.kodein.di.generic.singleton
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.*

private val logger = LoggerFactory.getLogger("io.imulab.astrea.service.flow.refresh.AppKt")

fun main(args: Array<String>) {
    val vertx = Vertx.vertx()
    val config = ConfigFactory.load()
    val app = App(vertx, config).bootstrap()

    val grpcApi by app.instance<RefreshServiceVerticle>()
    vertx.deployVerticle(grpcApi) { ar ->
        if (ar.succeeded()) {
            logger.info("Refresh token flow service successfully deployed with id {}", ar.result())
        } else {
            logger.error("Refresh token flow service failed to deploy.", ar.cause())
        }
    }

    val healthApi by app.instance<HealthVerticle>()
    vertx.deployVerticle(healthApi) { ar ->
        if (ar.succeeded()) {
            logger.info("Authorize code flow service health information available.")
        } else {
            logger.error("Authorize code flow service health information unavailable.", ar.cause())
        }
    }
}

@Suppress("MemberVisibilityCanBePrivate")
open class App(vertx: Vertx, config: Config) {
    open fun bootstrap() = Kodein {
        importOnce(discovery)
        importOnce(persistence)
        importOnce(app)
    }

    val discovery = Kodein.Module("discovery") {
        bind<Discovery>() with eagerSingleton {
            val channel = ManagedChannelBuilder.forAddress(
                config.getString("discovery.host"),
                config.getInt("discovery.port")
            ).enableRetry().maxRetryAttempts(10).usePlaintext().build()

            if (config.getBoolean("discovery.useSample")) {
                logger.info("Using default discovery instead of remote.")
                SampleDiscovery.default()
            } else {
                runBlocking {
                    RemoteDiscoveryService(channel).getDiscovery()
                }.also { logger.info("Acquired discovery from remote.") }
            }
        }
    }

    val persistence = Kodein.Module("persistence") {
        bind<RedisClient>() with singleton {
            RedisClient.create(vertx, RedisOptions().apply {
                host = config.getString("redis.host")
                port = config.getInt("redis.port")
                select = config.getInt("redis.db")
            })
        }

        bind<RefreshTokenRepository>() with singleton {
            SubscribingRefreshTokenRepository(
                vertx = vertx,
                redisClient = instance(),
                refreshTokenLifespan = instance<ServiceContext>().refreshTokenLifespan
            )
        }
    }

    val app = Kodein.Module("app") {
        bind<ServiceContext>() with singleton { ServiceContext(instance(), config) }

        bind<RefreshTokenStrategy>() with singleton {
            HmacSha2RefreshTokenStrategy(
                key = instance<ServiceContext>().refreshTokenKey,
                signingAlgorithm = JwtSigningAlgorithm.HS256
            )
        }

        bind<OAuthRefreshHandler>() with singleton {
            OAuthRefreshHandler(
                accessTokenHelper = AccessTokenHelper(
                    oauthContext = instance(),
                    accessTokenStrategy = JwtAccessTokenStrategy(
                        oauthContext = instance(),
                        signingAlgorithm = JwtSigningAlgorithm.RS256,
                        serverJwks = instance<ServiceContext>().masterJsonWebKeySet
                    ),
                    accessTokenRepository = NoOpAccessTokenRepository
                ),
                accessTokenRepository = NoOpAccessTokenRepository,
                refreshTokenHelper = RefreshTokenHelper(
                    refreshTokenStrategy = instance(),
                    refreshTokenRepository = instance()
                ),
                refreshTokenStrategy = instance(),
                refreshTokenRepository = instance()
            )
        }

        bind<OidcRefreshHandler>() with singleton {
            OidcRefreshHandler(
                JwxIdTokenStrategy(
                    oidcContext = instance(),
                    jsonWebKeySetStrategy = LocalJsonWebKeySetStrategy(
                        instance<ServiceContext>().masterJsonWebKeySet
                    )
                )
            )
        }

        bind<HealthCheckHandler>() with singleton {
            HealthCheckHandler.create(vertx).apply {
                val redisClient = instance<RedisClient>()
                register("refresh_token_redis", 2000) { h ->
                    redisClient.ping { ar ->
                        if (ar.succeeded())
                            h.complete(Status.OK())
                        else
                            h.complete(Status.KO())
                    }
                }
            }
        }

        bind<RefreshServiceVerticle>() with singleton {
            RefreshServiceVerticle(
                flowService = RefreshGrpcService(
                    exchangeHandlers = listOf(
                        instance<OAuthRefreshHandler>(),
                        instance<OidcRefreshHandler>()
                    )
                ),
                healthCheckHandler = instance(),
                appConfig = config
            )
        }

        bind<HealthVerticle>() with singleton {
            HealthVerticle(healthCheckHandler = instance(), appConfig = config)
        }
    }
}

class ServiceContext(discovery: Discovery, config: Config) : OidcContext, Discovery by discovery {
    override val accessTokenLifespan: Duration = config.getDuration("service.accessTokenLifespan")
    override val authorizeCodeLifespan: Duration = Duration.ZERO
    override val authorizeEndpointUrl: String = authorizationEndpoint
    override val defaultTokenEndpointAuthenticationMethod: String = AuthenticationMethod.clientSecretBasic
    override val idTokenLifespan: Duration = config.getDuration("service.idTokenLifespan")
    override val issuerUrl: String = issuer
    override val masterJsonWebKeySet: JsonWebKeySet = JsonWebKeySet(config.getString("service.jwks"))
    override val nonceEntropy: Int = config.getInt("service.nonceEntropy")
    override val refreshTokenLifespan: Duration = config.getDuration("service.refreshTokenLifespan")
    override val stateEntropy: Int = config.getInt("service.stateEntropy")
    override val tokenEndpointUrl: String = tokenEndpoint
    val refreshTokenKey = AesKey(Base64.getDecoder().decode(config.getString("service.refreshTokenKey")))
    override fun validate() { super<OidcContext>.validate() }
}