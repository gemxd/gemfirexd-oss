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

import java.util.Properties;

import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.query.QueryTestUtils;
import com.gemstone.gemfire.cache.query.data.Portfolio;
import com.gemstone.gemfire.cache.query.internal.index.IndexManager;
import com.gemstone.gemfire.cache.query.internal.index.IndexManager.TestHook;
import com.gemstone.gemfire.cache30.CacheSerializableRunnable;
import dunit.AsyncInvocation;
import dunit.DistributedTestCase;
import dunit.Host;
import dunit.SerializableRunnable;
import dunit.VM;

public class CompactRangeIndexDUnitTest extends DistributedTestCase{

  QueryTestUtils utils;
  VM vm0;
  
  public CompactRangeIndexDUnitTest(String name) {
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
    createServer(vm0, null, utils);
    createReplicateRegion("exampleRegion", vm0, utils);
    createIndex(vm0, "type", "\"type\"", "/exampleRegion", utils);
  }

  public static void createServer(VM server, final Properties prop,
      final QueryTestUtils utils) {
    SerializableRunnable createCacheServer = new SerializableRunnable(
        "Create Cache Server") {
      private static final long serialVersionUID = 1L;

      public void run() throws CacheException {
        utils.createCache(prop);
      }
    };
    server.invoke(createCacheServer);
  }

  public static void createIndex(VM vm, final String name, final String field,
      final String region, final QueryTestUtils utils) throws CacheException {
    vm.invoke(new SerializableRunnable("Create Index") {
      public void run() throws CacheException {
        utils.createIndex(name, field, region);
      }
    });
  }

  public static void createPartitionRegion(final String name,
      final Class constraint, VM vm, final QueryTestUtils utils) {
    vm.invoke(new SerializableRunnable("Create Partition region") {
      private static final long serialVersionUID = 1L;

      public void run() throws CacheException {
        utils.createPartitionRegion(name, constraint);
      }
    });
  }

  public static void createReplicateRegion(final String name, VM vm,
      final QueryTestUtils utils) {
    vm.invoke(new SerializableRunnable("Create Replicated region") {
      private static final long serialVersionUID = 1L;

      public void run() throws CacheException {
        utils.getLogger().fine("### Create replicated region. ###");
        utils.createReplicateRegion(name);
      }
    });
  }

  public static void closeServer(VM server, final QueryTestUtils utils) {
    server.invoke(new SerializableRunnable("Closing Cache Server") {
      private static final long serialVersionUID = 1L;

      public void run() throws CacheException {
        utils.getLogger().fine("### Close Cache Server. ###");
        utils.closeCache();
      }
    });
  }

  /*
   * Tests that the message component of the exception is not null
   */
  public void testIndexInvalidDueToExpressionOnPartitionedRegion() throws Exception {
    Host host = Host.getHost(0);
    createPartitionRegion("examplePartitionedRegion", Portfolio.class,
        vm0, utils);

    vm0.invoke(new CacheSerializableRunnable("Putting values") {
      public void run2() {
        utils.createValuesStringKeys("examplePartitionedRegion", 100);
      }
    });
    try {
      createIndex(vm0, "partitionedIndex", "\"albs\"",
          "/examplePartitionedRegion", utils);
    }
    catch (Exception e) {
      //expected
      assertTrue(e.getCause().toString().contains("albs"));
    }
  }
  

  public void testCompactRangeIndexForIndexElemArray() throws Exception{
    doPut(200);// around 66 entries for a key in the index (< 100 so does not create a ConcurrentHashSet)
    doQuery();
    doUpdate(10);
    doQuery();
    doDestroy(200);
    doQuery();
    Thread.sleep(5000);
  }
  
  public void testCompactRangeIndexForConcurrentHashSet() throws Exception{
    doPut(333); //111 entries for a key in the index (> 100 so creates a ConcurrentHashSet)
    doQuery();
    doUpdate(10);
    doQuery();
    doDestroy(200);
    doQuery();
  }

  public void testNoSuchElemException() throws Exception{
    setHook();
    doPutSync(300);
    doDestroy(298);
    doQuery();
  }
  
  public void doPut(final int entries) {
     vm0.invokeAsync(new CacheSerializableRunnable("Putting values") {
      public void run2() {
        utils.createValuesStringKeys("exampleRegion", entries);
      }
    });
  }

  public void doPutSync(final int entries) {
    vm0.invoke(new CacheSerializableRunnable("Putting values") {
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

  public void doQuery() throws InterruptedException {
    final String[] qarr = {"1", "519", "181"};
    AsyncInvocation as0 = vm0.invokeAsync(new CacheSerializableRunnable("Executing query") {
      public void run2() throws CacheException {
        for (int i = 0; i < 50; i++) {
          try {
            utils.executeQueries(qarr);
          } catch (Exception e) {
            throw new CacheException(e) {
            };
          } 
        }
      }
    });
    as0.join();
    if(as0.exceptionOccurred()){
        fail("Query execution failed.", as0.getException());
    }
   
  }

  public void doDestroy(final int entries) throws Exception {
    vm0.invokeAsync(new CacheSerializableRunnable("Destroying values") {
      public void run2() {
        try {
          Thread.sleep(500);
          utils.destroyRegion("exampleRegion", entries);
        } catch (Exception e) {
          fail("Destroy failed.");
        }
      }
    });
   
  }

  public void tearDown2() throws Exception{
    Thread.sleep(5000);
    removeHook();
    closeServer(vm0, utils);
  }

  public void setHook(){
    vm0.invoke(new CacheSerializableRunnable("Setting hook") {
      public void run2() {
        IndexManager.testHook = new CompactRangeIndexTestHook();
      }
    });
  }
  
  public void removeHook(){
    vm0.invoke(new CacheSerializableRunnable("Removing hook") {
      public void run2() {
        IndexManager.testHook = null;
      }
    });
  }
  
  
  private static class CompactRangeIndexTestHook implements TestHook{
    @Override
    public void hook(int spot) throws RuntimeException {
     if(spot == 11){
         pause(10);
     }
   }
  }
  
}
