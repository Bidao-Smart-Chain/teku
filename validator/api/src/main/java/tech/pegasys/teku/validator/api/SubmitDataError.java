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

package tech.pegasys.teku.validator.api;

import static tech.pegasys.teku.infrastructure.json.types.CoreTypes.STRING_TYPE;
import static tech.pegasys.teku.infrastructure.json.types.CoreTypes.UINT64_TYPE;

import java.util.Objects;
import tech.pegasys.teku.infrastructure.json.types.SerializableTypeDefinition;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

public class SubmitDataError {
  private final UInt64 index;
  private final String message;

  public SubmitDataError(final UInt64 index, final String message) {
    this.index = index;
    this.message = message;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final SubmitDataError that = (SubmitDataError) o;
    return Objects.equals(index, that.index) && Objects.equals(message, that.message);
  }

  @Override
  public int hashCode() {
    return Objects.hash(index, message);
  }

  public UInt64 getIndex() {
    return index;
  }

  public String getMessage() {
    return message;
  }

  public static SerializableTypeDefinition<SubmitDataError> getJsonTypeDefinition() {
    return SerializableTypeDefinition.object(SubmitDataError.class)
        .name("SubmitDataError")
        .withField("index", UINT64_TYPE, SubmitDataError::getIndex)
        .withField("message", STRING_TYPE, SubmitDataError::getMessage)
        .build();
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("SubmitDataError{");
    sb.append("index=").append(index);
    sb.append(", message='").append(message).append('\'');
    sb.append('}');
    return sb.toString();
  }
}
