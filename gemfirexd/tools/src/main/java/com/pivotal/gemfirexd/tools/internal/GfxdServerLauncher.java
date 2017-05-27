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

package com.pivotal.gemfirexd.tools.internal;

import java.io.Console;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.control.ResourceManager;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.AbstractDistributionConfig;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.distributed.internal.DistributionConfigImpl;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.ByteArrayDataInput;
import com.gemstone.gemfire.internal.HeapDataOutputStream;
import com.gemstone.gemfire.internal.SocketCreator;
import com.gemstone.gemfire.internal.cache.CacheServerLauncher;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.control.HeapMemoryMonitor;
import com.gemstone.gemfire.internal.cache.lru.HeapEvictor;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberID;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.lang.StringUtils;
import com.gemstone.gemfire.internal.shared.NativeCalls;
import com.gemstone.gnu.trove.THashSet;
import com.pivotal.gemfirexd.FabricServer;
import com.pivotal.gemfirexd.FabricService;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.fabricservice.FabricServiceUtils;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.tools.i18n.LocalizedResource;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import com.pivotal.gemfirexd.tools.GfxdUtilLauncher;
import scala.tools.jline.console.ConsoleReader;

/**
 * Launcher class for the GFXD server script. This extends
 * {@link CacheServerLauncher} class and initializes Gemfire using JDBC
 * {@link Connection} class.
 * 
 * @author Sumedh Wale
 */
public class GfxdServerLauncher extends CacheServerLauncher {

  protected static final String HEAP_SIZE = "heap-size";
  protected static final String OFF_HEAP_SIZE = "off-heap-size";
  
  //There attributes are no longer supported. They get populated in deprecatedAttributes map.
  protected static final String INITIAL_HEAP = "initial-heap";
  protected static final String MAX_HEAP = "max-heap";
  protected static final long DEFAULT_HEAPSIZE_GB = 4L;
  protected static final long DEFAULT_HEAPSIZE_SMALL_GB = 2L;

  protected static final String RUN_NETSERVER = "run-netserver";
  protected static final String LWC_BIND_ADDRESS_ARG = "client-bind-address";
  protected static final String LWC_PORT_ARG = "client-port";
  protected static final String WAIT_FOR_SYNC = "sync";
  // Thrift Related
  protected static final String THRIFT_SERVER_ADDRESS = "thrift-server-address";
  protected static final String THRIFT_SERVER_PORT = "thrift-server-port";

  // key bytes used for encrypting the environment variable
  private static final byte[] ENV_B1 = new byte[] { 0x3e, (byte)0xee,
      (byte)0x88, (byte)0xef };
  private static final byte[] ENV_B2 = new byte[] { 0x1c, (byte)0xec, 0x37,
      0x4a };
  // environment variable used for passing password
  private static final String ENV1 = "env_1";
  private static final byte[] ENV_B3 = new byte[] { (byte)0xe6, 0x2e,
      (byte)0xd6, (byte)0xc3 };
  private static final byte[] ENV_B4 = new byte[] { 0x5d, (byte)0x82, 0x74,
      (byte)0xbe };
  // environment variable used for passing full command-line
  private static final String ENV2 = "env_2";

  /** Should the launch command be printed? */
  private static final boolean PRINT_LAUNCH_COMMAND = Boolean
      .getBoolean(GfxdServerLauncher.class.getSimpleName()
          + ".PRINT_LAUNCH_COMMAND");

  protected final String jvmVendor;

  protected Properties bootProps;

  /** wait for startup to complete, or exit once region GII wait begins */
  protected boolean waitForData;
  
  private HashMap<String, String> deprecatedAttributes = new HashMap<String, String>(); 

  public GfxdServerLauncher(String baseName) {
    super(baseName);
    GemFireCacheImpl.setGFXDSystem(true);
    this.jvmVendor = System.getProperty("java.vendor");

    // use GemFireXD prefixed properties for timeout
    STATUS_WAIT_TIME = Long.getLong("gemfirexd.launcher.STATUS_WAIT_TIME_MS",
        15000);
    /** How long to wait for a cache server to stop */
    SHUTDOWN_WAIT_TIME = Long.getLong("gemfirexd.launcher.SHUTDOWN_WAIT_TIME_MS",
        20000);

    // don't wait for diskstore sync by default and instead let the server go
    // into WAITING state
    this.waitForData = false;
    populateDeprecatedAttributes();
  }
  
  /**
   * When an attribute is no longer supported, add it to this map (as key) along 
   * with the new attribute (as value) that replaced the old attribute. 
   */
  private void populateDeprecatedAttributes() {
	  deprecatedAttributes.put(INITIAL_HEAP, HEAP_SIZE);
	  deprecatedAttributes.put(MAX_HEAP, HEAP_SIZE);
  }

  /**
   * Prints usage information of this program.
   */
  @Override
  protected void usage() throws IOException {
    final String script = LocalizedResource.getMessage("FS_SCRIPT");
    final String name = LocalizedResource.getMessage("FS_NAME");
    final String usageOutput = LocalizedResource.getMessage("SERVER_HELP",
        script, name, LocalizedResource.getMessage("FS_ADDRESS_ARG"),
        LocalizedResource.getMessage("FS_EXTRA_HELP" ,  LocalizedResource.getMessage("FS_PRODUCT")));
    printUsage(usageOutput, SanityManager.DEFAULT_MAX_OUT_LINES);
  }

  protected final void printUsage(final String usageOutput, int maxLines)
      throws IOException {
    final ConsoleReader reader = GfxdUtilLauncher.getConsoleReader();
    GfxdUtilLauncher.printUsage(usageOutput, maxLines, reader);
  }

  @Override
  protected InternalDistributedSystem connect(Properties props,
      Map<String, Object> options) {
    props = processProperties(props, options, this.defaultLogFileName);

    try {
      startServerVM(props);
    } catch (Exception ex) {
      RuntimeException rte = new RuntimeException(ex);
      rte.setStackTrace(new StackTraceElement[0]);
      throw rte;
    }
    return InternalDistributedSystem.getConnectedInstance();
  }

  public static Properties processProperties(Properties props,
      Map<String, Object> options, final String defaultLogFileName) {
    if (props == null) {
      props = new Properties();
    }
    final boolean hasLocators = props
        .containsKey(DistributionConfig.LOCATORS_NAME)
        || props.containsKey(DistributionConfig.START_LOCATOR_NAME);
    final boolean hasMcastPort = props
        .containsKey(DistributionConfig.MCAST_PORT_NAME)
        || props.containsKey(DistributionConfig.MCAST_ADDRESS_NAME);
    // For server set mcast-port to zero by default if locators have been
    // specified.
    if (hasLocators && !hasMcastPort) {
      props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    }
    if (!props.containsKey(com.pivotal.gemfirexd.Attribute.GFXD_HOST_DATA)) {
      props.setProperty(com.pivotal.gemfirexd.Attribute.GFXD_HOST_DATA, "true");
    }
    String gfxdLogFile = props.getProperty(GfxdConstants.GFXD_LOG_FILE);
    if (gfxdLogFile == null || gfxdLogFile.length() == 0) {
      gfxdLogFile = props.getProperty(DistributionConfig.LOG_FILE_NAME);
      if (gfxdLogFile == null || gfxdLogFile.length() == 0) {
        if (defaultLogFileName != null && defaultLogFileName.length() > 0) {
          props.setProperty(DistributionConfig.LOG_FILE_NAME,
              defaultLogFileName);
          gfxdLogFile = defaultLogFileName;
        }
        else {
          gfxdLogFile = null;
        }
      }
    }
    if (gfxdLogFile != null) {
      System.setProperty(GfxdConstants.GFXD_LOG_FILE, gfxdLogFile);
    }
    // set the critical/eviction heap percentage if provided
    float threshold = getCriticalHeapPercent(options);
    if (threshold > 0.0f) {
      props.setProperty(CRITICAL_HEAP_PERCENTAGE, Float.toString(threshold));
    }
    threshold = getEvictionHeapPercent(options);
    if (threshold > 0.0f) {
      props.setProperty(EVICTION_HEAP_PERCENTAGE, Float.toString(threshold));
    }
    // set the critical/eviction off-heap percentage if provided
    float thresholdOffHeap = getCriticalOffHeapPercent(options);
    if (thresholdOffHeap > 0.0f) {
      props.setProperty(CRITICAL_OFF_HEAP_PERCENTAGE, Float.toString(thresholdOffHeap));
    }
    thresholdOffHeap = getEvictionOffHeapPercent(options);
    if (thresholdOffHeap > 0.0f) {
      props.setProperty(EVICTION_OFF_HEAP_PERCENTAGE, Float.toString(thresholdOffHeap));
    }
    
    return props;
  }

  /** do the actual startup of the GemFireXD VM */
  protected void startServerVM(Properties props) throws Exception {
    ((FabricServer)getFabricServiceInstance()).start(props);
    this.bootProps = props;
  }

  protected FabricService getFabricServiceInstance() throws Exception {
    return (FabricService)Class
        .forName("com.pivotal.gemfirexd.FabricServiceManager")
        .getMethod("getFabricServerInstance").invoke(null);
  }

  @Override
  protected Cache createCache(InternalDistributedSystem system,
      Map<String, Object> options) {
    // Cache is already created for GemFireXD
    Cache cache = CacheFactory.getInstance(system);
    return cache;
  }

  @Override
  protected void disconnect(Cache cache) {
    Exception severeEx = null;
    try {
      getFabricServiceInstance().stop(this.bootProps);
    } catch (SQLException se) {
      if (((se.getErrorCode() == 50000) && ("XJ015".equals(se.getSQLState())))) {
        // we got the expected exception
      }
      else {
        severeEx = se;
      }
    } catch (Exception ex) {
      // got an unexpected exception
      severeEx = ex;
    } finally {
      if (severeEx != null) {
        // if the error code or SQLState is different, we have
        // an unexpected exception (shutdown failed)
        String msg = LocalizedResource.getMessage("FS_JDBC_SHUTDOWN_ERROR");
        logSevere(msg, severeEx);
      }
      if (!cache.isClosed()) {
        cache.close();
      }
    }
  }

  /*
  /**
   * Start the rebalancing of buckets of all partitioned tables and wait for it
   * to complete.
   *
  @Override
  protected void startRebalanceFactory(final Cache cache,
      final Map<String, Object> options) {
    Boolean rebalanceOnStartup = (Boolean)options.get(REBALANCE);
    if (rebalanceOnStartup != null && rebalanceOnStartup.booleanValue()) {
      boolean interrupted = Thread.interrupted();
      try {
        final RebalanceOperation rebalanceOp = cache.getResourceManager()
            .createRebalanceFactory().start();
        rebalanceOp.getResults();
      } catch (InterruptedException ie) {
        interrupted = true;
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }
  */

  protected void logSevere(String msg, Throwable t) {
    if (this.logger != null) {
      if (msg != null) {
        this.logger.convertToLogWriter().severe(msg, t);
      }
      else {
        this.logger.severe(t);
      }
    }
    else {
      if (msg != null) {
        System.out.println("[severe] " + msg);
      }
      if (t != null) {
        t.printStackTrace();
      }
    }
  }

  protected void logWarning(String msg, Throwable t) {
    if (this.logger != null) {
      if (msg != null) {
        this.logger.convertToLogWriter().warning(msg, t);
      }
      else {
        this.logger.warning(t);
      }
    }
    else {
      if (msg != null) {
        System.out.println("[warning] " + msg);
      }
      if (t != null) {
        t.printStackTrace();
      }
    }
  }

  protected boolean setDefaultHeapSize() {
    return true;
  }

  @Override
  protected void processStartArg(String key, String value,
      Map<String, Object> m, List<String> vmArgs, Properties props)
      throws Exception {
    throw new IllegalArgumentException(
        LocalizedStrings.GfxdServerLauncher_UNKNOWN_ARGUMENT
            .toLocalizedString(new Object[] { key, value }));
  }

  protected boolean processedDefaultGCParams = false;

  @Override
  protected void processStartOption(String key, String value,
      Map<String, Object> m, List<String> vmArgs, Map<String, String> envArgs,
      Properties props) throws Exception {
    final String lwcBindAddressArg = getLWCAddressArgName();
    final String lwcPortArg = getLWCPortArgName();
    // THRIFT
    final String thriftServerAddressArg = getThriftAddressArgName();
    final String thriftServerPortArg = getThriftPortArgName();

    //first check for deprecated attributes and throw exception with appropriate error message
    if (deprecatedAttributes.keySet().contains(key)) {
    	throw new IllegalArgumentException(
    		LocalizedResource.getMessage(
    			"TOOLS_DEPRECATED_ATTRIBUTES_MESSAGE", 
    			key, 
    			deprecatedAttributes.get(key)));
    }
    if (HEAP_SIZE.equals(key)) {
      if (this.maxHeapSize == null) {
        vmArgs.add("-Xmx" + value);
        this.maxHeapSize = value;
      }
      if (this.initialHeapSize == null) {
        vmArgs.add("-Xms" + value);
        this.initialHeapSize = value;
      }
    }
    else if (OFF_HEAP_SIZE.equals(key)) {
      props.setProperty(DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME, value);
      this.offHeapSize = value;
    }
    else if (RUN_NETSERVER.equals(key)) {
      m.put(RUN_NETSERVER, value);
    }
    else if (lwcBindAddressArg.equals(key)) {
      m.put(lwcBindAddressArg, value);
    }
    else if (lwcPortArg.equals(key)) {
      try {
        int lwcPort = Integer.parseInt(value);
        if (lwcPort < 1 || lwcPort > 65535) {
          String msg = LocalizedResource.getMessage("SERVER_INVALID_PORT",
              value);
          throw new IllegalArgumentException(msg);
        }
        m.put(lwcPortArg, value);
      } catch (NumberFormatException nfe) {
        String msg = LocalizedResource.getMessage("SERVER_INVALID_PORT", value);
        throw new IllegalArgumentException(msg, nfe);
      }
    }
    // THRIFT related Starts
    else if (thriftServerAddressArg.equals(key)) {
      m.put(thriftServerAddressArg, value);
    }
    else if (thriftServerPortArg.equals(key)) {
      try {
        int thriftPort = Integer.parseInt(value);
        if (thriftPort < 1 || thriftPort > 65535) {
          String msg = LocalizedResource.getMessage("SERVER_INVALID_PORT",
              value);
          throw new IllegalArgumentException(msg);
        }
        m.put(thriftServerPortArg, value);
      } catch (NumberFormatException nfe) {
        String msg = LocalizedResource.getMessage("SERVER_INVALID_PORT", value);
        throw new IllegalArgumentException(msg, nfe);
      }
    }
    // THRIFT related ends
    else if (com.pivotal.gemfirexd.Attribute.AUTH_PROVIDER.equals(key)) {
      props.setProperty(com.pivotal.gemfirexd.Attribute.AUTH_PROVIDER, value);
    }
    else if (com.pivotal.gemfirexd.Attribute.SERVER_AUTH_PROVIDER.equals(key)) {
      props.setProperty(com.pivotal.gemfirexd.Attribute.SERVER_AUTH_PROVIDER,
          value);
    }
    else if (com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR.equalsIgnoreCase(key)
        && (value == null || value.length() == 0)) {
      readPassword(envArgs);
    }
    else if (WAIT_FOR_SYNC.equals(key)) {
      if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
        throw new IllegalArgumentException(LocalizedResource.getMessage(
            "UTIL_GFXD_ExpectedBoolean", WAIT_FOR_SYNC, value));
      }
      this.waitForData = "true".equalsIgnoreCase(value);
    }
    else {
      super.processStartOption(key, value, m, vmArgs, envArgs, props);
    }
  }

  @Override
  protected void processServerEnv(Properties props) throws Exception {
    final NativeCalls nc = NativeCalls.getInstance();
    final String encPasswd = nc.getEnvironment(ENV1);
    if (encPasswd != null) {
      // try to clear the environment variable now for security
      // (will not work when no JNA based implementation is available)
      nc.setEnvironment(ENV1, null);
      if (encPasswd.length() > 0) {
        final byte[] keyBytes = getBytesEnv();
        props.setProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR,
            GemFireXDUtils.decrypt(encPasswd, null, keyBytes));
      }
    }
  }

  protected void readPassword(Map<String, String> envArgs) throws Exception {
    final Console cons = System.console();
    if (cons == null) {
      throw new IllegalStateException(
          "No console found for reading the password.");
    }
    final char[] pwd = cons.readPassword(LocalizedResource
        .getMessage("UTIL_password_Prompt"));
    if (pwd != null) {
      final String passwd = new String(pwd);
      // encrypt the password with predefined key that is salted with host IP
      final byte[] keyBytes = getBytesEnv();
      envArgs.put(ENV1, GemFireXDUtils.encrypt(passwd, null, keyBytes));
    }
  }

  // key used for encrypting the password set as environment variable
  private final byte[] getBytesEnv() throws Exception {
    final byte[] bytes = new byte[16];
    final int step = 4;
    int pos = 0;
    Assert.assertTrue(step == ENV_B2.length);
    System.arraycopy(ENV_B2, 0, bytes, pos, step);
    pos += step;
    Assert.assertTrue(step == ENV_B1.length);
    System.arraycopy(ENV_B1, 0, bytes, pos, step);
    pos += step;
    Assert.assertTrue(step == ENV_B3.length);
    System.arraycopy(ENV_B3, 0, bytes, pos, step);
    pos += step;
    Assert.assertTrue(step == ENV_B4.length);
    System.arraycopy(ENV_B4, 0, bytes, pos, step);

    // salt the key with host address
    InetAddress host;
    try {
      host = SocketCreator.getLocalHost();
    } catch (Exception ex) {
      host = null;
    }
    if (host != null) {
      GemFireXDUtils.updateCipherKeyBytes(bytes, host.getAddress());
    }
    return bytes;
  }

  /**
   * <P>
   * if <code>heap-size</code> is mentioned in the
   * startup arguments and started within Sun JVM, following GC options are
   * enabled by the script.
   * 
   * <li>
   * Parallel young generation collector (<code>UseParNewGC</code>)</li>
   * <li>
   * Concurrent low pause collector (<code>UseConcMarkSweepGC</code>)</li>
   * <li>
   * old generation threshold (<code>CMSInitiatingOccupancyFraction</code>) from
   * default 68% to 50%.</li>
   * <P>
   * On frequent full garbage collections with above options consider following
   * GC options.
   * <li>
   * UseCMSInitiatingOccupancyOnly</li>
   * <li>
   * CMSFullGCsBeforeCompaction</li>
   * 
   * <P>
   * For beyond 1 GB of physical memory and initial-heap/max-heap padding is
   * enough consider additional options.
   * 
   * <li>
   * <code>MaxNewSize=24m</code></li>
   * <li>
   * <code>NewSize=24m</code></li>
   * <li>
   * <code>CMSParallelRemarkEnabled</code></li>
   * 
   * <P>
   * <note> All JVM options can be passed from the command line using '-J'
   * prefix.</note> <br>
   * <example> e.g. -J-XX:MaxNewSize=24m </example>
   * <P>
   * 
   * @param incomingVMArgs
   *          vm argument list.
   * @param map
   *          additional server arguments of command builder.
   */
  // addressing #43004
  // for beyond 1GB of physical memory
  // -Xmx512m -Xms512m -XX:MaxNewSize=24m -XX:NewSize=24m -XX:+UseParNewGC
  // -XX:+CMSParallelRemarkEnabled -XX:+UseConcMarkSweepGC
  @Override
  protected List<String> postProcessOptions(List<String> incomingVMArgs,
      final Map<String, Object> map) {

    if (processedDefaultGCParams) {
      return incomingVMArgs;
    }
    
    int evictPercent = 0;
    int criticalPercent = 0;
    
    if (this.offHeapSize != null) {
      if (!map.containsKey(CRITICAL_OFF_HEAP_PERCENTAGE)) {
        criticalPercent = 90;
        map.put(CRITICAL_OFF_HEAP_PERCENTAGE, "-" + CRITICAL_OFF_HEAP_PERCENTAGE + "=90");
      }
      if (!map.containsKey(EVICTION_OFF_HEAP_PERCENTAGE)) {
        evictPercent = (criticalPercent * 4) / 5;
        map.put(EVICTION_OFF_HEAP_PERCENTAGE, "-" + EVICTION_OFF_HEAP_PERCENTAGE + '='
            + evictPercent);
      }
    }

    final ArrayList<String> vmArgs = new ArrayList<String>();
    
    // increase the default perm gen size
    if (this.maxPermGenSize == null 
        && jvmVendor != null 
        && (jvmVendor.contains("Sun") || jvmVendor.contains("Oracle"))) {
      this.maxPermGenSize = MAX_PERM_SIZE + MAX_PERM_DEFAULT;
      vmArgs.add(this.maxPermGenSize);
    }

    // If either the max heap or initial heap is null, set the one that is null
    // equal to the one that isn't.
    if (this.maxHeapSize == null) {
      if (this.initialHeapSize != null) {
        vmArgs.add("-Xmx" + this.initialHeapSize);
        this.maxHeapSize = this.initialHeapSize;
      } else {
        final Properties props = (Properties)map.get(PROPERTIES);
        if (setDefaultHeapSize() && !"false".equalsIgnoreCase(props
            .getProperty(com.pivotal.gemfirexd.Attribute.GFXD_HOST_DATA))) {
          // Try some sane default for heapSize if none specified.
          // Set it only if total RAM is more than 1.5X of the default.
          OperatingSystemMXBean bean = ManagementFactory
              .getOperatingSystemMXBean();
          Object memSize = null;
          try {
            Method m = bean.getClass().getMethod("getTotalPhysicalMemorySize");
            m.setAccessible(true);
            memSize = m.invoke(bean);
          } catch (Exception e) {
            // ignore and move with JVM defaults
          }
          if (memSize != null && (memSize instanceof Number)) {
            long totalMemory = ((Number)memSize).longValue();
            long useDefaultMemoryGB = DEFAULT_HEAPSIZE_GB;
            long defaultMemory = DEFAULT_HEAPSIZE_GB * 1024L * 1024L * 1024L;
            if ((totalMemory * 2) < (defaultMemory * 3)) {
              useDefaultMemoryGB = DEFAULT_HEAPSIZE_SMALL_GB;
              defaultMemory = DEFAULT_HEAPSIZE_SMALL_GB * 1024L * 1024L * 1024L;
              if ((totalMemory * 2) < (defaultMemory * 3)) {
                useDefaultMemoryGB = 0;
              }
            }
            if (useDefaultMemoryGB > 0) {
              this.maxHeapSize = this.initialHeapSize =
                  Long.toString(useDefaultMemoryGB) + 'g';
              vmArgs.add("-Xmx" + this.maxHeapSize);
              vmArgs.add("-Xms" + this.initialHeapSize);
            }
          }
        }
      }
    } else if (this.initialHeapSize == null) {
      vmArgs.add("-Xms" + this.maxHeapSize);
      this.initialHeapSize = this.maxHeapSize;
    }
    if (this.maxHeapSize != null && this.maxHeapSize.equals(this.initialHeapSize)) {
      if (!map.containsKey(CRITICAL_HEAP_PERCENTAGE)) {
        criticalPercent = 90;
        map.put(CRITICAL_HEAP_PERCENTAGE, "-" + CRITICAL_HEAP_PERCENTAGE + "=90");
      }
      else {
        String criticalHeapStr = (String)map.get(CRITICAL_HEAP_PERCENTAGE);
        criticalPercent = Integer.parseInt(criticalHeapStr.substring(
            criticalHeapStr.indexOf('=') + 1).trim());
      }
      
      if (!map.containsKey(EVICTION_HEAP_PERCENTAGE)) {
        // reduce the critical-heap-percentage by 10% to get
        // eviction-heap-percentage
        evictPercent = (criticalPercent * 9) / 10;
        map.put(EVICTION_HEAP_PERCENTAGE, "-" + EVICTION_HEAP_PERCENTAGE + '='
            + evictPercent);
      }
      else {
        String evictHeapStr = (String)map.get(EVICTION_HEAP_PERCENTAGE);
        evictPercent = Integer.parseInt(evictHeapStr.substring(
            evictHeapStr.indexOf('=') + 1).trim());
      }
      
      if (jvmVendor != null
          && (jvmVendor.contains("Sun") || jvmVendor.contains("Oracle"))) {
        vmArgs.add("-XX:+UseParNewGC");
        vmArgs.add("-XX:+UseConcMarkSweepGC");
        vmArgs.add("-XX:CMSInitiatingOccupancyFraction=50");
        vmArgs.add("-XX:+CMSClassUnloadingEnabled");
      }
    }

    // If heap and off-heap sizes were both specified, then the critical and
    // eviction values for heap will be used.
    if (evictPercent != 0) {
      // set the thickness to a more reasonable value than 2%
      float criticalThickness = criticalPercent * 0.05f;
      vmArgs.add("-D" + ResourceManager.THRESHOLD_THICKNESS_PROP
          + '=' + criticalThickness);
      // set the eviction thickness to a more reasonable value than 2%
      float evictThickness = evictPercent * 0.1f;
      vmArgs.add("-D" + ResourceManager.THRESHOLD_THICKNESS_EVICT_PROP
          + '=' + evictThickness);
      // set the eviction burst percentage to a more reasonable value than 0.4%
      float evictBurstPercent = evictPercent * 0.02f;
      vmArgs.add("-D" + ResourceManager.EVICTION_BURST_PERCENT_PROP + '='
          + evictBurstPercent);
      // reduce the heap poller interval to something more practical for high
      // concurrent putAlls
      vmArgs.add("-D" + HeapMemoryMonitor.POLLER_INTERVAL_PROP + "=50");
      // always force EVICT_HIGH_ENTRY_COUNT_BUCKETS_FIRST to false and
      // EVICT_HIGH_ENTRY_COUNT_BUCKETS_FIRST_FOR_EVICTOR to true
      vmArgs.add("-D" + HeapEvictor.EVICT_HIGH_ENTRY_COUNT_BUCKETS_FIRST_PROP
          + "=false");
      vmArgs.add("-D"
          + HeapEvictor.EVICT_HIGH_ENTRY_COUNT_BUCKETS_FIRST_FOR_EVICTOR_PROP
          + "=true");
    }

    vmArgs.addAll(incomingVMArgs);
    processedDefaultGCParams = true;

    return vmArgs;
  }

  protected String getLWCAddressArgName() {
    return LWC_BIND_ADDRESS_ARG;
  }

  protected String getLWCPortArgName() {
    return LWC_PORT_ARG;
  }

  // THRIFT related Starts
  protected String getThriftAddressArgName() {
    return THRIFT_SERVER_ADDRESS;
  }

  protected String getThriftPortArgName() {
    return THRIFT_SERVER_PORT;
  }

  // THRIFT related Ends

  @Override
  protected void processUnknownStartOption(String key, String value,
      Map<String, Object> m, List<String> vmArgs, Properties props) {
    props.setProperty(key, value);
  }

  /**
   * Main method that parses the command line and performs an will start, stop,
   * or get the status of a fabric server. This main method is also the main
   * method of the launched fabric server VM ("server" mode).
   */
  public static void main(String[] args) {
    GfxdServerLauncher launcher = new GfxdServerLauncher("SnappyData Server");
    launcher.run(args);
  }

  @Override
  protected String getBaseName(final String name) {
    if (!StringUtils.isBlank(System.getenv("SNAPPY_HOME")))
      return "snappyserver";
    else
      return "gfxdserver";
  }

  /**
   * Main method that parses the command line and performs an will start, stop,
   * or get the status of a fabric server.
   */
  protected void run(String[] args) {
    instance = this;
    boolean inServer = false;
    try {
      if (args.length > 0) {
        if (args[0].equalsIgnoreCase("start")) {
          if (args.length > 1 && args[1] != null
              && args[1].equalsIgnoreCase("listcmds")) {
            listOptions("start", args);
          }
          start(args);
        }
        else if (args[0].equalsIgnoreCase("server")) {
          inServer = true;
          server(args);
        }
        else if (args[0].equalsIgnoreCase("stop")) {
          stop(args);
        }
        else if (args[0].equalsIgnoreCase("status")) {
          status(args);
        }
        else if (args[0].equalsIgnoreCase("listcmds")) {
          listCommands();
        }
        else if (args[0].equalsIgnoreCase("wait")) {
          this.waitForData = true;
          waitForRunning(args);
        }
        else {
          usage();
          System.exit(1);
        }
      }
      else {
        usage();
        System.exit(1);
      }
      if (DONT_EXIT_AFTER_LAUNCH) {
        return;
      }
      throw new Exception("internal error.. shouldn't reach here.");
    } catch (Throwable t) {
      Error err;
      if (t instanceof Error && SystemFailure.isJVMFailureError(
          err = (Error)t)) {
        SystemFailure.initiateFailure(err);
        err.printStackTrace();
        final String errorMsg = LocalizedResource.getMessage("FS_START_ERROR");
        if (inServer) {
          Throwable lastE = err;
          Throwable exception = err;
          while ((lastE = exception.getCause()) != null) {
            exception = lastE;
          }
          final String msg = (exception.getMessage() == null) ? "" : "\n"
              + exception.getClass().getSimpleName() + ": "
              + exception.getMessage();
          setServerError(errorMsg + msg, err);
        }
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw err;
      }
      // Whenever you catch Error or Throwable, you must also
      // check for fatal JVM error (see above). However, there is
      // _still_ a possibility that you are dealing with a cascading
      // error condition, so you also need to check to see if the JVM
      // is still usable:
      SystemFailure.checkFailure();
      t.printStackTrace();
      final String errorMsg = LocalizedResource.getMessage("FS_START_ERROR");
      if (inServer) {
        Throwable lastE = t;
        while ((lastE = t.getCause()) != null) {
          t = lastE;
        }
        final String msg = (t.getMessage() == null) ? "" : "\n"
            + t.getClass().getSimpleName() + ": " + t.getMessage();
        setServerError(errorMsg + msg, t);
      }
      restoreStdOut();
      if (this.logger != null) {
        this.logger.convertToLogWriter().severe(errorMsg, t);
      }
      else {
        System.out.println(errorMsg + t.getMessage());
      }
      System.exit(1);
    } finally {
      instance = null;
    }
  }

  private void listCommands() {
    System.out.println("start");
    System.out.println("status");
    System.out.println("stop");
    System.exit(0);
  }

  private void listOptions(String subCmd, String[] args) {
    // don't put any code here, we want to exit from finally block
    try {

      final String current, prefix;
      final boolean isPrefixHyphen;
      switch (args.length) {
        case 3:
          prefix = args[2];
          isPrefixHyphen = prefix.equals("-");
          if (!isPrefixHyphen) {
            return;
          }
          current = "";
          break;
        default:
          prefix = args[2];
          isPrefixHyphen = prefix.equals("-");
          current = args[3] != null ? args[3] : "";
      }

      final boolean startsWithGemfire = current
          .startsWith(DistributionConfig.GEMFIRE_PREFIX);
      final boolean startsWithGemfirexd = current
          .startsWith(GfxdConstants.GFXD_PREFIX);

      // applicable to all (start/stop/status).
      if (!startsWithGemfire && !startsWithGemfirexd && isPrefixHyphen) {
        System.out.println("-" + DIR);
      }

      if (!subCmd.equals("start")) {
        return;
      }

      listAddOnArgs(startsWithGemfire, startsWithGemfirexd, isPrefixHyphen);

      for (String s : FabricServiceUtils.getGFEPropNames()) {
        if (startsWithGemfire) {
          System.out.print(prefix);
          System.out.print(DistributionConfig.GEMFIRE_PREFIX);
          System.out.println(s);
        }
        else if (isPrefixHyphen) {
          System.out.print(prefix);
          System.out.println(s);
        }
      }

      for (String s : new TreeSet<String>(
          GfxdConstants.validExtraGFXDProperties)) {
        if (startsWithGemfirexd) {
          System.out.print(prefix);
          System.out.print(GfxdConstants.GFXD_PREFIX);
          System.out.println(s);
        }
        else if (isPrefixHyphen) {
          System.out.print(prefix);
          System.out.println(s);
        }
      }
    } finally {
      System.exit(0);
    }
  }

  // to be overridden by child classes for additional options.
  protected void listAddOnArgs(boolean startsWithGemfire,
      boolean startsWithGemfirexd, boolean isPrefixHyphen) {
    // only applicable to start and if filter is not gemfire. or gemfirexd.
    if (!startsWithGemfire && !startsWithGemfirexd && isPrefixHyphen) {
      System.out.println("-" + CLASSPATH);
      System.out.println("-" + REBALANCE);
      System.out.println("-" + LOCK_MEMORY);
      System.out.println("-" + SERVER_PORT);
      System.out.println("-" + SERVER_BIND_ADDRESS);
      System.out.println("-" + WAIT_FOR_SYNC);
      System.out.println("-" + DISABLE_DEFAULT_SERVER);
      System.out.println("-" + CRITICAL_HEAP_PERCENTAGE);
      System.out.println("-" + EVICTION_HEAP_PERCENTAGE);
      System.out.println("-" + HEAP_SIZE);
      System.out.println("-" + CRITICAL_OFF_HEAP_PERCENTAGE);
      System.out.println("-" + EVICTION_OFF_HEAP_PERCENTAGE);
      System.out.println("-" + OFF_HEAP_SIZE);
      System.out.println("-" + com.pivotal.gemfirexd.Attribute.SERVER_GROUPS);
      System.out.println("-" + com.pivotal.gemfirexd.Attribute.INIT_SCRIPTS);
      System.out.println("-" + RUN_NETSERVER);
      System.out.println("-" + getLWCAddressArgName());
      System.out.println("-" + getLWCPortArgName());
      System.out.println("-" + getThriftAddressArgName());
      System.out.println("-" + getThriftPortArgName());
    }
  }

  /** @see CacheServerLauncher#addToServerCommand */
  @Override
  protected void addToServerCommand(List<String> cmds,
      Map<String, Object> options) {
    super.addToServerCommand(cmds, options);
    String runNetServer = (String)options.get(RUN_NETSERVER);
    if (runNetServer != null) {
      StringBuilder netServerOption = new StringBuilder("-");
      netServerOption.append(RUN_NETSERVER).append('=');
      netServerOption.append(runNetServer);
      cmds.add(netServerOption.toString());
    }
    final String lwcAddressArg = getLWCAddressArgName();
    String bindAddress = (String)options.get(lwcAddressArg);
    if (bindAddress != null) {
      StringBuilder bindOption = new StringBuilder("-");
      bindOption.append(lwcAddressArg).append('=').append(bindAddress);
      cmds.add(bindOption.toString());
    }
    final String lwcPortArg = getLWCPortArgName();
    String port = (String)options.get(lwcPortArg);
    if (port != null) {
      StringBuilder portOption = new StringBuilder("-");
      portOption.append(lwcPortArg).append('=').append(port);
      cmds.add(portOption.toString());
    }
    // THRIFT Related Starts
    final String thriftAddressArg = getThriftAddressArgName();
    bindAddress = (String)options.get(thriftAddressArg);
    if (bindAddress != null) {
      StringBuilder bindOption = new StringBuilder("-");
      bindOption.append(thriftAddressArg).append('=').append(bindAddress);
      cmds.add(bindOption.toString());
    }
    final String thriftPortArg = getThriftPortArgName();
    String thriftPort = (String)options.get(thriftPortArg);
    if (thriftPort != null) {
      StringBuilder thriftPortOption = new StringBuilder("-");
      thriftPortOption.append(thriftPortArg).append('=').append(thriftPort);
      cmds.add(thriftPortOption.toString());
    }
    // THRIFT Related Ends
  }

  @Override
  protected void printStartMessage(Map<String, Object> options,
      Properties props, int pid) throws Exception {
    // validate the GemFire properties
    final Properties configProps = new Properties();
    final Set<String> gfePropNames = getGFEPropNames();
    for (Map.Entry<Object, Object> entry : props.entrySet()) {
      String key = entry.getKey().toString();
      if (gfePropNames.contains(key)) {
        configProps.put(entry.getKey(), entry.getValue());
      }
    }
    DistributionConfig config = printDiscoverySettings(options, configProps);

    // check and print starting message for network server
    String runNetServer = (String)options.get(RUN_NETSERVER);
    String bindAddress = (String)options.get(getLWCAddressArgName());
    String port = (String)options.get(getLWCPortArgName());
    final String thriftDisplay = "true".equalsIgnoreCase(props
        .getProperty(com.pivotal.gemfirexd.Attribute.THRIFT_USE_SSL))
            ? "Secure(SSL) Thrift" : "Thrift";
    if (runNetServer == null
        || Boolean.valueOf(runNetServer).equals(Boolean.TRUE)) {
      InetAddress listenAddr = FabricServiceUtils.getListenAddress(bindAddress,
          config);
      port = (port != null ? port : String
          .valueOf(FabricService.NETSERVER_DEFAULT_PORT));

      System.out.println(LocalizedStrings.GfxdServerLauncher_STARTING_NET_SERVER
          .toLocalizedString(new Object[] { this.useThriftServerDefault
              ? thriftDisplay : "DRDA", "SnappyData", listenAddr, port }));
    }
    // check for thrift server arguments
    if ((port = (String)options.get(getThriftPortArgName())) != null) {
      bindAddress = (String)options.get(getThriftAddressArgName());
      InetAddress listenAddr = FabricServiceUtils.getListenAddress(bindAddress,
          config);
      System.out.println(LocalizedStrings.GfxdServerLauncher_STARTING_NET_SERVER
          .toLocalizedString(new Object[] { thriftDisplay, this.baseName,
              listenAddr, port }));
    }
  }

  protected DistributionConfig printDiscoverySettings(
      final Map<String, Object> options, Properties props) throws SQLException {
    // set the gemfirexd.system.home for any -dir option to search for
    // gemfirexd.properties
    Object homeObj = options.get(DIR);
    if (homeObj != null) {
      FabricServiceUtils.defineSystemPropertyIfAbsent(null,
          Property.SYSTEM_HOME_PROPERTY, homeObj.toString(), null, null);
    }
    // perform GemFireXD specific customizations
    props = FabricServiceUtils.preprocessProperties(props, null, null, true);
    props = FabricServiceUtils.filterGemFireProperties(props,
        "gemfirexdservertemp.log");
    DistributionConfigImpl config = new DistributionConfigImpl(props);

    // check if loner VM, using mcast-port or locators
    String startLocator = props
        .getProperty(DistributionConfig.START_LOCATOR_NAME);
    String locators = props.getProperty(DistributionConfig.LOCATORS_NAME);
    String mcastPort = props.getProperty(DistributionConfig.MCAST_PORT_NAME);
    String startMessage;
    if (startLocator != null && startLocator.length() > 0) {
      startMessage = LocalizedResource.getMessage("FS_START_EMBEDDED_LOCATOR",
          this.baseName, startLocator);
    }
    else if (locators != null && locators.length() > 0) {
      startMessage = LocalizedResource.getMessage("FS_START_LOCATORS",
          this.baseName, locators);
    }
    else {
      if ("0".equals(mcastPort)) {
        startMessage = LocalizedResource.getMessage("FS_START_LONER",
            this.baseName);
      }
      else {
        String mcastAddress = config.getMcastAddress().toString();
        if (mcastAddress.charAt(0) == '/') {
          mcastAddress = mcastAddress.substring(1);
        }
        startMessage = LocalizedResource.getMessage("FS_START_MCAST",
            this.baseName, mcastAddress + '[' + config.getMcastPort() + ']');
      }
    }
    System.out.println(startMessage);

    return config;
  }

  /** @see CacheServerLauncher#startAdditionalServices */
  @Override
  protected void startAdditionalServices(Cache cache,
      Map<String, Object> options, Properties props) throws Exception {
    String runNetServer = (String)options.get(RUN_NETSERVER);
    if (runNetServer == null
        || Boolean.valueOf(runNetServer).equals(Boolean.TRUE)) {
      Properties networkProperties = new Properties();
      if (props != null) {
        networkProperties.putAll(props);
      }
      Object val = null;
      String bindAddress = null;
      int port = -1;
      if ((val = options.get(getLWCAddressArgName())) != null) {
        bindAddress = (String)val;
      }
      if ((val = options.get(getLWCPortArgName())) != null) {
        port = Integer.parseInt((String)val);
      }
      getFabricServiceInstance().startNetworkServer(bindAddress, port,
          networkProperties);
    }

    // check for thrift server arguments
    Object val;
    if ((val = options.get(getThriftPortArgName())) != null) {
      final int port = Integer.parseInt((String)val);
      String bindAddress = null;
      if ((val = options.get(getThriftAddressArgName())) != null) {
        bindAddress = (String)val;
      }
      Properties networkProperties = new Properties();
      if (props != null) {
        networkProperties.putAll(props);
      }
      getFabricServiceInstance().startThriftServer(bindAddress, port,
          networkProperties);
    }
  }

  @Override
  protected void setRunningStatus(final Status stat,
      final InternalDistributedSystem system) {
    final Set<?> otherMembers = system.getDistributionManager()
        .getAllOtherMembers();
    final int numOtherMembers = otherMembers.size();
    if (numOtherMembers > 0) {
      final StringBuilder sb = new StringBuilder();
      int i = 0;
      InternalDistributedMember member;
      for (Object memberObj : otherMembers) {
        if (sb.length() > 0) {
          sb.append(", ");
        }
        if (++i < 8) {
          member = (InternalDistributedMember)memberObj;
          // get the VMKind of this member
          Object vmKind = null;
          try {
            vmKind = Class.forName("com.pivotal.gemfirexd"
                + ".internal.engine.distributed.utils.GemFireXDUtils")
                .getMethod("getVMKind", DistributedMember.class)
                .invoke(null, member);
          } catch (Exception e) {
            // ignore exceptions here
          }
          if (vmKind != null) {
            sb.append(member.toString(":" + vmKind));
          }
          else {
            sb.append(member.toString());
          }
        }
        else { // don't display more than 10 members
          sb.append("...");
          break;
        }
      }
      stat.dsMsg = LocalizedResource.getMessage(
          "UTIL_GFXD_DistributedMembers_Message", numOtherMembers + 1,
          sb.toString());
    }
    super.setRunningStatus(stat, system);
  }

  @Override
  public void setWaitingStatus(String regionPath,
      Set<PersistentMemberID> membersToWaitFor, Set<Integer> missingBuckets,
      PersistentMemberID myId, String message) {
    StringBuilder otherMembers = new StringBuilder();
    String tableName = Misc.getFullTableNameFromRegionPath(regionPath);
    if (GemFireStore.DDL_STMTS_REGION.equals(tableName)) {
      tableName = "DataDictionary";
    }
    else {
      tableName = "table " + tableName;
    }
    for (PersistentMemberID otherId : membersToWaitFor) {
      otherMembers.append("\n [DiskId: ")
          .append(otherId.diskStoreId.toUUID().toString())
          .append(", Location: ").append(otherId.directory).append(']');
    }
    if (missingBuckets != null && missingBuckets.size() > 0) {
      message = LocalizedResource.getMessage("FS_WAITING_MESSAGE_BUCKETS",
          tableName, missingBuckets.toString(), myId.diskStoreId.toUUID()
              .toString(), myId.directory, otherMembers.toString());
    }
    else {
      message = LocalizedResource.getMessage("FS_WAITING_MESSAGE", tableName,
          myId.diskStoreId.toUUID().toString(), myId.directory,
          otherMembers.toString());
    }
    super.setWaitingStatus(regionPath, membersToWaitFor, missingBuckets, myId,
        message);
  }

  /** @see CacheServerLauncher#stopAdditionalServices() */
  @Override
  protected void stopAdditionalServices() throws Exception {
    getFabricServiceInstance().stopAllNetworkServers();
  }

  @SuppressWarnings("unchecked")
  private static final Set<String> gfePropNames = new THashSet(
      Arrays.asList(AbstractDistributionConfig._getAttNames()));

  /**
   * Get all the known GFE property names.
   */
  public static Set<String> getGFEPropNames() {
    return gfePropNames;
  }

  @Override
  protected boolean checkStatusForWait(Status status) {
    // start node even in WAITING state if the "-sync" option is false
    return (status.state == STARTING ||
        (this.waitForData && status.state == WAITING));
  }

  @Override
  protected boolean printLaunchCommand() {
    return PRINT_LAUNCH_COMMAND;
  }

  @Override
  protected void printCommandLine(final String[] commandLine,
      final Map<String, String> env) throws Exception {
    super.printCommandLine(commandLine, env);

    // serialize command-line to bytes and write to ENV2
    final HeapDataOutputStream dos = new HeapDataOutputStream();
    // skip classpath argument since it is already output separately in logs
    ArrayList<String> cmdArgs = new ArrayList<String>(commandLine.length - 2);
    for (int i = 0; i < commandLine.length; i++) {
      String command = commandLine[i];
      if (command == null) {
        continue;
      }
      else if ("-classpath".equals(command)) {
        i++;
      }
      else if (command.startsWith("password=")) {
        // mask password
        cmdArgs.add("password=******");
      }
      else {
        cmdArgs.add(command);
      }
    }
    DataSerializer.writeArrayList(cmdArgs, dos);
    final byte[] keyBytes = getBytesEnv();
    env.put(ENV2, GemFireXDUtils.encryptBytes(dos.toByteArray(), null, keyBytes));
  }

  @Override
  public void printCommandLine(final LogWriterI18n logger) {
    final NativeCalls nc = NativeCalls.getInstance();
    final String cmdLine = nc.getEnvironment(ENV2);
    if (cmdLine != null) {
      try {
        // clear the environment variable
        nc.setEnvironment(ENV2, null);
        final byte[] keyBytes = getBytesEnv();
        final byte[] cmdBytes = GemFireXDUtils.decryptBytes(cmdLine, null,
            keyBytes);
        final ByteArrayDataInput din = new ByteArrayDataInput();
        din.initialize(cmdBytes, null);
        ArrayList<String> cmdArgs = DataSerializer.readArrayList(din);
        logger.config(LocalizedStrings.CacheServerLauncher_CommandLine,
            new Object[] { this.baseName, cmdArgs.toString() });
      } catch (Exception e) {
        logger.warning(LocalizedStrings.CacheServerLauncher_CommandLine,
            new Object[] { this.baseName,
                "[ERROR getting command line: " + e.toString() + ']' });
      }
    }
  }
}
