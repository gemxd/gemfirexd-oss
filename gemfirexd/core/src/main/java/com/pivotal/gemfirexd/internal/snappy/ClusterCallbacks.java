/*
 * Copyright (c) 2016 SnappyData, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.snappy;

import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;

import java.util.HashSet;

/**
 * Callbacks that are required for cluster management of Snappy should go here.
 */
public interface ClusterCallbacks {

    public HashSet<String> getLeaderGroup();

    public void launchExecutor(String driver_url, InternalDistributedMember driverDM);

    public String getDriverURL();

    public void stopExecutor();

    public SparkSQLExecute getSQLExecute(String sql, LeadNodeExecutionContext ctx, Version v);
}
