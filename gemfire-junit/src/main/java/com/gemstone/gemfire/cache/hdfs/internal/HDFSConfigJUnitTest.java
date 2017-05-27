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

package com.gemstone.gemfire.cache.hdfs.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.cache.asyncqueue.internal.AsyncEventQueueImpl;
import com.gemstone.gemfire.cache.hdfs.HDFSEventQueueAttributesFactory;
import com.gemstone.gemfire.cache.hdfs.HDFSStore;
import com.gemstone.gemfire.cache.hdfs.HDFSStore.HDFSCompactionConfig;
import com.gemstone.gemfire.cache.hdfs.HDFSStoreFactory;
import com.gemstone.gemfire.cache.hdfs.HDFSStoreFactory.HDFSCompactionConfigFactory;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.AbstractHoplogOrganizer;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.control.HeapMemoryMonitor;
import io.snappydata.test.dunit.VM;
import junit.framework.TestCase;
import org.apache.hadoop.hbase.regionserver.StoreFile;

/**
 * A test class for testing the configuration option for HDFS 
 * 
 * @author Hemant Bhanawat
 * @author Ashvin Agrawal
 */
public class HDFSConfigJUnitTest extends TestCase {
  private GemFireCacheImpl c;

  public HDFSConfigJUnitTest() {
    super();
  }

  @Override
  public void setUp() {
    // make it a loner 
    System.setProperty(HDFSStoreImpl.ALLOW_STANDALONE_HDFS_FILESYSTEM_PROP, "true");
    this.c = createCache();
    AbstractHoplogOrganizer.JUNIT_TEST_RUN = true;
  }

  @Override
  public void tearDown() {
    this.c.close();
  }
    
    public void testHDFSStoreCreation() throws Exception {
      this.c.close();
      this.c = createCache();
      try {
        HDFSStoreFactory hsf = this.c.createHDFSStoreFactory();
        HDFSStore store = hsf.create("myHDFSStore");
        RegionFactory rf1 = this.c.createRegionFactory(RegionShortcut.PARTITION_HDFS);
        Region r1 = rf1.setHDFSStoreName("myHDFSStore").create("r1");
       
        r1.put("k1", "v1");
        
        assertTrue("Mismatch in attributes, actual.batchsize: " + store.getHDFSEventQueueAttributes().getBatchSizeMB() + " and expected batchsize: 32", store.getHDFSEventQueueAttributes().getBatchSizeMB()== 32);
        assertTrue("Mismatch in attributes, actual.isPersistent: " + store.getHDFSEventQueueAttributes().isPersistent() + " and expected isPersistent: false", store.getHDFSEventQueueAttributes().isPersistent()== false);
        assertEquals(false, r1.getAttributes().getHDFSWriteOnly());
        assertTrue("Mismatch in attributes, actual.getDiskStoreName: " + store.getHDFSEventQueueAttributes().getDiskStoreName() + " and expected getDiskStoreName: null", store.getHDFSEventQueueAttributes().getDiskStoreName()== null);
        assertTrue("Mismatch in attributes, actual.getFileRolloverInterval: " + store.getFileRolloverInterval() + " and expected getFileRolloverInterval: 3600", store.getFileRolloverInterval() == 3600);
        assertTrue("Mismatch in attributes, actual.getMaxFileSize: " + store.getMaxFileSize() + " and expected getMaxFileSize: 256MB", store.getMaxFileSize() == 256);
        this.c.close();
        
        
        this.c = createCache();
        hsf = this.c.createHDFSStoreFactory();
        hsf.create("myHDFSStore");
        
        r1 = this.c.createRegionFactory(RegionShortcut.PARTITION_WRITEONLY_HDFS_STORE).setHDFSStoreName("myHDFSStore")
              .create("r1");
       
        r1.put("k1", "v1");
        assertTrue("Mismatch in attributes, actual.batchsize: " + store.getHDFSEventQueueAttributes().getBatchSizeMB() + " and expected batchsize: 32", store.getHDFSEventQueueAttributes().getBatchSizeMB()== 32);
        assertTrue("Mismatch in attributes, actual.isPersistent: " + store.getHDFSEventQueueAttributes().isPersistent() + " and expected isPersistent: false", store.getHDFSEventQueueAttributes().isPersistent()== false);
        assertTrue("Mismatch in attributes, actual.isRandomAccessAllowed: " + r1.getAttributes().getHDFSWriteOnly() + " and expected isRandomAccessAllowed: true", r1.getAttributes().getHDFSWriteOnly()== true);
        assertTrue("Mismatch in attributes, actual.getDiskStoreName: " + store.getHDFSEventQueueAttributes().getDiskStoreName() + " and expected getDiskStoreName: null", store.getHDFSEventQueueAttributes().getDiskStoreName()== null);
        assertTrue("Mismatch in attributes, actual.batchInterval: " + store.getHDFSEventQueueAttributes().getBatchTimeInterval() + " and expected batchsize: 60000", store.getHDFSEventQueueAttributes().getBatchTimeInterval()== 60000);
        assertTrue("Mismatch in attributes, actual.isDiskSynchronous: " + store.getHDFSEventQueueAttributes().isDiskSynchronous() + " and expected isDiskSynchronous: true", store.getHDFSEventQueueAttributes().isDiskSynchronous()== true);
        
        this.c.close();

        this.c = createCache();
        
        File directory = new File("HDFS" + "_disk_"
            + System.currentTimeMillis() + "_" + VM.getCurrentVMNum());
        directory.mkdir();
        File[] dirs1 = new File[] { directory };
        DiskStoreFactory dsf = this.c.createDiskStoreFactory();
        dsf.setDiskDirs(dirs1);
        DiskStore diskStore = dsf.create("mydisk");
        
        HDFSEventQueueAttributesFactory hqf= new HDFSEventQueueAttributesFactory();
        hqf.setBatchSizeMB(50);
        hqf.setDiskStoreName("mydisk");
        hqf.setPersistent(true);
        hqf.setBatchTimeInterval(50);
        hqf.setDiskSynchronous(false);
        
        hsf = this.c.createHDFSStoreFactory();
        hsf.setHomeDir("/home/hemant");
        hsf.setNameNodeURL("mymachine");
        hsf.setHDFSEventQueueAttributes(hqf.create());
        hsf.setMaxFileSize(1);
        hsf.setFileRolloverInterval(10);
        hsf.create("myHDFSStore");
        
        
        r1 = this.c.createRegionFactory(RegionShortcut.PARTITION_WRITEONLY_HDFS_STORE).setHDFSStoreName("myHDFSStore")
            .setHDFSWriteOnly(true).create("r1");
       
        r1.put("k1", "v1");
        store = c.findHDFSStore(r1.getAttributes().getHDFSStoreName());
        
        assertTrue("Mismatch in attributes, actual.batchsize: " + store.getHDFSEventQueueAttributes().getBatchSizeMB() + " and expected batchsize: 50", store.getHDFSEventQueueAttributes().getBatchSizeMB()== 50);
        assertTrue("Mismatch in attributes, actual.isPersistent: " + store.getHDFSEventQueueAttributes().isPersistent() + " and expected isPersistent: true", store.getHDFSEventQueueAttributes().isPersistent()== true);
        assertTrue("Mismatch in attributes, actual.isRandomAccessAllowed: " + r1.getAttributes().getHDFSWriteOnly() + " and expected isRandomAccessAllowed: true", r1.getAttributes().getHDFSWriteOnly()== true);
        assertTrue("Mismatch in attributes, actual.getDiskStoreName: " + store.getHDFSEventQueueAttributes().getDiskStoreName() + " and expected getDiskStoreName: mydisk", store.getHDFSEventQueueAttributes().getDiskStoreName()== "mydisk");
        assertTrue("Mismatch in attributes, actual.HDFSStoreName: " + r1.getAttributes().getHDFSStoreName() + " and expected getDiskStoreName: myHDFSStore", r1.getAttributes().getHDFSStoreName()== "myHDFSStore");
        assertTrue("Mismatch in attributes, actual.getFolderPath: " + ((GemFireCacheImpl)this.c).findHDFSStore("myHDFSStore").getHomeDir() + " and expected getDiskStoreName: /home/hemant", ((GemFireCacheImpl)this.c).findHDFSStore("myHDFSStore").getHomeDir()== "/home/hemant");
        assertTrue("Mismatch in attributes, actual.getNamenode: " + ((GemFireCacheImpl)this.c).findHDFSStore("myHDFSStore").getNameNodeURL()+ " and expected getDiskStoreName: mymachine", ((GemFireCacheImpl)this.c).findHDFSStore("myHDFSStore").getNameNodeURL()== "mymachine");
        assertTrue("Mismatch in attributes, actual.batchInterval: " + store.getHDFSEventQueueAttributes().getBatchTimeInterval() + " and expected batchsize: 50 ", store.getHDFSEventQueueAttributes().getBatchSizeMB()== 50);
        assertTrue("Mismatch in attributes, actual.isDiskSynchronous: " + store.getHDFSEventQueueAttributes().isDiskSynchronous() + " and expected isPersistent: false", store.getHDFSEventQueueAttributes().isDiskSynchronous()== false);
        assertTrue("Mismatch in attributes, actual.getFileRolloverInterval: " + store.getFileRolloverInterval() + " and expected getFileRolloverInterval: 10", store.getFileRolloverInterval() == 10);
        assertTrue("Mismatch in attributes, actual.getMaxFileSize: " + store.getMaxFileSize() + " and expected getMaxFileSize: 1MB", store.getMaxFileSize() == 1);
        this.c.close();
      } finally {
        this.c.close();
      }
    }
       
    public void testCacheXMLParsing() throws Exception {
      try {
        this.c.close();

        Region r1 = null;

        // use a cache.xml to recover
        this.c = createCache();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(baos), true);
        pw.println("<?xml version=\"1.0\"?>");
        pw.println("<!DOCTYPE cache PUBLIC");
        pw.println("  \"-//GemStone Systems, Inc.//GemFire Declarative Caching 7.5//EN\"");
        pw.println("  \"http://www.gemstone.com/dtd/cache7_5.dtd\">");
        pw.println("<cache>");
        pw.println("  <hdfs-store name=\"myHDFSStore\" namenode-url=\"mynamenode\"  home-dir=\"mypath\" />");
        pw.println("  <region name=\"r1\" refid=\"PARTITION_HDFS\">");
        pw.println("    <region-attributes hdfs-store-name=\"myHDFSStore\"/>");
        pw.println("  </region>");
        pw.println("</cache>");
        pw.close();
        byte[] bytes = baos.toByteArray();  
        this.c.loadCacheXml(new ByteArrayInputStream(bytes));
        
        r1 = this.c.getRegion("/r1");
        HDFSStoreImpl store = c.findHDFSStore(r1.getAttributes().getHDFSStoreName());
        r1.put("k1", "v1");
        assertTrue("Mismatch in attributes, actual.batchsize: " + store.getHDFSEventQueueAttributes().getBatchSizeMB() + " and expected batchsize: 32", store.getHDFSEventQueueAttributes().getBatchSizeMB()== 32);
        assertTrue("Mismatch in attributes, actual.isPersistent: " + store.getHDFSEventQueueAttributes().isPersistent() + " and expected isPersistent: false", store.getHDFSEventQueueAttributes().isPersistent()== false);
        assertEquals(false, r1.getAttributes().getHDFSWriteOnly());
        assertTrue("Mismatch in attributes, actual.getDiskStoreName: " + store.getHDFSEventQueueAttributes().getDiskStoreName() + " and expected getDiskStoreName: null", store.getHDFSEventQueueAttributes().getDiskStoreName()== null);
        assertTrue("Mismatch in attributes, actual.getFileRolloverInterval: " + store.getFileRolloverInterval() + " and expected getFileRolloverInterval: 3600", store.getFileRolloverInterval() == 3600);
        assertTrue("Mismatch in attributes, actual.getMaxFileSize: " + store.getMaxFileSize() + " and expected getMaxFileSize: 256MB", store.getMaxFileSize() == 256);
        
        this.c.close();
        
        // use a cache.xml to recover
        this.c = createCache();
        baos = new ByteArrayOutputStream();
        pw = new PrintWriter(new OutputStreamWriter(baos), true);
        pw.println("<?xml version=\"1.0\"?>");
        pw.println("<!DOCTYPE cache PUBLIC");
        pw.println("  \"-//GemStone Systems, Inc.//GemFire Declarative Caching 7.5//EN\"");
        pw.println("  \"http://www.gemstone.com/dtd/cache7_5.dtd\">");
        pw.println("<cache>");
        pw.println("  <hdfs-store name=\"myHDFSStore\" namenode-url=\"mynamenode\"  home-dir=\"mypath\" />");
        pw.println("  <region name=\"r1\" refid=\"PARTITION_WRITEONLY_HDFS_STORE\">");
        pw.println("    <region-attributes hdfs-store-name=\"myHDFSStore\"/>");
        pw.println("  </region>");
        pw.println("</cache>");
        pw.close();
        bytes = baos.toByteArray();  
        this.c.loadCacheXml(new ByteArrayInputStream(bytes));
        
        r1 = this.c.getRegion("/r1");
        store = c.findHDFSStore(r1.getAttributes().getHDFSStoreName());
        r1.put("k1", "v1");
        assertTrue("Mismatch in attributes, actual.batchsize: " + store.getHDFSEventQueueAttributes().getBatchSizeMB() + " and expected batchsize: 32", store.getHDFSEventQueueAttributes().getBatchSizeMB()== 32);
        assertTrue("Mismatch in attributes, actual.isPersistent: " + store.getHDFSEventQueueAttributes().isPersistent() + " and expected isPersistent: false", store.getHDFSEventQueueAttributes().isPersistent()== false);
        assertTrue("Mismatch in attributes, actual.isRandomAccessAllowed: " + r1.getAttributes().getHDFSWriteOnly() + " and expected isRandomAccessAllowed: false", r1.getAttributes().getHDFSWriteOnly()== false);
        assertTrue("Mismatch in attributes, actual.getDiskStoreName: " + store.getHDFSEventQueueAttributes().getDiskStoreName() + " and expected getDiskStoreName: null", store.getHDFSEventQueueAttributes().getDiskStoreName()== null);
        
        this.c.close();
        
        // use a cache.xml to recover
        this.c = createCache();
        baos = new ByteArrayOutputStream();
        pw = new PrintWriter(new OutputStreamWriter(baos), true);
        pw.println("<?xml version=\"1.0\"?>");
        pw.println("<!DOCTYPE cache PUBLIC");
        pw.println("  \"-//GemStone Systems, Inc.//GemFire Declarative Caching 7.5//EN\"");
        pw.println("  \"http://www.gemstone.com/dtd/cache7_5.dtd\">");
        pw.println("<cache>");
        pw.println("  <disk-store name=\"mydiskstore\"/>");
        pw.println("  <hdfs-store name=\"myHDFSStore\" namenode-url=\"mynamenode\"  home-dir=\"mypath\" max-file-size-mb=\"1\" file-rollover-time-secs=\"10\" >");
        pw.println("      <hdfs-event-queue batch-size-mb=\"151\" persistent =\"true\" disk-store-name=\"mydiskstore\" disk-synchronous=\"false\" batch-time-interval=\"50\" />");
        pw.println("  </hdfs-store>");
        pw.println("  <region name=\"r1\" refid=\"PARTITION_WRITEONLY_HDFS_STORE\">");
        pw.println("    <region-attributes hdfs-store-name=\"myHDFSStore\" hdfs-write-only=\"false\">");
        pw.println("    </region-attributes>");
        pw.println("  </region>");
        pw.println("</cache>");
        pw.close();
        bytes = baos.toByteArray();
        this.c.loadCacheXml(new ByteArrayInputStream(bytes));
        
        r1 = this.c.getRegion("/r1");
        store = c.findHDFSStore(r1.getAttributes().getHDFSStoreName());
        r1.put("k1", "v1");
        assertTrue("Mismatch in attributes, actual.batchsize: " + store.getHDFSEventQueueAttributes().getBatchSizeMB() + " and expected batchsize: 151", store.getHDFSEventQueueAttributes().getBatchSizeMB()== 151);
        assertTrue("Mismatch in attributes, actual.isPersistent: " + store.getHDFSEventQueueAttributes().isPersistent() + " and expected isPersistent: true", store.getHDFSEventQueueAttributes().isPersistent()== true);
        assertTrue("Mismatch in attributes, actual.isRandomAccessAllowed: " + r1.getAttributes().getHDFSWriteOnly() + " and expected isRandomAccessAllowed: true", r1.getAttributes().getHDFSWriteOnly()== false);
        assertTrue("Mismatch in attributes, actual.getDiskStoreName: " + store.getHDFSEventQueueAttributes().getDiskStoreName() + " and expected getDiskStoreName: mydiskstore", store.getHDFSEventQueueAttributes().getDiskStoreName().equals("mydiskstore"));
        assertTrue("Mismatch in attributes, actual.HDFSStoreName: " + r1.getAttributes().getHDFSStoreName() + " and expected getDiskStoreName: myHDFSStore", r1.getAttributes().getHDFSStoreName().equals("myHDFSStore"));
        assertTrue("Mismatch in attributes, actual.getFolderPath: " + ((GemFireCacheImpl)this.c).findHDFSStore("myHDFSStore").getHomeDir() + " and expected getDiskStoreName: mypath", ((GemFireCacheImpl)this.c).findHDFSStore("myHDFSStore").getHomeDir().equals("mypath"));
        assertTrue("Mismatch in attributes, actual.getNamenode: " + ((GemFireCacheImpl)this.c).findHDFSStore("myHDFSStore").getNameNodeURL()+ " and expected getDiskStoreName: mynamenode", ((GemFireCacheImpl)this.c).findHDFSStore("myHDFSStore").getNameNodeURL().equals("mynamenode"));
        assertTrue("Mismatch in attributes, actual.batchInterval: " + store.getHDFSEventQueueAttributes().getBatchTimeInterval() + " and expected batchsize: 50", store.getHDFSEventQueueAttributes().getBatchTimeInterval()== 50);
        assertTrue("Mismatch in attributes, actual.isDiskSynchronous: " + store.getHDFSEventQueueAttributes().isDiskSynchronous() + " and expected isDiskSynchronous: false", store.getHDFSEventQueueAttributes().isDiskSynchronous()== false);
        assertTrue("Mismatch in attributes, actual.getFileRolloverInterval: " + store.getFileRolloverInterval() + " and expected getFileRolloverInterval: 10", store.getFileRolloverInterval() == 10);
        assertTrue("Mismatch in attributes, actual.getMaxFileSize: " + store.getMaxFileSize() + " and expected getMaxFileSize: 1MB", store.getMaxFileSize() == 1);
        
        this.c.close();
      } finally {
          this.c.close();
      }
    }
   
  /**
   * Validates if hdfs store conf is getting complety and correctly parsed
   */
  public void testHdfsStoreConfFullParsing() {
    String conf = createStoreConf(null, "123");
    this.c.loadCacheXml(new ByteArrayInputStream(conf.getBytes()));
    HDFSStoreImpl store = ((GemFireCacheImpl)this.c).findHDFSStore("store");
    assertEquals("namenode url mismatch.", "url", store.getNameNodeURL());
    assertEquals("home-dir mismatch.", "dir", store.getHomeDir());
    assertEquals("hdfs-client-config-file mismatch.", "client", store.getHDFSClientConfigFile());
    assertEquals("block-cache-size mismatch.", 24.5f, store.getBlockCacheSize());
    
    HDFSCompactionConfig compactConf = store.getHDFSCompactionConfig();
    assertEquals("compaction strategy mismatch.", "size-oriented", compactConf.getCompactionStrategy());
    assertFalse("compaction auto-compact mismatch.", compactConf.getAutoCompaction());
    assertTrue("compaction auto-major-compact mismatch.", compactConf.getAutoMajorCompaction());
    assertEquals("compaction max-input-file-size mismatch.", 123, compactConf.getMaxInputFileSizeMB());
    assertEquals("compaction min-input-file-count.", 9, compactConf.getMinInputFileCount());
    assertEquals("compaction max-input-file-count.", 1234, compactConf.getMaxInputFileCount());
    assertEquals("compaction max-concurrency", 23, compactConf.getMaxThreads());
    assertEquals("compaction max-major-concurrency", 27, compactConf.getMajorCompactionMaxThreads());
    assertEquals("compaction major-interval", 781, compactConf.getMajorCompactionIntervalMins());
    assertEquals("compaction major-interval", 711, compactConf.getOldFilesCleanupIntervalMins());
  }
  
  /**
   * Validates that the config defaults are set even with minimum XML configuration 
   */
  public void testHdfsStoreConfMinParse() {
    this.c.loadCacheXml(new ByteArrayInputStream(XML_MIN_CONF.getBytes()));
    HDFSStoreImpl store = ((GemFireCacheImpl)this.c).findHDFSStore("store");
    assertEquals("namenode url mismatch.", "url", store.getNameNodeURL());
    assertEquals("home-dir mismatch.", "gemfire", store.getHomeDir());
    
    HDFSCompactionConfig compactConf = store.getHDFSCompactionConfig();
    assertNotNull("compaction conf should have initialized to default", compactConf);
    assertEquals("compaction strategy mismatch.", "size-oriented", compactConf.getCompactionStrategy());
    assertTrue("compaction auto-compact mismatch.", compactConf.getAutoCompaction());
    assertTrue("compaction auto-major-compact mismatch.", compactConf.getAutoMajorCompaction());
    assertEquals("compaction max-input-file-size mismatch.", 512, compactConf.getMaxInputFileSizeMB());
    assertEquals("compaction min-input-file-count.", 4, compactConf.getMinInputFileCount());
    assertEquals("compaction max-iteration-size.", 10, compactConf.getMaxInputFileCount());
    assertEquals("compaction max-concurrency", 10, compactConf.getMaxThreads());
    assertEquals("compaction max-major-concurrency", 2, compactConf.getMajorCompactionMaxThreads());
    assertEquals("compaction major-interval", 720, compactConf.getMajorCompactionIntervalMins());
    assertEquals("compaction cleanup-interval", 30, compactConf.getOldFilesCleanupIntervalMins());
  }
  
  /**
   * Validates that cache creation fails if a compaction configuration is
   * provided which is not applicable to the selected compaction strategy
   */
  public void testHdfsStoreInvalidCompactionConf() {
    String conf = createStoreConf("dummy", "123");
    try {
      this.c.loadCacheXml(new ByteArrayInputStream(conf.getBytes()));
      fail();
    } catch (CacheXmlException e) {
      // expected
    }
  }
  
  /**
   * Validates that cache creation fails if a compaction configuration is
   * provided which is not applicable to the selected compaction strategy
   */
  public void testInvalidConfigCheck() throws Exception {
    this.c.close();

    this.c = createCache();

    HDFSStoreFactory hsf; 
    hsf = this.c.createHDFSStoreFactory();
    HDFSCompactionConfigFactory ccsf = hsf.createCompactionConfigFactory(null);
    
    try {
      ccsf.setMaxInputFileSizeMB(-1);
      fail("validation failed");
    } catch (IllegalArgumentException e) {
      //expected
    }
    try {
      ccsf.setMinInputFileCount(-1);
      fail("validation failed");
    } catch (IllegalArgumentException e) {
      //expected
    }
    try {
      ccsf.setMaxInputFileCount(-1);
      //expected
      fail("validation failed");
    } catch (IllegalArgumentException e) {
    }
    try {
      ccsf.setMaxThreads(-1);
      fail("validation failed");
    } catch (IllegalArgumentException e) {
      //expected
    }
    try {
      ccsf.setMajorCompactionIntervalMins(-1);
      fail("validation failed");
    } catch (IllegalArgumentException e) {
      //expected
    }
    try {
      ccsf.setMajorCompactionMaxThreads(-1);
      fail("validation failed");
    } catch (IllegalArgumentException e) {
      //expected
    }
    try {
      ccsf.setOldFilesCleanupIntervalMins(-1);
      fail("validation failed");
    } catch (IllegalArgumentException e) {
      //expected
    }
    try {
      ccsf.setMinInputFileCount(2);
      ccsf.setMaxInputFileCount(1);
      ccsf.create();
      fail("validation failed");
    } catch (IllegalArgumentException e) {
      //expected
    }
    try {
      ccsf.setMaxInputFileCount(1);
      ccsf.setMinInputFileCount(2);
      ccsf.create();
      fail("validation failed");
    } catch (IllegalArgumentException e) {
      //expected
    }
  }
  
  /**
   * Validates cache creation fails if invalid integer size configuration is provided
   * @throws Exception
   */
  public void testHdfsStoreConfInvalidInt() throws Exception {
    String conf = createStoreConf(null, "NOT_INTEGER");
    try {
      this.c.loadCacheXml(new ByteArrayInputStream(conf.getBytes()));
      fail();
    } catch (CacheXmlException e) {
      // expected
    }
  }
  
  private static String XML_MIN_CONF = 
          "<!DOCTYPE cache PUBLIC" +
          "  \"-//GemStone Systems, Inc.//GemFire Declarative Caching 7.5//EN\"" +
          "  \"http://www.gemstone.com/dtd/cache7_5.dtd\">\n" +
          "<cache>" + 
          "  <hdfs-store name=\"store\" namenode-url=\"url\">" +
          "  </hdfs-store>" + 
          "</cache>";
  
  private static String XML_FULL_CONF = "<!DOCTYPE cache PUBLIC"
      + "  \"-//GemStone Systems, Inc.//GemFire Declarative Caching 7.5//EN\""
      + "  \"http://www.gemstone.com/dtd/cache7_5.dtd\">\n"
      + "<cache>\n"
      + "  <hdfs-store name=\"store\" namenode-url=\"url\" "
      + "              home-dir=\"dir\" "
      + "              block-cache-size=\"24.5\" "
      + "              hdfs-client-config-file=\"client\">\n"
      + "    <hdfs-compaction compaction-strategy=\"STRATEGY\" auto-compaction=\"false\" \n"
      + "                   max-input-file-size-mb=\"FILE_SIZE_CONF\"\n"
      + "                   min-input-file-count=\"9\" max-input-file-count=\"1234\" \n"
      + "                   max-threads=\"23\" auto-major-compaction=\"true\" \n"
      + "                   major-compaction-interval-mins=\"781\" major-compaction-max-threads=\"27\"\n"
      + "                   old-files-cleanup-interval-mins=\"711\"                           \n"
      + "    />\n" + "  </hdfs-store>\n" + "</cache>";
  // potential replacement targets
  String STRATEGY_SUBSTRING = "STRATEGY";
  String FILE_SIZE_CONF_SUBSTRING = "FILE_SIZE_CONF";
  
  private String createStoreConf(String strategy, String fileSize) {
    String result = XML_FULL_CONF;
    
    String replaceWith = (strategy == null) ? "size-oriented" : strategy;
    result = result.replaceFirst(STRATEGY_SUBSTRING, replaceWith);

    replaceWith = (fileSize == null) ? "123" : fileSize;
    result = result.replaceFirst(FILE_SIZE_CONF_SUBSTRING, replaceWith);

    return result;
  }
  
  public void testBlockCacheConfiguration() throws Exception {
    this.c.close();
    this.c = createCache();
    try {
      HDFSStoreFactory hsf = this.c.createHDFSStoreFactory();
      
      //Configure a block cache to cache about 20 blocks.
      long heapSize = HeapMemoryMonitor.getTenuredPoolMaxMemory();
      int blockSize = StoreFile.DEFAULT_BLOCKSIZE_SMALL;
      int blockCacheSize = 5 * blockSize;
      int entrySize = blockSize / 2;
      
      
      float percentage = 100 * (float) blockCacheSize / (float) heapSize;
      hsf.setBlockCacheSize(percentage);
      HDFSStoreImpl store = (HDFSStoreImpl) hsf.create("myHDFSStore");
      RegionFactory rf1 = this.c.createRegionFactory(RegionShortcut.PARTITION_HDFS);
      //Create a region that evicts everything
      HDFSEventQueueAttributesFactory heqf = new HDFSEventQueueAttributesFactory();
      heqf.setBatchTimeInterval(10);
      LocalRegion r1 = (LocalRegion) rf1.setHDFSStoreName("myHDFSStore").setEvictionAttributes(EvictionAttributes.createLRUEntryAttributes(1)).create("r1");
     
      //Populate about many times our block cache size worth of data
      //We want to try to cache at least 5 blocks worth of index and metadata
      byte[] value = new byte[entrySize];
      int numEntries = 10 * blockCacheSize / entrySize;
      for(int i = 0; i < numEntries; i++) {
        r1.put(i, value);
      }

      //Wait for the events to be written to HDFS.
      Set<String> queueIds = r1.getAsyncEventQueueIds();
      assertEquals(1, queueIds.size());
      AsyncEventQueueImpl queue = (AsyncEventQueueImpl) c.getAsyncEventQueue(queueIds.iterator().next());
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
      while(queue.size() > 0 && System.nanoTime() < end) {
        Thread.sleep(10);
      }
      assertEquals(0, queue.size());
      
      
      Thread.sleep(10000);

      //Do some reads to cache some blocks. Note that this doesn't
      //end up caching data blocks, just index and bloom filters blocks.
      for(int i = 0; i < numEntries; i++) {
        r1.get(i);
      }
      
      long statSize = store.getStats().getBlockCache().getBytesCached();
      assertTrue("Block cache stats expected to be near " + blockCacheSize + " was " + statSize, 
          blockCacheSize / 2  < statSize &&
          statSize <=  2 * blockCacheSize);
      
      long currentSize = store.getBlockCache().getCurrentSize();
      assertTrue("Block cache size expected to be near " + blockCacheSize + " was " + currentSize, 
          blockCacheSize / 2  < currentSize &&
          currentSize <= 2 * blockCacheSize);
      
    } finally {
      this.c.close();
    }
  }

  protected GemFireCacheImpl createCache() {
    return (GemFireCacheImpl) new CacheFactory().set("mcast-port", "0").set("log-level", "info")
    .create();
  }
}
