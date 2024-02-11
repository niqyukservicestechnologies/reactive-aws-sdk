package io.reactiverse.awssdk.integration.apigateway;

import cloud.localstack.Localstack;
import cloud.localstack.ServiceName;
import cloud.localstack.docker.LocalstackDockerExtension;
import cloud.localstack.docker.annotation.LocalstackDockerProperties;
import io.reactiverse.awssdk.VertxSdkClient;
import io.reactiverse.awssdk.integration.LocalStackBaseSpec;
import io.reactivex.Single;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import software.amazon.awssdk.http.HttpStatusCode;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayAsyncClient;
import software.amazon.awssdk.services.apigateway.ApiGatewayAsyncClientBuilder;
import software.amazon.awssdk.services.apigateway.model.CreateResourceResponse;
import software.amazon.awssdk.services.apigateway.model.CreateRestApiRequest;
import software.amazon.awssdk.services.apigateway.model.CreateRestApiResponse;
import software.amazon.awssdk.services.apigateway.model.GetResourcesResponse;
import software.amazon.awssdk.services.apigateway.model.IntegrationType;
import software.amazon.awssdk.services.apigateway.model.PutIntegrationResponse;
import software.amazon.awssdk.services.apigateway.model.PutIntegrationResponseResponse;
import software.amazon.awssdk.services.apigateway.model.PutMethodResponse;
import software.amazon.awssdk.services.apigateway.model.PutMethodResponseResponse;
import software.amazon.awssdk.services.apigateway.model.Resource;
import software.amazon.awssdk.services.apigateway.model.TestInvokeMethodResponse;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfSystemProperty(named = "tests.integration", matches = "localstack")
@LocalstackDockerProperties(services = { ServiceName.API_GATEWAY }, imageTag = "1.4.0")
@ExtendWith(VertxExtension.class)
@ExtendWith(LocalstackDockerExtension.class)
public class VertxApiGatewaySpec extends LocalStackBaseSpec {

    private final static String API_NAME = "MyAPI";
    private final static String PATH = "faking";
    private final static HttpMethod METHOD = HttpMethod.GET;
    private final static Map<String, String> TEMPLATES = new HashMap<>();
    private final static String MOCK_RESPONSE = "{\"message\": \"Hello from a fake backend\"}";
    static {
        TEMPLATES.put("application/json", MOCK_RESPONSE);
    }

    private ApiGatewayAsyncClient gatewayClient;
    private String apiId;
    private String parentId;
    private String resourceId;

    // README: see below for the full test
    // Since: https://github.com/localstack/localstack/issues/1030 has been fixed, it should work, but there's another issue
    // (on the test design this time: No integration defined for method "Service: ApiGateway" => investigate later)
    // For now we're just testing creation requests, but not the actual routing one, because localstack doesn't allow it
//    @Test
//    @Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
//    public void testRequestThroughGateway(Vertx vertx, VertxTestContext ctx) throws Exception {
//        final Context originalContext = vertx.getOrCreateContext();
//        createGatewayClient(originalContext);
//        // create the REST API
//        createRestApi()
//                .flatMap(restAPI -> {
//                    assertContext(vertx, originalContext, ctx);
//                    apiId = restAPI.id();
//                    return getResources();
//                })
//                .flatMap(resources -> {
//                    assertContext(vertx, originalContext, ctx);
//                    List<Resource> items = resources.items();
//                    ctx.verify(() -> {
//                        assertEquals(1, items.size());
//                    });
//                    parentId = items.get(0).id();
//                    return createResource();
//                })
//                .flatMap(createdRes -> {
//                    assertContext(vertx, originalContext, ctx);
//                    resourceId = createdRes.id();
//                    return declareGetMethod();
//                })
//                .flatMap(putMethodRes -> {
//                    assertContext(vertx, originalContext, ctx);
//                    return declare200ResponseToGet();
//                })
//                .subscribe(res -> {
//                    assertContext(vertx, originalContext, ctx);
//                    ctx.completeNow();
//                }, ctx::failNow);
//    }

    private Single<CreateRestApiResponse> createRestApi() {
        return single(gatewayClient.createRestApi(VertxApiGatewaySpec::restApiDefinition));
    }

    private Single<GetResourcesResponse> getResources() {
        return single(gatewayClient.getResources(grr -> grr.restApiId(apiId)));
    }

    private Single<CreateResourceResponse> createResource() {
        return single(gatewayClient.createResource(crr -> crr.parentId(parentId).restApiId(apiId).pathPart(PATH)));
    }

    private Single<PutMethodResponse> declareGetMethod() {
        return single(
                gatewayClient.putMethod(pmr ->
                    pmr.resourceId(resourceId)
                        .restApiId(apiId)
                        .httpMethod(METHOD.toString())
                        .authorizationType("NONE")
                )
        );
    }

    private Single<PutMethodResponseResponse> declare200ResponseToGet() {
        return single(
                gatewayClient.putMethodResponse(pmr ->
                        pmr.resourceId(resourceId)
                                .restApiId(apiId)
                                .httpMethod(METHOD.toString())
                                .statusCode(String.valueOf(HttpStatusCode.OK))
                )
        );
    }

    private Single<PutIntegrationResponse> attachRemoteEndpoint() {
        return single(
                gatewayClient.putIntegration( pir ->
                    pir.restApiId(apiId)
                        .resourceId(parentId)
                        .httpMethod(METHOD.toString())
                        .integrationHttpMethod(METHOD.toString())
                        .type(IntegrationType.MOCK)
                )
        );
    }

    private Single<PutIntegrationResponseResponse> mapRemoteResponse() {
        return single(
                gatewayClient.putIntegrationResponse( pir ->
                        pir.restApiId(apiId)
                                .resourceId(parentId)
                                .httpMethod(METHOD.toString())
                                .statusCode(String.valueOf(HttpStatusCode.OK))
                                .responseTemplates(TEMPLATES)
                )
        );
    }

    private Single<TestInvokeMethodResponse> makeIntegrationTest() {
        Map<String, String> headers = new HashMap<>(1);
        headers.put("Accept", "text/plain");
        return single(
                gatewayClient.testInvokeMethod(timr ->
                        timr.restApiId(apiId)
                                .resourceId(parentId)
                                .pathWithQueryString(PATH)
                                .httpMethod(METHOD.toString())
                                .headers(headers)
                )
        );
    }


    private static CreateRestApiRequest.Builder restApiDefinition(CreateRestApiRequest.Builder rar) {
        return rar.name(API_NAME)
                .binaryMediaTypes("text/plain")
                .description("Fetches_weather");
    }

    private void createGatewayClient(Context context) throws Exception {
        final URI gatewayURI = new URI(Localstack.INSTANCE.getEndpointAPIGateway());
        final ApiGatewayAsyncClientBuilder builder = ApiGatewayAsyncClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(credentialsProvider)
                .endpointOverride(gatewayURI);
        gatewayClient = VertxSdkClient.withVertx(builder, context).build();
    }

    /*
    .flatMap(res -> {
        assertEquals(originalContext, vertx.getOrCreateContext());
        return attachRemoteEndpoint();
    })
    .flatMap(res -> {
        assertEquals(originalContext, vertx.getOrCreateContext());
        return mapRemoteResponse();
    })
    .flatMap(res -> {
        assertEquals(originalContext, vertx.getOrCreateContext());
        return makeIntegrationTest();
    })
    .doOnSuccess(integrationTest -> {
        assertEquals(originalContext, vertx.getOrCreateContext());
        assertEquals(integrationTest.body(), MOCK_RESPONSE);
        ctx.completeNow();
    })
    */

}
