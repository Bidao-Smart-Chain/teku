/*
 * Copyright 2022 ConsenSys AG.
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

package tech.pegasys.teku.spec.datastructures.execution;

import tech.pegasys.teku.bls.BLSSignature;
import tech.pegasys.teku.infrastructure.ssz.containers.Container2;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.spec.datastructures.type.SszSignature;

public class SignedValidatorRegistrationV1
    extends Container2<SignedValidatorRegistrationV1, ValidatorRegistrationV1, SszSignature> {

  SignedValidatorRegistrationV1(SignedValidatorRegistrationV1Schema type, TreeNode backingNode) {
    super(type, backingNode);
  }

  SignedValidatorRegistrationV1(
      final SignedValidatorRegistrationV1Schema type,
      final ValidatorRegistrationV1 message,
      final BLSSignature signature) {
    super(type, message, new SszSignature(signature));
  }

  @Override
  public SignedValidatorRegistrationV1Schema getSchema() {
    return (SignedValidatorRegistrationV1Schema) super.getSchema();
  }

  public ValidatorRegistrationV1 getMessage() {
    return getField0();
  }

  public BLSSignature getSignature() {
    return getField1().getSignature();
  }
}
