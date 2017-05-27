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

package com.pivotal.gemfirexd.thrift.server;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.apache.thrift.ProcessFunction;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolUtil;
import org.apache.thrift.protocol.TType;

import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.distributed.internal.ServerLocation;
import com.gemstone.gnu.trove.THashSet;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.i18n.MessageService;
import com.pivotal.gemfirexd.internal.impl.jdbc.TransactionResourceImpl;
import com.pivotal.gemfirexd.internal.shared.common.error.ExceptionSeverity;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import com.pivotal.gemfirexd.thrift.GFXDException;
import com.pivotal.gemfirexd.thrift.GFXDExceptionData;
import com.pivotal.gemfirexd.thrift.GFXDService;
import com.pivotal.gemfirexd.thrift.HostAddress;
import com.pivotal.gemfirexd.thrift.LocatorService;
import com.pivotal.gemfirexd.thrift.ServerType;
import com.pivotal.gemfirexd.thrift.common.ThriftExceptionUtil;
import com.pivotal.gemfirexd.thrift.common.ThriftUtils;

/**
 * Server-side implementation of thrift LocatorService (see gfxd.thrift).
 * 
 * @author swale
 * @since gfxd 1.1
 */
public class LocatorServiceImpl implements LocatorService.Iface {

  protected final String hostAddress;
  protected final int hostPort;
  private volatile boolean isActive;
  private final CancelCriterion stopper;

  public LocatorServiceImpl(String address, int port) {
    this.hostAddress = address;
    this.hostPort = port;
    this.isActive = true;

    final GemFireStore store = Misc.getMemStoreBooting();
    this.stopper = new CancelCriterion() {

      @Override
      public RuntimeException generateCancelledException(Throwable t) {
        final RuntimeException ce;
        if ((ce = store.getAdvisee().getCancelCriterion()
            .generateCancelledException(t)) != null) {
          return ce;
        }
        return new CacheClosedException(MessageService.getCompleteMessage(
            SQLState.CLOUDSCAPE_SYSTEM_SHUTDOWN, null), t);
      }

      @Override
      public String cancelInProgress() {
        String cancel;
        if ((cancel = store.getAdvisee().getCancelCriterion()
            .cancelInProgress()) != null) {
          return cancel;
        }
        if (isActive()) {
          return null;
        }
        else {
          return MessageService.getCompleteMessage(
              SQLState.CLOUDSCAPE_SYSTEM_SHUTDOWN, null);
        }
      }
    };
  }

  /**
   * Get HostAddress of the preferred server w.r.t. load-balancing to connect to
   * from a thrift client. Client must use the returned server for the real data
   * connection to use the main {@link GFXDService.Iface} thrift API. A list of
   * servers to be excluded from consideration can be passed as a
   * comma-separated string (e.g. to ignore the failed server during failover).
   * <p>
   * If no server is available (after excluding the "failedServers"), then this
   * throws a GFXDException with SQLState "40XD2" (
   * {@link SQLState#DATA_CONTAINER_VANISHED}).
   */
  @Override
  public final HostAddress getPreferredServer(Set<ServerType> serverTypes,
      Set<String> serverGroups, Set<HostAddress> failedServers)
      throws GFXDException {

    if (failedServers == null) {
      failedServers = Collections.emptySet();
    }

    final Set<String> intersectGroups;
    final int ntypes;
    if (serverTypes != null && (ntypes = serverTypes.size()) > 0) {
      if (ntypes == 1) {
        intersectGroups = Collections.singleton(serverTypes.iterator().next()
            .getServerGroupName());
      }
      else {
        @SuppressWarnings("unchecked")
        Set<String> igroups = new THashSet(ntypes);
        intersectGroups = igroups;
        for (ServerType serverType : serverTypes) {
          intersectGroups.add(serverType.getServerGroupName());
        }
      }
    }
    else {
      intersectGroups = null;
    }
    if (SanityManager.TraceClientHA) {
      SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
          "getPreferredServer(): getting preferred server for typeGroups="
              + intersectGroups + (serverGroups != null ? " serverGroups="
                  + serverGroups : ""));
    }

    ServerLocation prefServer;
    HostAddress prefHost;
    try {
      prefServer = GemFireXDUtils.getPreferredServer(serverGroups,
          intersectGroups, failedServers, null, true);
    } catch (Throwable t) {
      throw gfxdException(t);
    }
    if (prefServer != null) {
      prefHost = ThriftUtils.getHostAddress(prefServer.getHostName(),
          prefServer.getPort());
    }
    else {
      // for consistency though null here is okay in Thrift protocol
      prefHost = HostAddress.NULL_ADDRESS;
    }
    return prefHost;
  }

  /**
   * Get two results: a HostAddress containing the preferred server w.r.t.
   * load-balancing to connect to from a thrift client (like in
   * {@link #getPreferredServer}), and all the thrift servers available in
   * the distributed system as HostAddresses. A list of servers to be excluded
   * from consideration can be passed as a comma-separated string (e.g. to
   * ignore the failed server during failover).
   * <p>
   * The returned list has the first element as the preferred server while the
   * remaining elements in the list are all the thrift servers available in the
   * distributed system.
   * <p>
   * This is primarily to avoid making two calls to the servers from the clients
   * during connection creation or failover.
   * <p>
   * If no server is available (after excluding the "failedServers"), then this
   * throws a GFXDException with SQLState "40XD2" (
   * {@link SQLState#DATA_CONTAINER_VANISHED}).
   */
  @Override
  public final List<HostAddress> getAllServersWithPreferredServer(
      Set<ServerType> serverTypes, Set<String> serverGroups,
      Set<HostAddress> failedServers) throws GFXDException {
    HostAddress prefServer = getPreferredServer(serverTypes, serverGroups,
        failedServers);
    ArrayList<HostAddress> prefAndAllServers = new ArrayList<HostAddress>();
    // null result in a list causes Thrift protocol exception
    if (prefServer == null) {
      prefServer = HostAddress.NULL_ADDRESS;
    }

    final Set<ServerType> allTypes;
    if (serverTypes == null || serverTypes.isEmpty()) {
      allTypes = null;
    }
    else {
      @SuppressWarnings("unchecked")
      Set<ServerType> types = new THashSet(serverTypes.size() * 2);
      allTypes = types;
      ServerType locatorType;

      allTypes.addAll(serverTypes);
      for (ServerType serverType : serverTypes) {
        if (serverType.isThriftGFXD()) {
          locatorType = serverType.getCorrespondingLocatorType();
          if (!serverTypes.contains(locatorType)) {
            allTypes.add(locatorType);
          }
        }
      }
    }
    prefAndAllServers.add(prefServer);
    try {
      GemFireXDUtils.getGfxdAdvisor().getAllThriftServers(allTypes,
          prefAndAllServers);
    } catch (Throwable t) {
      throw gfxdException(t);
    }
    if (SanityManager.TraceClientHA) {
      SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
          "getAllServersWithPreferredServer(): returning preferred server "
              + "and all hosts " + prefAndAllServers);
    }
    return prefAndAllServers;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void closeConnection() {
    // nothing to be done here; only signals the processor to cleanly close
    // server-side socket
  }

  /**
   * Custom Processor implementation to handle closeConnection by closing
   * server-side connection cleanly.
   */
  public static final class Processor extends
      LocatorService.Processor<LocatorServiceImpl> {

    private final LocatorServiceImpl inst;
    private final HashMap<String, ProcessFunction<LocatorServiceImpl, ?>> fnMap;

    public Processor(LocatorServiceImpl inst) {
      super(inst);
      this.inst = inst;
      this.fnMap = new HashMap<String, ProcessFunction<LocatorServiceImpl, ?>>(
          super.getProcessMapView());
    }

    @Override
    public final boolean process(final TProtocol in, final TProtocol out)
        throws TException {
      final TMessage msg = in.readMessageBegin();
      final ProcessFunction<LocatorServiceImpl, ?> fn = this.fnMap
          .get(msg.name);
      if (fn != null) {
        fn.process(msg.seqid, in, out, this.inst);
        // terminate connection on receiving closeConnection
        // direct class comparison should be the fastest way
        return fn.getClass() != LocatorService.Processor.closeConnection.class;
      }
      else {
        TProtocolUtil.skip(in, TType.STRUCT);
        in.readMessageEnd();
        TApplicationException x = new TApplicationException(
            TApplicationException.UNKNOWN_METHOD, "Invalid method name: '"
                + msg.name + "'");
        out.writeMessageBegin(new TMessage(msg.name, TMessageType.EXCEPTION,
            msg.seqid));
        x.write(out);
        out.writeMessageEnd();
        out.getTransport().flush();
        return true;
      }
    }
  }

  public final CancelCriterion getCancelCriterion() {
    return this.stopper;
  }

  protected GFXDException gfxdException(Throwable t) {
    SQLException sqle;
    if (t instanceof SQLException) {
      sqle = (SQLException)t;
    }
    else if (t instanceof GFXDException) {
      return (GFXDException)t;
    }
    else {
      if (t instanceof Error) {
        Error err = (Error)t;
        if (SystemFailure.isJVMFailureError(err)) {
          SystemFailure.initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        }
        // Whenever you catch Error or Throwable, you must also
        // check for fatal JVM error (see above). However, there is
        // _still_ a possibility that you are dealing with a cascading
        // error condition, so you also need to check to see if the JVM
        // is still usable.
        SystemFailure.checkFailure();
        // If the above returns then send back error since this may be
        // assertion or some other internal code bug.
      }
      // check node going down
      String nodeFailure = getCancelCriterion().cancelInProgress();
      if (nodeFailure != null) {
        if (!GemFireXDUtils.nodeFailureException(t)) {
          t = getCancelCriterion().generateCancelledException(t);
        }
      }
      else {
        // print to server log
        log("Unexpected error in execution", t, null, true);
      }
      sqle = TransactionResourceImpl.wrapInSQLException(t);
    }

    GFXDExceptionData exData = new GFXDExceptionData(sqle.getMessage(),
        sqle.getSQLState(), sqle.getErrorCode());
    ArrayList<GFXDExceptionData> nextExceptions =
        new ArrayList<GFXDExceptionData>(4);
    SQLException next = sqle.getNextException();
    if (next != null) {
      nextExceptions = new ArrayList<GFXDExceptionData>();
      do {
        nextExceptions.add(new GFXDExceptionData(next.getMessage(), next
            .getSQLState(), next.getErrorCode()));
      } while ((next = next.getNextException()) != null);
    }
    GFXDException gfxde = new GFXDException(exData, getServerInfo());
    // append the server stack trace at the end
    final StringBuilder stack;
    if (t instanceof TException) {
      stack = new StringBuilder("Cause: ").append(
          ThriftExceptionUtil.getExceptionString(t)).append("; Server STACK: ");
    }
    else {
      stack = new StringBuilder("Server STACK: ");
    }
    SanityManager.getStackTrace(t, stack);
    nextExceptions.add(new GFXDExceptionData(stack.toString(),
        SQLState.GFXD_SERVER_STACK_INDICATOR,
        ExceptionSeverity.STATEMENT_SEVERITY));
    gfxde.setNextExceptions(nextExceptions);
    return gfxde;
  }

  public GFXDException newGFXDException(String messageId, Object... args) {
    GFXDExceptionData exData = new GFXDExceptionData();
    exData.setSqlState(StandardException.getSQLStateFromIdentifier(messageId));
    exData.setSeverity(StandardException.getSeverityFromIdentifier(messageId));
    exData.setReason(MessageService.getCompleteMessage(messageId, args));
    return new GFXDException(exData, getServerInfo());
  }

  protected String getServerInfo() {
    return "Locator=" + this.hostAddress + '[' + this.hostPort + "] Thread="
        + Thread.currentThread().getName();
  }

  static void log(final String message, Throwable t, String logLevel,
      boolean forceLog) {
    if (forceLog | GemFireXDUtils.TraceThriftAPI) {
      if (logLevel != null) {
        logLevel = logLevel + ':' + GfxdConstants.TRACE_THRIFT_API;
      }
      else {
        logLevel = GfxdConstants.TRACE_THRIFT_API;
      }
      SanityManager.DEBUG_PRINT(logLevel, message, t);
    }
  }

  public final boolean isActive() {
    return this.isActive;
  }

  public void stop() {
    this.isActive = false;
  }
}
