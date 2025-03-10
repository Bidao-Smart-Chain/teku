/*
 * Copyright ConsenSys Software Inc., 2022
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.ethereum.executionclient.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static tech.pegasys.teku.spec.schemas.ApiSchemas.SIGNED_VALIDATOR_REGISTRATIONS_SCHEMA;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.Resources;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.Bytes48;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import tech.pegasys.teku.bls.BLSPublicKey;
import tech.pegasys.teku.ethereum.executionclient.schema.BuilderApiResponse;
import tech.pegasys.teku.infrastructure.json.JsonUtil;
import tech.pegasys.teku.infrastructure.json.exceptions.MissingRequiredFieldException;
import tech.pegasys.teku.infrastructure.json.types.DeserializableTypeDefinition;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.TestSpecContext;
import tech.pegasys.teku.spec.TestSpecInvocationContextProvider.SpecContext;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.builder.SignedBuilderBid;
import tech.pegasys.teku.spec.datastructures.builder.SignedValidatorRegistration;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayload;
import tech.pegasys.teku.spec.networks.Eth2Network;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsBellatrix;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsCapella;

@TestSpecContext(
    milestone = {SpecMilestone.BELLATRIX, SpecMilestone.CAPELLA},
    network = Eth2Network.MAINNET)
class RestBuilderClientTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final Duration WAIT_FOR_CALL_COMPLETION = Duration.ofSeconds(10);

  private static final String INTERNAL_SERVER_ERROR_MESSAGE =
      "{\"code\":500,\"message\":\"Internal server error\"}";

  private static final UInt64 SLOT = UInt64.ONE;

  private static final Bytes32 PARENT_HASH =
      Bytes32.fromHexString("0xcf8e0d4e9587369b2301d0790347320302cc0943d5a1884560367e8208d920f2");

  private static final BLSPublicKey PUB_KEY =
      BLSPublicKey.fromBytesCompressed(
          Bytes48.fromHexString(
              "0x93247f2209abcacf57b75a51dafae777f9dd38bc7053d1af526f220a7489a6d3a2753e5f3e8b1cfe39b56f43611df74a"));

  private static final Pattern TEKU_USER_AGENT_REGEX = Pattern.compile("teku/v.*");

  private static final Consumer<RecordedRequest> USER_AGENT_HEADER_ASSERTION =
      recordedRequest ->
          assertThat(recordedRequest.getHeader("User-Agent")).matches(TEKU_USER_AGENT_REGEX);

  private final OkHttpClient okHttpClient = new OkHttpClient.Builder().build();
  private final MockWebServer mockWebServer = new MockWebServer();

  private Spec spec;
  private OkHttpRestClient okHttpRestClient;
  private SchemaDefinitionsBellatrix schemaDefinitions;

  private RestBuilderClient restBuilderClient;

  private String signedValidatorRegistrationsRequest;
  private String signedBlindedBeaconBlockRequest;
  private String executionPayloadHeaderResponse;
  private String unblindedExecutionPayloadResponse;

  @BeforeEach
  void setUp(final SpecContext specContext) throws IOException {
    mockWebServer.start();

    spec = specContext.getSpec();
    final String endpoint = "http://localhost:" + mockWebServer.getPort();
    okHttpRestClient = new OkHttpRestClient(okHttpClient, endpoint);

    final SpecMilestone milestone = specContext.getSpecMilestone();

    if (milestone.equals(SpecMilestone.BELLATRIX)) {
      this.schemaDefinitions =
          SchemaDefinitionsBellatrix.required(specContext.getSchemaDefinitions());
    } else {
      this.schemaDefinitions =
          SchemaDefinitionsCapella.required(specContext.getSchemaDefinitions());
    }

    signedValidatorRegistrationsRequest = readResource("builder/signedValidatorRegistrations.json");
    final String milestoneFolder = "builder/" + milestone.toString().toLowerCase();
    signedBlindedBeaconBlockRequest =
        readResource(milestoneFolder + "/signedBlindedBeaconBlock.json");
    executionPayloadHeaderResponse =
        readResource(milestoneFolder + "/executionPayloadHeaderResponse.json");
    unblindedExecutionPayloadResponse =
        readResource(milestoneFolder + "/unblindedExecutionPayloadResponse.json");

    restBuilderClient = new RestBuilderClient(okHttpRestClient, spec, true);
  }

  @AfterEach
  void afterEach() throws Exception {
    mockWebServer.shutdown();
  }

  @TestTemplate
  void getStatus_success() {
    mockWebServer.enqueue(new MockResponse().setResponseCode(200));

    assertThat(restBuilderClient.status())
        .succeedsWithin(WAIT_FOR_CALL_COMPLETION)
        .satisfies(
            response -> {
              assertThat(response.isSuccess()).isTrue();
              assertThat(response.getPayload()).isNull();
            });

    verifyGetRequest("/eth/v1/builder/status");
  }

  @TestTemplate
  void getStatus_failures() {
    mockWebServer.enqueue(
        new MockResponse().setResponseCode(500).setBody(INTERNAL_SERVER_ERROR_MESSAGE));

    assertThat(restBuilderClient.status())
        .succeedsWithin(WAIT_FOR_CALL_COMPLETION)
        .satisfies(
            response -> {
              assertThat(response.isFailure()).isTrue();
              assertThat(response.getErrorMessage()).isEqualTo(INTERNAL_SERVER_ERROR_MESSAGE);
            });

    verifyGetRequest("/eth/v1/builder/status");
  }

  @TestTemplate
  void registerValidators_success() {

    mockWebServer.enqueue(new MockResponse().setResponseCode(200));

    final SszList<SignedValidatorRegistration> signedValidatorRegistrations =
        createSignedValidatorRegistrations();

    assertThat(restBuilderClient.registerValidators(SLOT, signedValidatorRegistrations))
        .succeedsWithin(WAIT_FOR_CALL_COMPLETION)
        .satisfies(
            response -> {
              assertThat(response.isSuccess()).isTrue();
              assertThat(response.getPayload()).isNull();
            });

    verifyPostRequest("/eth/v1/builder/validators", signedValidatorRegistrationsRequest);
  }

  @TestTemplate
  void registerValidators_zeroRegistrationsDoesNotMakeRequest() {

    final SszList<SignedValidatorRegistration> zeroRegistrations =
        SIGNED_VALIDATOR_REGISTRATIONS_SCHEMA.getDefault();

    assertThat(restBuilderClient.registerValidators(SLOT, zeroRegistrations))
        .succeedsWithin(WAIT_FOR_CALL_COMPLETION)
        .satisfies(
            response -> {
              assertThat(response.isSuccess()).isTrue();
              assertThat(response.getPayload()).isNull();
            });

    assertThat(mockWebServer.getRequestCount()).isEqualTo(0);
  }

  @TestTemplate
  void registerValidators_failures() {

    final String unknownValidatorError = "{\"code\":400,\"message\":\"unknown validator\"}";

    mockWebServer.enqueue(new MockResponse().setResponseCode(400).setBody(unknownValidatorError));

    final SszList<SignedValidatorRegistration> signedValidatorRegistrations =
        createSignedValidatorRegistrations();

    assertThat(restBuilderClient.registerValidators(SLOT, signedValidatorRegistrations))
        .succeedsWithin(WAIT_FOR_CALL_COMPLETION)
        .satisfies(
            response -> {
              assertThat(response.isFailure()).isTrue();
              assertThat(response.getErrorMessage()).isEqualTo(unknownValidatorError);
            });

    verifyPostRequest("/eth/v1/builder/validators", signedValidatorRegistrationsRequest);

    mockWebServer.enqueue(
        new MockResponse().setResponseCode(500).setBody(INTERNAL_SERVER_ERROR_MESSAGE));

    assertThat(restBuilderClient.registerValidators(SLOT, signedValidatorRegistrations))
        .succeedsWithin(WAIT_FOR_CALL_COMPLETION)
        .satisfies(
            response -> {
              assertThat(response.isFailure()).isTrue();
              assertThat(response.getErrorMessage()).isEqualTo(INTERNAL_SERVER_ERROR_MESSAGE);
            });

    verifyPostRequest("/eth/v1/builder/validators", signedValidatorRegistrationsRequest);
  }

  @TestTemplate
  void getExecutionPayloadHeader_success_doesNotSetUserAgentHeader() {

    restBuilderClient = new RestBuilderClient(okHttpRestClient, spec, false);

    mockWebServer.enqueue(
        new MockResponse().setResponseCode(200).setBody(executionPayloadHeaderResponse));

    assertThat(restBuilderClient.getHeader(SLOT, PUB_KEY, PARENT_HASH))
        .succeedsWithin(WAIT_FOR_CALL_COMPLETION)
        .satisfies(
            response -> {
              assertThat(response.isSuccess()).isTrue();
              assertThat(response.getPayload())
                  .isPresent()
                  .hasValueSatisfying(this::verifySignedBuilderBidResponse);
            });

    verifyGetRequest(
        "/eth/v1/builder/header/1/" + PARENT_HASH + "/" + PUB_KEY,
        recordedRequest -> {
          assertThat(recordedRequest.getHeader("User-Agent")).doesNotMatch(TEKU_USER_AGENT_REGEX);
        });
  }

  @TestTemplate
  void getExecutionPayloadHeader_success() {

    mockWebServer.enqueue(
        new MockResponse().setResponseCode(200).setBody(executionPayloadHeaderResponse));

    assertThat(restBuilderClient.getHeader(SLOT, PUB_KEY, PARENT_HASH))
        .succeedsWithin(WAIT_FOR_CALL_COMPLETION)
        .satisfies(
            response -> {
              assertThat(response.isSuccess()).isTrue();
              assertThat(response.getPayload())
                  .isPresent()
                  .hasValueSatisfying(this::verifySignedBuilderBidResponse);
            });

    verifyGetRequest(
        "/eth/v1/builder/header/1/" + PARENT_HASH + "/" + PUB_KEY, USER_AGENT_HEADER_ASSERTION);
  }

  @TestTemplate
  void getExecutionPayloadHeader_noHeaderAvailable() {

    mockWebServer.enqueue(new MockResponse().setResponseCode(204));

    assertThat(restBuilderClient.getHeader(SLOT, PUB_KEY, PARENT_HASH))
        .succeedsWithin(WAIT_FOR_CALL_COMPLETION)
        .satisfies(
            response -> {
              assertThat(response.isSuccess()).isTrue();
              assertThat(response.getPayload()).isEmpty();
            });

    verifyGetRequest(
        "/eth/v1/builder/header/1/" + PARENT_HASH + "/" + PUB_KEY, USER_AGENT_HEADER_ASSERTION);
  }

  @TestTemplate
  void getExecutionPayloadHeader_failures() {

    final String missingParentHashError =
        "{\"code\":400,\"message\":\"Unknown hash: missing parent hash\"}";
    mockWebServer.enqueue(new MockResponse().setResponseCode(400).setBody(missingParentHashError));

    assertThat(restBuilderClient.getHeader(SLOT, PUB_KEY, PARENT_HASH))
        .succeedsWithin(WAIT_FOR_CALL_COMPLETION)
        .satisfies(
            response -> {
              assertThat(response.isFailure()).isTrue();
              assertThat(response.getErrorMessage()).isEqualTo(missingParentHashError);
            });

    verifyGetRequest(
        "/eth/v1/builder/header/1/" + PARENT_HASH + "/" + PUB_KEY, USER_AGENT_HEADER_ASSERTION);

    mockWebServer.enqueue(
        new MockResponse().setResponseCode(500).setBody(INTERNAL_SERVER_ERROR_MESSAGE));

    assertThat(restBuilderClient.getHeader(SLOT, PUB_KEY, PARENT_HASH))
        .succeedsWithin(WAIT_FOR_CALL_COMPLETION)
        .satisfies(
            response -> {
              assertThat(response.isFailure()).isTrue();
              assertThat(response.getErrorMessage()).isEqualTo(INTERNAL_SERVER_ERROR_MESSAGE);
            });

    verifyGetRequest(
        "/eth/v1/builder/header/1/" + PARENT_HASH + "/" + PUB_KEY, USER_AGENT_HEADER_ASSERTION);
  }

  @TestTemplate
  void getExecutionPayloadHeader_wrongFork(final SpecContext specContext) {
    specContext.assumeCapellaActive();

    final String milestoneFolder = "builder/" + SpecMilestone.BELLATRIX.toString().toLowerCase();

    executionPayloadHeaderResponse =
        readResource(milestoneFolder + "/executionPayloadHeaderResponse.json");

    mockWebServer.enqueue(
        new MockResponse().setResponseCode(200).setBody(executionPayloadHeaderResponse));

    assertThat(restBuilderClient.getHeader(SLOT, PUB_KEY, PARENT_HASH))
        .failsWithin(WAIT_FOR_CALL_COMPLETION)
        .withThrowableOfType(ExecutionException.class)
        .withRootCauseInstanceOf(MissingRequiredFieldException.class)
        .withMessageContaining("required fields: (withdrawals_root) were not set");

    verifyGetRequest(
        "/eth/v1/builder/header/1/" + PARENT_HASH + "/" + PUB_KEY, USER_AGENT_HEADER_ASSERTION);
  }

  @TestTemplate
  void getExecutionPayloadHeader_wrongVersion(final SpecContext specContext) {
    specContext.assumeCapellaActive();

    final String milestoneFolder =
        "builder/"
            + specContext.getSpecMilestone().toString().toLowerCase(Locale.ROOT)
            + "_wrong_version_responses";

    executionPayloadHeaderResponse =
        readResource(milestoneFolder + "/executionPayloadHeaderResponse.json");

    mockWebServer.enqueue(
        new MockResponse().setResponseCode(200).setBody(executionPayloadHeaderResponse));

    assertThat(restBuilderClient.getHeader(SLOT, PUB_KEY, PARENT_HASH))
        .failsWithin(WAIT_FOR_CALL_COMPLETION)
        .withThrowableOfType(ExecutionException.class)
        .withRootCauseInstanceOf(IllegalArgumentException.class)
        .withMessageContaining(
            "java.lang.IllegalArgumentException: Wrong response version: expected CAPELLA, received BELLATRIX");

    verifyGetRequest(
        "/eth/v1/builder/header/1/" + PARENT_HASH + "/" + PUB_KEY, USER_AGENT_HEADER_ASSERTION);
  }

  @TestTemplate
  void sendSignedBlindedBlock_success() {

    mockWebServer.enqueue(
        new MockResponse().setResponseCode(200).setBody(unblindedExecutionPayloadResponse));

    final SignedBeaconBlock signedBlindedBeaconBlock = createSignedBlindedBeaconBlock();

    assertThat(restBuilderClient.getPayload(signedBlindedBeaconBlock))
        .succeedsWithin(WAIT_FOR_CALL_COMPLETION)
        .satisfies(
            response -> {
              assertThat(response.isSuccess()).isTrue();
              ExecutionPayload responsePayload = response.getPayload();
              verifyExecutionPayloadResponse(responsePayload);
            });

    verifyPostRequest("/eth/v1/builder/blinded_blocks", signedBlindedBeaconBlockRequest);
  }

  @TestTemplate
  void sendSignedBlindedBlock_failures() {

    String missingSignatureError =
        "{\"code\":400,\"message\":\"Invalid block: missing signature\"}";
    mockWebServer.enqueue(new MockResponse().setResponseCode(400).setBody(missingSignatureError));

    final SignedBeaconBlock signedBlindedBeaconBlock = createSignedBlindedBeaconBlock();

    assertThat(restBuilderClient.getPayload(signedBlindedBeaconBlock))
        .succeedsWithin(WAIT_FOR_CALL_COMPLETION)
        .satisfies(
            response -> {
              assertThat(response.isFailure()).isTrue();
              assertThat(response.getErrorMessage()).isEqualTo(missingSignatureError);
            });

    verifyPostRequest("/eth/v1/builder/blinded_blocks", signedBlindedBeaconBlockRequest);

    mockWebServer.enqueue(
        new MockResponse().setResponseCode(500).setBody(INTERNAL_SERVER_ERROR_MESSAGE));

    assertThat(restBuilderClient.getPayload(signedBlindedBeaconBlock))
        .succeedsWithin(WAIT_FOR_CALL_COMPLETION)
        .satisfies(
            response -> {
              assertThat(response.isFailure()).isTrue();
              assertThat(response.getErrorMessage()).isEqualTo(INTERNAL_SERVER_ERROR_MESSAGE);
            });

    verifyPostRequest("/eth/v1/builder/blinded_blocks", signedBlindedBeaconBlockRequest);
  }

  @TestTemplate
  void sendSignedBlindedBlock_wrongVersion(final SpecContext specContext) {
    specContext.assumeCapellaActive();

    final String milestoneFolder =
        "builder/"
            + specContext.getSpecMilestone().toString().toLowerCase(Locale.ROOT)
            + "_wrong_version_responses";

    unblindedExecutionPayloadResponse =
        readResource(milestoneFolder + "/unblindedExecutionPayloadResponse.json");

    mockWebServer.enqueue(
        new MockResponse().setResponseCode(200).setBody(unblindedExecutionPayloadResponse));

    final SignedBeaconBlock signedBlindedBeaconBlock = createSignedBlindedBeaconBlock();

    assertThat(restBuilderClient.getPayload(signedBlindedBeaconBlock))
        .succeedsWithin(WAIT_FOR_CALL_COMPLETION)
        .satisfies(
            response -> {
              assertThat(response.isSuccess()).isTrue();
              ExecutionPayload responsePayload = response.getPayload();
              verifyExecutionPayloadResponse(responsePayload);
            });

    verifyPostRequest("/eth/v1/builder/blinded_blocks", signedBlindedBeaconBlockRequest);
  }

  private void verifyGetRequest(final String apiPath) {
    verifyRequest("GET", apiPath, Optional.empty());
  }

  private void verifyGetRequest(
      final String apiPath, final Consumer<RecordedRequest> additionalRequestAssertions) {
    verifyRequest("GET", apiPath, Optional.empty(), Optional.of(additionalRequestAssertions));
  }

  private void verifyPostRequest(final String apiPath, final String requestBody) {
    verifyRequest("POST", apiPath, Optional.of(requestBody));
  }

  private void verifyRequest(
      final String method, final String apiPath, final Optional<String> expectedRequestBody) {
    verifyRequest(method, apiPath, expectedRequestBody, Optional.empty());
  }

  private void verifyRequest(
      final String method,
      final String apiPath,
      final Optional<String> expectedRequestBody,
      final Optional<Consumer<RecordedRequest>> additionalRequestAssertions) {
    try {
      final RecordedRequest request = mockWebServer.takeRequest();
      assertThat(request.getMethod()).isEqualTo(method);
      assertThat(request.getPath()).isEqualTo(apiPath);
      final Buffer actualRequestBody = request.getBody();
      if (expectedRequestBody.isEmpty()) {
        assertThat(actualRequestBody.size()).isZero();
      } else {
        assertThat(actualRequestBody.size()).isNotZero();
        assertThat(OBJECT_MAPPER.readTree(expectedRequestBody.get()))
            .isEqualTo(OBJECT_MAPPER.readTree(actualRequestBody.readUtf8()));
      }
      additionalRequestAssertions.ifPresent(assertions -> assertions.accept(request));
    } catch (final InterruptedException | JsonProcessingException ex) {
      Assertions.fail(ex);
    }
  }

  private SszList<SignedValidatorRegistration> createSignedValidatorRegistrations() {
    try {
      return JsonUtil.parse(
          signedValidatorRegistrationsRequest,
          SIGNED_VALIDATOR_REGISTRATIONS_SCHEMA.getJsonTypeDefinition());
    } catch (JsonProcessingException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  private void verifySignedBuilderBidResponse(final SignedBuilderBid actual) {
    final DeserializableTypeDefinition<BuilderApiResponse<SignedBuilderBid>>
        responseTypeDefinition =
            BuilderApiResponse.createTypeDefinition(
                schemaDefinitions.getSignedBuilderBidSchema().getJsonTypeDefinition());
    try {
      final SignedBuilderBid expected =
          JsonUtil.parse(executionPayloadHeaderResponse, responseTypeDefinition).getData();
      assertThat(actual).isEqualTo(expected);
    } catch (JsonProcessingException ex) {
      Assertions.fail(ex);
    }
  }

  private SignedBeaconBlock createSignedBlindedBeaconBlock() {
    try {
      return JsonUtil.parse(
          signedBlindedBeaconBlockRequest,
          schemaDefinitions.getSignedBlindedBeaconBlockSchema().getJsonTypeDefinition());
    } catch (JsonProcessingException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  private void verifyExecutionPayloadResponse(final ExecutionPayload actual) {
    final DeserializableTypeDefinition<? extends BuilderApiResponse<? extends ExecutionPayload>>
        responseTypeDefinition =
            BuilderApiResponse.createTypeDefinition(
                schemaDefinitions.getExecutionPayloadSchema().getJsonTypeDefinition());
    try {
      final ExecutionPayload expected =
          JsonUtil.parse(unblindedExecutionPayloadResponse, responseTypeDefinition).getData();
      assertThat(actual).isEqualTo(expected);
    } catch (JsonProcessingException ex) {
      Assertions.fail(ex);
    }
  }

  private static String readResource(final String resource) {
    try {
      return Resources.toString(Resources.getResource(resource), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }
}
