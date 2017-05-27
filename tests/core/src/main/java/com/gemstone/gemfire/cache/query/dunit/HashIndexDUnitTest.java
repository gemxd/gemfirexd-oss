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
package com.gemstone.gemfire.cache.query.dunit;

import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.query.QueryTestUtils;
import com.gemstone.gemfire.cache30.CacheSerializableRunnable;

import dunit.DistributedTestCase;
import dunit.Host;
import dunit.SerializableRunnable;
import dunit.VM;

public class HashIndexDUnitTest extends DistributedTestCase{

  QueryTestUtils utils;
  VM vm0;
  
  public HashIndexDUnitTest(String name) {
    super(name);
  }

  public void setUp() throws Exception {
    getSystem();
    invokeInEveryVM(new SerializableRunnable("getSystem") {
      public void run() {
        getSystem();
      }
    });
    Host host = Host.getHost(0);
    vm0 = host.getVM(0);
    utils = new QueryTestUtils();
    CompactRangeIndexDUnitTest.createServer(vm0, null, utils);
    CompactRangeIndexDUnitTest.createReplicateRegion("exampleRegion", vm0, utils);
    createHashIndex(vm0, "ID", "r.ID", "/exampleRegion r", utils);
  }

  public static void createHashIndex(VM vm, final String name,
      final String field, final String region, final QueryTestUtils utils) {
    vm.invoke(new SerializableRunnable("Create Replicated region") {
      public void run() throws CacheException {
        utils.createHashIndex(name, field, region);
      }
    });
  }

  public void testHashIndexForIndexElemArray() throws Exception{
    doPut(200);// around 66 entries for a key in the index (< 100 so does not create a ConcurrentHashSet)
    doQuery();
    doUpdate(200);
    doQuery();
    doDestroy(200);
    doQuery();
    Thread.sleep(5000);
  }
  
  public void testHashIndexForConcurrentHashSet() throws Exception{
    doPut(333); //111 entries for a key in the index (> 100 so creates a ConcurrentHashSet)
    doQuery();
    doUpdate(333);
    doQuery();
    doDestroy(200);
    doQuery();
  }

  public void doPut(final int entries) {
     vm0.invokeAsync(new CacheSerializableRunnable("Putting values") {
      public void run2() {
        utils.createValuesStringKeys("exampleRegion", entries);
      }
    });
  }

  public void doUpdate(final int entries) {
    vm0.invokeAsync(new CacheSerializableRunnable("Updating values") {
     public void run2() {
       utils.createDiffValuesStringKeys("exampleRegion", entries);
     }
   });
 }

  
  public void doQuery() throws Exception{
    final String[] qarr = {"173", "174", "176", "180"};
    vm0.invokeAsync(new CacheSerializableRunnable("Executing query") {
      public void run2() throws CacheException {
        try {
          for (int i = 0; i < 50; i++) {
            utils.executeQueries(qarr);
          }
        }
        catch (Exception e) {
          throw new CacheException(e){};
        }
      }
    });
  }

  public void doDestroy(final int entries) {
    vm0.invokeAsync(new CacheSerializableRunnable("Destroying values") {
      public void run2() throws CacheException {
        try {
          Thread.sleep(500);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
        try {
         utils.destroyRegion("exampleRegion", entries);
        }
        catch (Exception e) {
          throw new CacheException(e){};
        }
      }
    });
  }
  
  public void tearDown2() throws Exception{
    Thread.sleep(5000);
    CompactRangeIndexDUnitTest.closeServer(vm0, utils);
  }
}
