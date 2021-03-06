/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package com.gemstone.gemfire.internal.admin.remote;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import com.gemstone.gemfire.cache.persistence.PersistentID;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberPattern;

/**
 * The MissingPersistentIdResonse we return 662 peers. This response
 * includes this list of ids that we have locally.
 * @author dsmith
 *
 */
public class MissingPersistentIDsResponse extends AdminResponse {

  private Set<PersistentID> missingIds;
  private Set<PersistentID> localIds;
  
  public MissingPersistentIDsResponse() {
  }

  public MissingPersistentIDsResponse(Set<PersistentID> missingIds,
      Set<PersistentID> localIds, InternalDistributedMember recipient) {
    this.missingIds = missingIds;
    this.localIds = localIds;
    this.setRecipient(recipient);
  }

  public int getDSFID() {
    return MISSING_PERSISTENT_IDS_RESPONSE;
  }
  
  @Override
  protected void process(DistributionManager dm) {
    super.process(dm);
  }

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    super.fromData(in);
    int size = in.readInt();
    missingIds = new HashSet<PersistentID>(size);
    for(int i =0; i < size; i++) {
      PersistentMemberPattern pattern = new PersistentMemberPattern();
      InternalDataSerializer.invokeFromData(pattern,in);
      missingIds.add(pattern);
    }
    size = in.readInt();
    localIds = new HashSet<PersistentID>(size);
    for(int i =0; i < size; i++) {
      PersistentMemberPattern pattern = new PersistentMemberPattern();
      InternalDataSerializer.invokeFromData(pattern,in);
      localIds.add(pattern);
    }
  }

  @Override
  public void toData(DataOutput out) throws IOException {
    super.toData(out);
    out.writeInt(missingIds.size());
    for(PersistentID pattern : missingIds) {
      InternalDataSerializer.invokeToData(pattern, out);
    }
    out.writeInt(localIds.size());
    for(PersistentID pattern : localIds) {
      InternalDataSerializer.invokeToData(pattern,out);
    }
  }
  
  public Set<PersistentID> getMissingIds() {
    return missingIds;
  }
  
  public Set<PersistentID> getLocalIds() {
    return localIds;
  }

  @Override
  protected Object clone() throws CloneNotSupportedException {
    // TODO Auto-generated method stub
    return super.clone();
  }

  @Override
  public String toString() {
    return getClass().getName() + ": missing=" + missingIds + "local="
        + localIds;
  }
  
  
}
