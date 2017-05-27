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
/**
 * This test verifies that stats are collected properly for the SingleNode and Single PartitionedRegion
 *
 */
package com.gemstone.gemfire.internal.cache;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.Statistics;
import com.gemstone.gemfire.cache.PartitionedRegionStorageException;
import com.gemstone.gemfire.cache.*;

import junit.framework.TestCase;

/**
 * @author tapshank, Created on Apr 13, 2006
 *  
 */
public class PartitionedRegionStatsJUnitTest extends TestCase
{
  LogWriter logger = null;

  /**
   * Constructor fot the PartitionedRegionStatsJUnitTest
   * 
   * @param str
   */
  public PartitionedRegionStatsJUnitTest(String str) {
    super(str);
    logger = PartitionedRegionTestHelper.getLogger();
  }

  private PartitionedRegion createPR(String name, int lmax, int redundancy) {
    PartitionAttributesFactory paf = new PartitionAttributesFactory();
    paf
      .setLocalMaxMemory(lmax)
      .setRedundantCopies(redundancy)
      .setTotalNumBuckets(13); // set low to reduce logging
    AttributesFactory af = new AttributesFactory();
    af.setPartitionAttributes(paf.create());
    Cache cache = PartitionedRegionTestHelper.createCache();
    PartitionedRegion pr = null;
    try {
      pr = (PartitionedRegion)cache.createRegion(name, af.create());
    }
    catch (RegionExistsException rex) {
      pr = (PartitionedRegion)cache.getRegion(name);
    }    
    return pr;
  }
    
  /**
   * This test verifies that PR statistics are working properly for
   * single/multiple PartitionedRegions on single node.
   * 
   * @throws Exception
   */
  public void testStats() throws Exception
  {
    String regionname = "testStats";
    int localMaxMemory = 100;
    PartitionedRegion pr = createPR(regionname + 1, localMaxMemory, 0);
    validateStats(pr);
    pr = createPR(regionname + 2, localMaxMemory, 0);
    validateStats(pr);

    if (logger.fineEnabled()) {
      logger
          .fine("PartitionedRegionStatsJUnitTest -  testStats() Completed successfully ... ");
    }
  }

  /**
   * This method verifies that PR statistics are working properly for a
   * PartitionedRegion. putsCompleted, getsCompleted, createsCompleted,
   * destroysCompleted, containsKeyCompleted, containsValueForKeyCompleted,
   * invalidatesCompleted, totalBucketSize
   * and temporarily commented avgRedundantCopies,
   * maxRedundantCopies, minRedundantCopies are validated in this method.
   */
  private void validateStats(PartitionedRegion pr) throws Exception  {
    Statistics stats = pr.getPrStats().getStats();
    int bucketCount = stats.get("bucketCount").intValue();
    int putsCompleted = stats.get("putsCompleted").intValue();
    int totalBucketSize = stats.get("dataStoreEntryCount").intValue();
    
    assertEquals(0, bucketCount);
    assertEquals(0, putsCompleted);
    assertEquals(0, totalBucketSize);
    int totalGets = 0;
    
    final int bucketMax = pr.getTotalNumberOfBuckets();
    for (int i = 0; i < bucketMax + 1; i++) {
      Long val = new Long(i);
      try {
        pr.put(val, val);
      }
      catch (PartitionedRegionStorageException ex) {
        this.logger.warning(ex);
      }
    }
    for (int i = 0; i < bucketMax + 1; i++) {
      Long val = new Long(i);
      try {
        pr.get(val);
        totalGets++;
      }
      catch (PartitionedRegionStorageException ex) {
        this.logger.warning(ex);
      }
    }


    bucketCount = stats.get("bucketCount").intValue();
    putsCompleted = stats.get("putsCompleted").intValue();
    totalBucketSize = stats.get("dataStoreEntryCount").intValue();
    
    assertEquals(bucketMax, bucketCount);
    assertEquals(bucketMax+1, putsCompleted);
    assertEquals(bucketMax+1, totalBucketSize);
    
    pr.destroy(new Long(bucketMax));

    putsCompleted = stats.get("putsCompleted").intValue();
    totalBucketSize = stats.get("dataStoreEntryCount").intValue();
    
    assertEquals(bucketMax, bucketCount);
    assertEquals(bucketMax+1, putsCompleted);
    assertEquals(bucketMax, totalBucketSize);
    
    for (int i = 200; i < 210; i++) {
      Long key = new Long(i);
      String val = "" + i;
      try {
        pr.create(key, val);
      }
      catch (PartitionedRegionStorageException ex) {
        this.logger.warning(ex);
      }
    }
    for (int i = 200; i < 210; i++) {
      Long key = new Long(i);
      try {
        pr.get(key);
        totalGets++; 
      }
      catch (PartitionedRegionStorageException ex) {
        this.logger.warning(ex);
      }
    }


    for (int i = 200; i < 210; i++) {
      Long key = new Long(i);
      try {
        pr.containsKey(key);
      }
      catch (PartitionedRegionStorageException ex) {
        this.logger.warning(ex);
      }
    }

    for (int i = 200; i < 210; i++) {
      Long key = new Long(i);
      try {
        pr.containsValueForKey(key);
      }
      catch (PartitionedRegionStorageException ex) {
        this.logger.warning(ex);
      }
    }

    for (int i = 200; i < 210; i++) {
      Long key = new Long(i);
      try {
        pr.invalidate(key);
      }
      catch (PartitionedRegionStorageException ex) {
        this.logger.warning(ex);
      }
    }
    int getsCompleted = stats.get("getsCompleted").intValue();
    int createsCompleted = stats.get("createsCompleted").intValue();
    int containsKeyCompleted = stats.get("containsKeyCompleted").intValue();
    int containsValueForKeyCompleted = stats.get(
    "containsValueForKeyCompleted").intValue();
    int invalidatesCompleted = stats.get("invalidatesCompleted").intValue();
    int destroysCompleted = stats.get("destroysCompleted").intValue();

    assertEquals(totalGets, getsCompleted);
    assertEquals(10, createsCompleted);
    assertEquals(10, containsKeyCompleted);
    assertEquals(10, containsValueForKeyCompleted);
    assertEquals(10, invalidatesCompleted);
    assertEquals(1, destroysCompleted);

    // Redundant copies related statistics
    /*
     * int maxRedundantCopies = stats.get("maxRedundantCopies").intValue();
     * int minRedundantCopies = stats.get("minRedundantCopies").intValue();
     * int avgRedundantCopies = stats.get("avgRedundantCopies").intValue();
     * 
     * assertEquals(minRedundantCopies, 2); assertEquals(maxRedundantCopies,
     * 2); assertEquals(avgRedundantCopies, 2);
     */
  }
}
