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

package tech.pegasys.teku.beaconrestapi.handlers.v2.validator;

import static tech.pegasys.teku.beaconrestapi.BeaconRestApiTypes.GRAFFITI_PARAMETER;
import static tech.pegasys.teku.beaconrestapi.BeaconRestApiTypes.RANDAO_PARAMETER;
import static tech.pegasys.teku.beaconrestapi.BeaconRestApiTypes.SLOT_PARAMETER;
import static tech.pegasys.teku.beaconrestapi.handlers.v1.beacon.MilestoneDependentTypesUtil.getAvailableSchemaDefinitionForAllMilestones;
import static tech.pegasys.teku.beaconrestapi.handlers.v1.beacon.MilestoneDependentTypesUtil.getSchemaDefinitionForAllMilestones;
import static tech.pegasys.teku.ethereum.json.types.EthereumTypes.MILESTONE_TYPE;
import static tech.pegasys.teku.ethereum.json.types.EthereumTypes.sszResponseType;
import static tech.pegasys.teku.infrastructure.http.HttpStatusCodes.SC_OK;
import static tech.pegasys.teku.infrastructure.http.RestApiConstants.SLOT_PATH_DESCRIPTION;
import static tech.pegasys.teku.infrastructure.http.RestApiConstants.TAG_VALIDATOR;
import static tech.pegasys.teku.infrastructure.http.RestApiConstants.TAG_VALIDATOR_REQUIRED;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Optional;
import java.util.function.Function;
import org.apache.tuweni.bytes.Bytes32;
import org.jetbrains.annotations.NotNull;
import tech.pegasys.teku.api.DataProvider;
import tech.pegasys.teku.api.ValidatorDataProvider;
import tech.pegasys.teku.bls.BLSSignature;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.json.types.SerializableOneOfTypeDefinition;
import tech.pegasys.teku.infrastructure.json.types.SerializableOneOfTypeDefinitionBuilder;
import tech.pegasys.teku.infrastructure.json.types.SerializableTypeDefinition;
import tech.pegasys.teku.infrastructure.restapi.endpoints.AsyncApiResponse;
import tech.pegasys.teku.infrastructure.restapi.endpoints.EndpointMetadata;
import tech.pegasys.teku.infrastructure.restapi.endpoints.RestApiEndpoint;
import tech.pegasys.teku.infrastructure.restapi.endpoints.RestApiRequest;
import tech.pegasys.teku.infrastructure.ssz.SszData;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlock;
import tech.pegasys.teku.spec.datastructures.blocks.versions.deneb.BlindedBlockContents;
import tech.pegasys.teku.spec.datastructures.blocks.versions.deneb.BlockContents;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionCache;
import tech.pegasys.teku.spec.schemas.SchemaDefinitions;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsDeneb;
import tech.pegasys.teku.storage.client.ChainDataUnavailableException;

public class GetNewBlock extends RestApiEndpoint {
  public static final String ROUTE = "/eth/v2/validator/blocks/{slot}";

  protected final ValidatorDataProvider provider;

  public GetNewBlock(
      final DataProvider dataProvider,
      final Spec spec,
      final SchemaDefinitionCache schemaDefinitionCache) {
    this(dataProvider.getValidatorDataProvider(), spec, schemaDefinitionCache);
  }

  public GetNewBlock(
      final ValidatorDataProvider provider,
      final Spec spec,
      final SchemaDefinitionCache schemaDefinitionCache) {
    super(getEndpointMetaData(spec, schemaDefinitionCache));
    this.provider = provider;
  }

  private static EndpointMetadata getEndpointMetaData(
      final Spec spec, final SchemaDefinitionCache schemaDefinitionCache) {
    return EndpointMetadata.get(ROUTE)
        .operationId("getNewBlock")
        .summary("Produce unsigned block")
        .description(
            "Requests a beacon node to produce a valid block, which can then be signed by a validator.\n"
                + "Metadata in the response indicates the type of block produced, and the supported types of block "
                + "will be added to as forks progress.")
        .tags(TAG_VALIDATOR, TAG_VALIDATOR_REQUIRED)
        .pathParam(SLOT_PARAMETER.withDescription(SLOT_PATH_DESCRIPTION))
        .queryParamRequired(RANDAO_PARAMETER)
        .queryParam(GRAFFITI_PARAMETER)
        .response(
            SC_OK,
            "Request successful",
            getResponseTypes(schemaDefinitionCache),
            sszResponseType(getMilestoneSelector(spec)))
        .build();
  }

  @Override
  public void handleRequest(RestApiRequest request) throws JsonProcessingException {
    final UInt64 slot =
        request.getPathParameter(SLOT_PARAMETER.withDescription(SLOT_PATH_DESCRIPTION));
    final BLSSignature randao = request.getQueryParameter(RANDAO_PARAMETER);
    final Optional<Bytes32> graffiti = request.getOptionalQueryParameter(GRAFFITI_PARAMETER);
    SafeFuture<? extends Optional<? extends SszData>> result =
        provider.getUnsignedBeaconBlockAtSlot(slot, randao, graffiti, false);
    request.respondAsync(
        result.thenApply(
            maybeBlock ->
                maybeBlock
                    .map(AsyncApiResponse::respondOk)
                    .orElseThrow(ChainDataUnavailableException::new)));
  }

  private static SerializableOneOfTypeDefinition<Object> getResponseTypes(
      final SchemaDefinitionCache schemaDefinitionCache) {
    final SerializableOneOfTypeDefinitionBuilder<Object> builder =
        new SerializableOneOfTypeDefinitionBuilder<>().description("Request successful");
    builder.withType(
        value -> value instanceof BeaconBlock, getBeaconBlockResponseType(schemaDefinitionCache));
    builder.withType(
        value -> value instanceof BlockContents,
        getBlockContentsResponseType(schemaDefinitionCache));
    return builder.build();
  }

  private static SerializableTypeDefinition<BeaconBlock> getBeaconBlockResponseType(
      final SchemaDefinitionCache schemaDefinitionCache) {
    return SerializableTypeDefinition.<BeaconBlock>object()
        .name("ProduceBlockV2Response")
        .withField(
            "data",
            getSchemaDefinitionForAllMilestones(
                schemaDefinitionCache,
                "Block",
                SchemaDefinitions::getBeaconBlockSchema,
                (beaconBlock, milestone) ->
                    schemaDefinitionCache.milestoneAtSlot(beaconBlock.getSlot()).equals(milestone)),
            Function.identity())
        .withField(
            "version",
            MILESTONE_TYPE,
            block -> schemaDefinitionCache.milestoneAtSlot(block.getSlot()))
        .build();
  }

  private static SerializableTypeDefinition<BlockContents> getBlockContentsResponseType(
      final SchemaDefinitionCache schemaDefinitionCache) {
    return SerializableTypeDefinition.<BlockContents>object()
        .name("ProduceBlockContentsResponse")
        .withField(
            "data",
            getAvailableSchemaDefinitionForAllMilestones(
                schemaDefinitionCache,
                "BlockContents",
                schemaDefinitions ->
                    schemaDefinitions
                        .toVersionDeneb()
                        .map(SchemaDefinitionsDeneb::getBlockContentsSchema),
                (blockContents, milestone) ->
                    schemaDefinitionCache
                        .milestoneAtSlot(blockContents.getBeaconBlock().getSlot())
                        .equals(milestone)),
            Function.identity())
        .withField(
            "version",
            MILESTONE_TYPE,
            blockContents ->
                schemaDefinitionCache.milestoneAtSlot(blockContents.getBeaconBlock().getSlot()))
        .build();
  }

  @NotNull
  private static Function<SszData, SpecMilestone> getMilestoneSelector(final Spec spec) {
    return sszData -> {
      if (sszData instanceof BeaconBlock) {
        return spec.getForkSchedule().getSpecMilestoneAtSlot(((BeaconBlock) sszData).getSlot());
      } else if (sszData instanceof BlockContents) {
        return spec.getForkSchedule()
            .getSpecMilestoneAtSlot(((BlockContents) sszData).getBeaconBlock().getSlot());
      } else {
        throw new UnsupportedOperationException(
            String.format(
                "Unsupported GetNewBlock response type. Must be of type %s or %s but got %s",
                BeaconBlock.class.getCanonicalName(),
                BlindedBlockContents.class.getCanonicalName(),
                sszData.getClass().getCanonicalName()));
      }
    };
  }
}
