package io.imulab.astrea.service.flow.refresh.service

import io.grpc.stub.StreamObserver
import io.imulab.astrea.sdk.commons.flow.refresh.RefreshFlowServiceGrpc
import io.imulab.astrea.sdk.commons.flow.refresh.RefreshRequest
import io.imulab.astrea.sdk.commons.flow.refresh.RefreshResponse
import io.imulab.astrea.sdk.commons.toFailure
import io.imulab.astrea.sdk.flow.refresh.toAccessRequest
import io.imulab.astrea.sdk.flow.refresh.toRefreshResponse
import io.imulab.astrea.sdk.oauth.error.OAuthException
import io.imulab.astrea.sdk.oauth.error.ServerError
import io.imulab.astrea.sdk.oauth.handler.AccessRequestHandler
import io.imulab.astrea.sdk.oidc.response.OidcTokenEndpointResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

class RefreshGrpcService(
    private val concurrency: Int = 4,
    private val exchangeHandlers: List<AccessRequestHandler>
) : RefreshFlowServiceGrpc.RefreshFlowServiceImplBase(), CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = Executors.newFixedThreadPool(concurrency).asCoroutineDispatcher()

    override fun exchange(request: RefreshRequest?, responseObserver: StreamObserver<RefreshResponse>?) {
        if (request == null || responseObserver == null)
            return

        val job = Job()
        val accessRequest = request.toAccessRequest()
        val accessResponse = OidcTokenEndpointResponse()

        launch(job) {
            exchangeHandlers.forEach { h -> h.updateSession(accessRequest) }
            exchangeHandlers.forEach { h -> h.handleAccessRequest(accessRequest, accessResponse) }
        }.invokeOnCompletion { t ->
            if (t != null) {
                job.cancel()
                val e: OAuthException = if (t is OAuthException) t else ServerError.wrapped(t)
                responseObserver.onNext(
                    RefreshResponse.newBuilder()
                        .setSuccess(false)
                        .setFailure(e.toFailure())
                        .build()
                )
            } else {
                responseObserver.onNext(accessResponse.toRefreshResponse())
            }
            responseObserver.onCompleted()
        }
    }
}