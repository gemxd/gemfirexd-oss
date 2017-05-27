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

package com.pivotal.gemfirexd.thrift.internal;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.FinalizeObject;
import com.gemstone.gemfire.internal.shared.NativeCalls;
import com.gemstone.gemfire.internal.shared.SystemProperties;
import com.gemstone.gnu.trove.THashSet;
import com.gemstone.gnu.trove.TIntArrayList;
import com.pivotal.gemfirexd.internal.client.net.NetConnection;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import com.pivotal.gemfirexd.jdbc.ClientAttribute;
import com.pivotal.gemfirexd.jdbc.ClientDriver;
import com.pivotal.gemfirexd.thrift.BlobChunk;
import com.pivotal.gemfirexd.thrift.ClobChunk;
import com.pivotal.gemfirexd.thrift.ConnectionProperties;
import com.pivotal.gemfirexd.thrift.EntityId;
import com.pivotal.gemfirexd.thrift.GFXDException;
import com.pivotal.gemfirexd.thrift.GFXDExceptionData;
import com.pivotal.gemfirexd.thrift.GFXDService;
import com.pivotal.gemfirexd.thrift.GFXDType;
import com.pivotal.gemfirexd.thrift.HostAddress;
import com.pivotal.gemfirexd.thrift.OpenConnectionArgs;
import com.pivotal.gemfirexd.thrift.OutputParameter;
import com.pivotal.gemfirexd.thrift.PrepareResult;
import com.pivotal.gemfirexd.thrift.Row;
import com.pivotal.gemfirexd.thrift.RowSet;
import com.pivotal.gemfirexd.thrift.ServerType;
import com.pivotal.gemfirexd.thrift.ServiceMetaData;
import com.pivotal.gemfirexd.thrift.ServiceMetaDataArgs;
import com.pivotal.gemfirexd.thrift.ServiceMetaDataCall;
import com.pivotal.gemfirexd.thrift.StatementAttrs;
import com.pivotal.gemfirexd.thrift.StatementResult;
import com.pivotal.gemfirexd.thrift.TransactionAttribute;
import com.pivotal.gemfirexd.thrift.UpdateResult;
import com.pivotal.gemfirexd.thrift.gfxdConstants;
import com.pivotal.gemfirexd.thrift.common.Converters;
import com.pivotal.gemfirexd.thrift.common.CreateLobFinalizer;
import com.pivotal.gemfirexd.thrift.common.GfxdTSSLSocket;
import com.pivotal.gemfirexd.thrift.common.GfxdTSocket;
import com.pivotal.gemfirexd.thrift.common.PrepareResultHolder;
import com.pivotal.gemfirexd.thrift.common.SocketParameters;
import com.pivotal.gemfirexd.thrift.common.SocketTimeout;
import com.pivotal.gemfirexd.thrift.common.ThriftExceptionUtil;
import com.pivotal.gemfirexd.thrift.common.ThriftUtils;

/**
 * Implementation of client service that wraps a {@link GFXDService.Client}
 * thrift transport to add exception handling, connection pooling, failover and
 * single-hop.
 * <p>
 * This class is NOT THREAD-SAFE and only one thread should be using an instance
 * of this class at a time (or use synchronization at higher layers).
 * 
 * @author swale
 * @since gfxd 1.1
 */
@SuppressWarnings("serial")
public final class ClientService extends ReentrantLock {

  private GFXDService.Client clientService;
  volatile boolean isOpen;
  private HostConnection currentHostConnection;
  private HostAddress currentHostAddress;
  final OpenConnectionArgs connArgs;
  final List<HostAddress> connHosts;
  final boolean loadBalance;
  final SocketParameters socketParams;
  final Set<String> serverGroups;
  volatile int isolationLevel = Converters
      .getJdbcIsolation(gfxdConstants.DEFAULT_TRANSACTION_ISOLATION);

  static final int NUM_TXFLAGS = TransactionAttribute.values().length;

  /**
   * Stores tri-state for TransactionAttributes:
   * 
   * 0 for unset, -1 for false, 1 for true
   */
  private final byte[] txFlags = new byte[NUM_TXFLAGS];

  private static final String hostName;
  private static final String hostId;

  private static Logger DEFAULT_LOGGER = LoggerFactory
      .getLogger(ClientDriver.class);
  private static Logger logger = DEFAULT_LOGGER;

  private static final SharedUtils.CSVVisitor<Collection<HostAddress>, int[]> addHostAddresses =
      new SharedUtils.CSVVisitor<Collection<HostAddress>, int[]>() {
    @Override
    public void visit(String str, Collection<HostAddress> collectAddresses,
        int[] port) {
      final String serverName = SharedUtils.getHostPort(str, port);
      collectAddresses.add(ThriftUtils.getHostAddress(serverName, port[0]));
    }
  };

  static {
    ThriftExceptionUtil.init();

    InetAddress hostAddr;
    String host;
    try {
      hostAddr = ClientSharedUtils.getLocalHost();
      host = hostAddr.getCanonicalHostName();
      if (host == null || host.length() == 0) {
        host = hostAddr.getHostAddress();
      }
    } catch (UnknownHostException uhe) {
      // use localhost as fallback
      host = "localhost";
    }
    hostName = host;

    // use process ID and timestamp for ID
    int pid = NativeCalls.getInstance().getProcessId();
    long currentTime = System.currentTimeMillis();
    StringBuilder sb = new StringBuilder();
    sb.append(pid).append('|');
    ClientSharedUtils.formatDate(currentTime, sb);
    hostId = sb.toString();
    ClientConfiguration config = ClientConfiguration.getInstance();
    getLogger().info("Starting client on '" + hostName + "' with ID='"
        + hostId + "' Source-Revision=" + config.getSourceRevision());
  }

  public static Logger getLogger() {
    return logger;
  }

  public static void setLogger(Logger log) {
    logger = log;
  }

  public ClientService(String host, int port, OpenConnectionArgs connArgs)
      throws GFXDException {
    this.isOpen = false;

    Thread currentThread = Thread.currentThread();
    connArgs.setClientHostName(hostName).setClientID(
        hostId + '|' + currentThread.getName() + "<0x"
            + Long.toHexString(currentThread.getId()) + '>');

    HostAddress hostAddr = ThriftUtils.getHostAddress(host, port);
    this.currentHostConnection = null;
    this.currentHostAddress = null;
    this.connArgs = connArgs;

    Map<String, String> props = connArgs.getProperties();
    String propValue;
    // default for load-balance is true
    this.loadBalance = (props == null || !"false".equalsIgnoreCase(props
        .remove(ClientAttribute.LOAD_BALANCE)));

    // setup the original host list
    if (props != null && (propValue = props.remove(
        ClientAttribute.SECONDARY_LOCATORS)) != null) {
      this.connHosts = new ArrayList<HostAddress>(4);
      this.connHosts.add(hostAddr);
      final int[] portHolder = new int[1];
      SharedUtils.splitCSV(propValue, addHostAddresses, this.connHosts,
          portHolder);
    }
    else {
      this.connHosts = Collections.singletonList(hostAddr);
    }

    // read the server groups to use for connection
    if (props != null
        && (propValue = props.remove(ClientAttribute.SERVER_GROUPS)) != null) {
      @SuppressWarnings("unchecked")
      Set<String> groupsSet = new THashSet(4);
      SharedUtils.splitCSV(propValue, SharedUtils.stringAggregator,
          groupsSet, Boolean.TRUE);
      this.serverGroups = Collections.unmodifiableSet(groupsSet);
    }
    else {
      this.serverGroups = null;
    }

    this.socketParams = new SocketParameters();
    // initialize read-timeout with DriverManager login timeout first
    int loginTimeout = DriverManager.getLoginTimeout();
    if (loginTimeout != 0) {
      SocketParameters.Param.READ_TIMEOUT.setParameter(this.socketParams,
          Integer.toString(loginTimeout));
    }
    // now check for the protocol details like SSL etc and thus get the required
    // GemFireXD ServerType
    boolean binaryProtocol = false;
    boolean useSSL = false;
    if (props != null) {
      binaryProtocol = Boolean.parseBoolean(props
          .remove(ClientAttribute.THRIFT_USE_BINARY_PROTOCOL));
      useSSL = Boolean.parseBoolean(props.remove(ClientAttribute.SSL));
      // set SSL properties (csv format) into SSL params in SocketParameters
      propValue = props.remove(ClientAttribute.THRIFT_SSL_PROPERTIES);
      if (propValue != null) {
        useSSL = true;
        ThriftUtils.getSSLParameters(this.socketParams, propValue);
      }
      // parse remaining properties like socket buffer sizes, read timeout
      // and keep alive settings
      for (SocketParameters.Param p : SocketParameters.getAllParamsNoSSL()) {
        propValue = (String)props.remove(p.getPropertyName());
        if (propValue != null) {
          p.setParameter(this.socketParams, propValue);
        }
      }
    }
    this.socketParams.setServerType(ServerType.getServerType(true,
        binaryProtocol, useSSL));

    connArgs.setProperties(props);

    openConnection(hostAddr, null);
  }

  void openConnection(HostAddress hostAddr, Set<HostAddress> failedServers)
      throws GFXDException {
    // open the connection
    if (SanityManager.TraceClientStatement | SanityManager.TraceClientConn) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("openConnection_S", null, 0, ns,
          true, SanityManager.TraceClientConn ? new Throwable() : null);
    }

    while (true) {
      super.lock();
      try {
        this.currentHostAddress = hostAddr;
        if (this.loadBalance) {
          ControlConnection controlService = ControlConnection
              .getOrCreateControlConnection(this.connHosts.get(0), this);
          // at this point query the control service for preferred server
          this.currentHostAddress = hostAddr = controlService
              .getPreferredServer(failedServers, this.serverGroups, false);
        }

        final SystemProperties sysProps = SystemProperties.getClientInstance();
        final SocketTimeout currentSocket;
        final int readTimeout;
        if (this.clientService != null) {
          currentSocket = (SocketTimeout)this.clientService
              .getOutputProtocol().getTransport();
          readTimeout = currentSocket.getRawTimeout();
        }
        else {
          currentSocket = null;
          readTimeout = socketParams.getReadTimeout(0);
        }

        TTransport inTransport, outTransport;
        TProtocol inProtocol, outProtocol;
        if (getServerType().isThriftSSL()) {
          inTransport = outTransport = new GfxdTSSLSocket(
              hostAddr.resolveHost(), hostAddr.getPort(), socketParams,
              sysProps, readTimeout);
        }
        else {
          inTransport = outTransport = new GfxdTSocket(
              hostAddr.resolveHost(), hostAddr.getPort(), true,
              ThriftUtils.isThriftSelectorServer(), socketParams, sysProps,
              readTimeout);
        }
        if (getServerType().isThriftBinaryProtocol()) {
          inProtocol = new TBinaryProtocol(inTransport);
          outProtocol = new TBinaryProtocol(outTransport);
        }
        else {
          inProtocol = new TCompactProtocol(inTransport);
          outProtocol = new TCompactProtocol(outTransport);
        }

        GFXDService.Client service = new GFXDService.Client(inProtocol,
            outProtocol);

        ConnectionProperties connProps = service.openConnection(this.connArgs);
        if (currentSocket != null) {
          currentSocket.close();
        }
        this.clientService = service;
        this.currentHostConnection = new HostConnection(hostAddr,
            connProps.connId, connProps.token);
        this.currentHostAddress = hostAddr;
        if (SanityManager.TraceClientStatementHA
            | SanityManager.TraceClientConn) {
          if (SanityManager.TraceClientHA) {
            SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                "Opened connection @" + hashCode() + " ID=" + connProps.connId
                    + (connProps.token == null ? "" : (" @"
                        + ClientSharedUtils.toHexString(connProps.token))));
          }
          else {
            final long ns = System.nanoTime();
            SanityManager.DEBUG_PRINT_COMPACT("openConnection_E", null,
                connProps.connId, connProps.token, ns, false, null);
          }
        }

        // set transaction isolation and other attributes on connection (for
        // the case of failover, for example)
        final int isolationLevel = this.isolationLevel;
        final EnumMap<TransactionAttribute, Boolean> txFlags = getTXFlags();
        if (isolationLevel != gfxdConstants.DEFAULT_TRANSACTION_ISOLATION) {
          beginTransaction(isolationLevel, txFlags);
        }
        else if (txFlags != null) {
          setTransactionAttributes(txFlags);
        }

        this.isOpen = true;
        return;
      } catch (Throwable t) {
        failedServers = handleException(t, failedServers, true, false,
            "openConnection");
      } finally {
        super.unlock();
      }
    }
  }

  final ServerType getServerType() {
    return this.socketParams.getServerType();
  }

  final TProtocol getInputProtocol() {
    return this.clientService.getInputProtocol();
  }

  final HostConnection getCurrentHostConnection() {
    return this.currentHostConnection;
  }

  final void setTXFlag(TransactionAttribute txFlag, boolean val) {
    this.txFlags[txFlag.ordinal()] = val ? (byte)1 : -1;
  }

  final boolean isTXFlagSet(TransactionAttribute txFlag, boolean defaultValue) {
    switch (this.txFlags[txFlag.ordinal()]) {
      case 1:
        return true;
      case -1:
        return false;
      default:
        return defaultValue;
    }
  }

  final EnumMap<TransactionAttribute, Boolean> getTXFlags() {
    final byte[] txFlags = this.txFlags;
    EnumMap<TransactionAttribute, Boolean> attrs = null;
    for (int ordinal = 0; ordinal < NUM_TXFLAGS; ordinal++) {
      int flag = txFlags[ordinal];
      switch (flag) {
        case 1:
        case -1:
          if (attrs == null) {
            attrs = ThriftUtils.newTransactionFlags();
          }
          attrs.put(TransactionAttribute.findByValue(ordinal), flag == 1);
          break;
        default:
          break;
      }
    }
    return attrs;
  }

  private Set<HostAddress> updateFailedServersForCurrent(
      Set<HostAddress> failedServers) {
    if (failedServers == null) {
      @SuppressWarnings("unchecked")
      Set<HostAddress> servers = new THashSet(2);
      failedServers = servers;
    }
    final HostAddress hostAddress = this.currentHostAddress;
    if (hostAddress != null) {
      failedServers.add(hostAddress);
    }
    return failedServers;
  }

  protected Set<HostAddress> handleException(Throwable t,
      Set<HostAddress> failedServers, boolean tryFailover,
      boolean createNewConnection, String op) throws GFXDException {
    final HostConnection source = this.currentHostConnection;
    final HostAddress sourceAddr = this.currentHostAddress;
    if (!this.isOpen && createNewConnection) {
      if (t instanceof GFXDException) {
        throw (GFXDException)t;
      }
      else {
        throw ThriftExceptionUtil.newGFXDException(
            SQLState.NO_CURRENT_CONNECTION, t,
            sourceAddr != null ? sourceAddr.toString() : null);
      }
    }
    final int isolationLevel = this.isolationLevel;
    if (!this.loadBalance
        // no failover for transactions yet
        || isolationLevel != Connection.TRANSACTION_NONE) {
      tryFailover = false;
    }

    if (t instanceof GFXDException) {
      GFXDException gfxde = (GFXDException)t;
      GFXDExceptionData gfxdeData = gfxde.getExceptionData();
      String sqlState = gfxdeData.getSqlState();
      NetConnection.FailoverStatus status;
      if ((status = NetConnection.getFailoverStatus(sqlState,
          gfxdeData.getSeverity(), gfxde)).isNone()) {
        // convert DATA_CONTAINTER_CLOSED to "X0Z01" for non-transactional case
        if (isolationLevel == Connection.TRANSACTION_NONE
            && SQLState.DATA_CONTAINER_CLOSED.equals(sqlState)) {
          throw newGFXDExceptionForNodeFailure(source, op, isolationLevel,
              failedServers, createNewConnection,
              ThriftExceptionUtil.newSQLException(gfxde));
        }
        else {
          throw gfxde;
        }
      }
      else if (!tryFailover) {
        throw newGFXDExceptionForNodeFailure(source, op, isolationLevel,
            failedServers, createNewConnection, gfxde);
      }
      else if (status == NetConnection.FailoverStatus.RETRY) {
        return failedServers;
      }
    }
    else if (t instanceof TException) {
      if (!tryFailover) {
        throw newGFXDExceptionForNodeFailure(source, op, isolationLevel,
            failedServers, createNewConnection, t);
      }
    }
    else {
      throw ThriftExceptionUtil.newGFXDException(SQLState.JAVA_EXCEPTION, t,
          sourceAddr != null ? sourceAddr.toString() : null, t.getClass(),
          t.getMessage());
    }
    // need to do failover to new server, so get the next one
    failedServers = updateFailedServersForCurrent(failedServers);
    if (createNewConnection) {
      openConnection(source.hostAddr, failedServers);
    }
    return failedServers;
  }

  Exception newExceptionForNodeFailure(HostConnection expectedSource,
      String op, int isolationLevel, Throwable cause, boolean gfxdException) {
    final HostConnection source = this.currentHostConnection;
    String opNode = op + " {current node = " + source + '}';
    if (isolationLevel == Connection.TRANSACTION_NONE) {
      // throw X0Z01 for this case
      String err = expectedSource
          + (cause instanceof TException ? " {caused by: "
              + ThriftExceptionUtil.getExceptionString(cause) + '}' : "");
      return gfxdException ? ThriftExceptionUtil.newGFXDException(
          SQLState.GFXD_NODE_SHUTDOWN, cause,
          source != null ? source.hostAddr.toString() : null, err, op)
          : ThriftExceptionUtil.newSQLException(SQLState.GFXD_NODE_SHUTDOWN,
              cause, err, opNode);
    }
    else {
      // throw 40XD0 for this case
      String err = " operation=" + opNode
          + (cause instanceof TException ? " caused by: "
              + ThriftExceptionUtil.getExceptionString(cause) : "");
      return gfxdException ? ThriftExceptionUtil.newGFXDException(
          SQLState.DATA_CONTAINER_CLOSED, cause, source != null
              ? source.hostAddr.toString() : null, expectedSource, err)
          : ThriftExceptionUtil.newSQLException(SQLState.DATA_CONTAINER_CLOSED,
              cause, expectedSource, err);
    }
  }

  GFXDException newGFXDExceptionForNodeFailure(HostConnection expectedSource,
      String op, int isolationLevel, Set<HostAddress> failedServers,
      boolean createNewConnection, Throwable cause) {
    final HostConnection source = this.currentHostConnection;
    if (!this.isOpen) {
      return ThriftExceptionUtil.newGFXDException(
          SQLState.NO_CURRENT_CONNECTION, cause,
          source != null ? source.hostAddr.toString() : null);
    }
    // create a new connection in any case for future operations
    if (createNewConnection && this.loadBalance) {
      try {
        failedServers = updateFailedServersForCurrent(failedServers);
        openConnection(source.hostAddr, failedServers);
      } catch (RuntimeException re) {
        // deliberately ignored at this point
      } catch (GFXDException gfxde) {
        // deliberately ignored at this point
      }
    }
    return (GFXDException)newExceptionForNodeFailure(expectedSource, op,
        isolationLevel, cause, true);
  }

  final void checkUnexpectedNodeFailure(final HostConnection expectedSource,
      final String op) throws GFXDException {
    if (expectedSource != null && currentHostConnection == expectedSource) {
      return;
    }
    // In a rare case a server may have come back in the meantime (e.g. between
    // two operations) on the same host:port and could be using the assigned
    // connection ID for some other client while token mode may be disabled. For
    // that case now only checking the HostConnection by reference only.
    throw (GFXDException)newExceptionForNodeFailure(expectedSource, op,
        isolationLevel, null, true);
  }

  final class ClientCreateLobFinalizer implements CreateLobFinalizer {

    private final HostConnection source;

    ClientCreateLobFinalizer(HostConnection source) {
      this.source = source;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FinalizeObject execute(BlobChunk chunk) {
      ClientFinalizer finalizer = new ClientFinalizer(chunk,
          ClientService.this, gfxdConstants.BULK_CLOSE_LOB);
      finalizer.updateReferentData(chunk.lobId, this.source);
      return finalizer;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FinalizeObject execute(ClobChunk chunk) {
      ClientFinalizer finalizer = new ClientFinalizer(chunk,
          ClientService.this, gfxdConstants.BULK_CLOSE_LOB);
      finalizer.updateReferentData(chunk.lobId, this.source);
      return finalizer;
    }
  }

  final void setSourceConnection(RowSet rs) {
    final HostConnection source = this.currentHostConnection;
    rs.setConnId(source.connId);
    rs.setToken(source.token);
    rs.setSource(source.hostAddr);
    // initialize finalizers for LOBs in the rows, if any
    final List<Row> rows = rs.rows;
    if (rows != null && rows.size() > 0) {
      final TIntArrayList lobIndices = rows.get(0).requiresLobFinalizers();
      if (lobIndices != null) {
        final ClientCreateLobFinalizer createLobFinalizer =
            new ClientCreateLobFinalizer(source);
        for (Row row : rows) {
          row.initializeLobFinalizers(lobIndices, createLobFinalizer);
        }
      }
    }
  }

  final void setSourceConnection(StatementResult sr) {
    RowSet rs = sr.resultSet;
    if (rs != null) {
      setSourceConnection(rs);
    }
    rs = sr.generatedKeys;
    if (rs != null) {
      setSourceConnection(rs);
    }
    // initialize finalizers for LOBs in the row, if any
    Row outRow = sr.procedureOutParams;
    if (outRow != null) {
      final TIntArrayList lobIndices = outRow.requiresLobFinalizers();
      if (lobIndices != null) {
        outRow.initializeLobFinalizers(lobIndices,
            new ClientCreateLobFinalizer(this.currentHostConnection));
      }
    }
  }

  private boolean acquireLock(long lockTimeoutMillis) {
    if (lockTimeoutMillis <= 0) {
      super.lock();
      return true;
    }
    else {
      try {
        return super.tryLock(lockTimeoutMillis, TimeUnit.MILLISECONDS);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
  }

  public StatementResult execute(String sql,
      Map<Integer, OutputParameter> outputParams, StatementAttrs attrs)
      throws GFXDException {
    HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("execute_S", sql, source.connId,
          source.token, ns, true, null);
    }
    Set<HostAddress> failedServers = null;
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        StatementResult sr = this.clientService.execute(source.connId, sql,
            outputParams, attrs, source.token);
        setSourceConnection(sr);
        if (attrs != null) {
          attrs.setPossibleDuplicate(false);
        }
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("execute_E", sql, source.connId,
              source.token, ns, false, null);
        }
        return sr;
      } catch (Throwable t) {
        if (attrs == null) {
          attrs = new StatementAttrs();
        }
        attrs.setPossibleDuplicate(false);
        failedServers = handleException(t, failedServers, true, true,
            "executeStatement");
        attrs.setPossibleDuplicate(true);
      } finally {
        super.unlock();
      }
    }
  }

  public UpdateResult executeUpdate(List<String> sqls, StatementAttrs attrs)
      throws GFXDException {
    HostConnection source = this.currentHostConnection;
    String sqlStr = null;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      sqlStr = sqls.toString();
      SanityManager.DEBUG_PRINT_COMPACT("executeUpdate_S", sqlStr,
          source.connId, source.token, ns, true, null);
    }
    Set<HostAddress> failedServers = null;
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        UpdateResult ur = this.clientService.executeUpdate(source.connId,
            sqls, attrs, source.token);
        if (attrs != null) {
          attrs.setPossibleDuplicate(false);
        }
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("executeUpdate_E", sqlStr,
              source.connId, source.token, ns, false, null);
        }
        return ur;
      } catch (Throwable t) {
        if (attrs == null) {
          attrs = new StatementAttrs();
        }
        attrs.setPossibleDuplicate(false);
        failedServers = handleException(t, failedServers, true, true,
            "executeUpdate");
        attrs.setPossibleDuplicate(true);
      } finally {
        super.unlock();
      }
    }
  }

  public RowSet executeQuery(String sql, StatementAttrs attrs)
      throws GFXDException {
    HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("executeQuery_S", sql, source.connId,
          source.token, ns, true, null);
    }
    Set<HostAddress> failedServers = null;
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        RowSet rs = this.clientService.executeQuery(source.connId, sql,
            attrs, source.token);
        setSourceConnection(rs);
        if (attrs != null) {
          attrs.setPossibleDuplicate(false);
        }
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("executeQuery_E", sql,
              source.connId, source.token, ns, false, null);
        }
        return rs;
      } catch (Throwable t) {
        if (attrs == null) {
          attrs = new StatementAttrs();
        }
        attrs.setPossibleDuplicate(false);
        failedServers = handleException(t, failedServers, true, true,
            "executeQuery");
        attrs.setPossibleDuplicate(true);
      } finally {
        super.unlock();
      }
    }
  }

  public PrepareResult prepareStatement(String sql,
      Map<Integer, OutputParameter> outputParams, StatementAttrs attrs)
      throws GFXDException {
    HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("prepareStatement_S", sql,
          source.connId, source.token, ns, true, null);
    }
    Set<HostAddress> failedServers = null;
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        PrepareResult pr = this.clientService.prepareStatement(source.connId,
            sql, outputParams, attrs, source.token);
        if (attrs != null) {
          attrs.setPossibleDuplicate(false);
        }
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("prepareStatement_E", sql
              + ":ID=" + pr.statementId, source.connId, source.token, ns,
              false, null);
        }
        return pr;
      } catch (Throwable t) {
        if (attrs == null) {
          attrs = new StatementAttrs();
        }
        attrs.setPossibleDuplicate(false);
        failedServers = handleException(t, failedServers, true, true,
            "prepareStatement");
        attrs.setPossibleDuplicate(true);
      } finally {
        super.unlock();
      }
    }
  }

  public StatementResult executePrepared(HostConnection source, int stmtId,
      Row params, Map<Integer, OutputParameter> outputParams,
      PrepareResultHolder prh) throws GFXDException {
    Integer logStmtId = null;
    StatementAttrs attrs = prh.getAttributes();
    Set<HostAddress> failedServers = null;
    super.lock();
    try {
      // check if node has failed sometime before; keeping inside try-catch
      // block allows automatic failover if possible
      checkUnexpectedNodeFailure(source, "executePrepared");

      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        logStmtId = Integer.valueOf(stmtId);
        SanityManager.DEBUG_PRINT_COMPACT("executePrepared_S", logStmtId,
            source.connId, source.token, ns, true, null);
      }

      StatementResult sr = this.clientService.executePrepared(stmtId, params,
          outputParams, source.token);
      setSourceConnection(sr);
      if (attrs != null) {
        attrs.setPossibleDuplicate(false);
      }
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("executePrepared_E", logStmtId,
            source.connId, source.token, ns, false, null);
      }
      return sr;
    } catch (Throwable t) {
      failedServers = handleException(t, failedServers, true, true,
          "executePrepared");

      source = this.currentHostConnection;
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        logStmtId = Integer.valueOf(stmtId);
        SanityManager.DEBUG_PRINT_COMPACT("executePrepared_S", logStmtId,
            source.connId, source.token, ns, true, null);
      }
    } finally {
      super.unlock();
    }

    List<Row> paramsBatch = Collections.singletonList(params);
    if (attrs == null) {
      attrs = new StatementAttrs();
    }
    attrs.setPossibleDuplicate(true);
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        StatementResult sr = this.clientService.prepareAndExecute(
            source.connId, prh.getSQL(), paramsBatch, outputParams, attrs,
            source.token);
        setSourceConnection(sr);
        prh.updatePrepareResult(sr.getPreparedResult());
        attrs.setPossibleDuplicate(false);
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("executePrepared_E", logStmtId,
              source.connId, source.token, ns, false, null);
        }
        return sr;
      } catch (Throwable t) {
        attrs.setPossibleDuplicate(false);
        failedServers = handleException(t, failedServers, true, true,
            "executePrepared");
        attrs.setPossibleDuplicate(true);
      } finally {
        super.unlock();
      }
    }
  }

  public UpdateResult executePreparedUpdate(HostConnection source,
      int stmtId, Row params, PrepareResultHolder prh) throws GFXDException {
    Set<HostAddress> failedServers = null;
    super.lock();
    try {
      // check if node has failed sometime before; keeping inside try-catch
      // block allows automatic failover if possible
      checkUnexpectedNodeFailure(source, "executePreparedUpdate");

      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("executePreparedUpdate_S", null,
            source.connId, source.token, ns, true, null);
      }

      UpdateResult ur = this.clientService.executePreparedUpdate(stmtId,
          params, source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("executePreparedUpdate_E", null,
            source.connId, source.token, ns, false, null);
      }
      return ur;
    } catch (Throwable t) {
      failedServers = handleException(t, failedServers, true, true,
          "executePreparedUpdate");

      source = this.currentHostConnection;
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("executePreparedUpdate_S", null,
            source.connId, source.token, ns, true, null);
      }
    } finally {
      super.unlock();
    }

    List<Row> paramsBatch = Collections.singletonList(params);
    StatementAttrs attrs = prh.getAttributes();
    if (attrs == null) {
      attrs = new StatementAttrs();
    }
    attrs.setPossibleDuplicate(true);
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        StatementResult sr = this.clientService.prepareAndExecute(
            source.connId, prh.getSQL(), paramsBatch, null, attrs,
            source.token);
        prh.updatePrepareResult(sr.getPreparedResult());
        attrs.setPossibleDuplicate(false);
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("executePreparedUpdate_E", null,
              source.connId, source.token, ns, false, null);
        }
        return new UpdateResult().setUpdateCount(sr.getUpdateCount())
            .setGeneratedKeys(sr.getGeneratedKeys())
            .setWarnings(sr.getWarnings());
      } catch (Throwable t) {
        attrs.setPossibleDuplicate(false);
        failedServers = handleException(t, failedServers, true, true,
            "executePreparedUpdate");
        attrs.setPossibleDuplicate(true);
      } finally {
        super.unlock();
      }
    }
  }

  public RowSet executePreparedQuery(HostConnection source, int stmtId,
      Row params, PrepareResultHolder prh) throws GFXDException {
    StatementAttrs attrs = prh.getAttributes();
    Set<HostAddress> failedServers = null;
    super.lock();
    try {
      // check if node has failed sometime before; keeping inside try-catch
      // block allows automatic failover if possible
      checkUnexpectedNodeFailure(source, "executePreparedQuery");

      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("executePreparedQuery_S", null,
            source.connId, source.token, ns, true, null);
      }

      RowSet rs = this.clientService.executePreparedQuery(stmtId, params,
          source.token);
      setSourceConnection(rs);
      if (attrs != null) {
        attrs.setPossibleDuplicate(false);
      }
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("executePreparedQuery_E", null,
            source.connId, source.token, ns, false, null);
      }
      return rs;
    } catch (Throwable t) {
      failedServers = handleException(t, failedServers, true, true,
          "executePreparedQuery");

      source = this.currentHostConnection;
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("executePreparedQuery_S", null,
            source.connId, source.token, ns, true, null);
      }
    } finally {
      super.unlock();
    }

    List<Row> paramsBatch = Collections.singletonList(params);
    if (attrs == null) {
      attrs = new StatementAttrs();
    }
    attrs.setPossibleDuplicate(true);
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        StatementResult sr = this.clientService.prepareAndExecute(
            source.connId, prh.getSQL(), paramsBatch, null, attrs,
            source.token);
        prh.updatePrepareResult(sr.getPreparedResult());
        attrs.setPossibleDuplicate(false);
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("executePreparedQuery_E", null,
              source.connId, source.token, ns, false, null);
        }
        RowSet rs = sr.getResultSet();
        setSourceConnection(rs);
        return rs;
      } catch (Throwable t) {
        attrs.setPossibleDuplicate(false);
        failedServers = handleException(t, failedServers, true, true,
            "executePreparedQuery");
        attrs.setPossibleDuplicate(true);
      } finally {
        super.unlock();
      }
    }
  }

  public UpdateResult executePreparedBatch(HostConnection source, int stmtId,
      List<Row> paramsBatch, PrepareResultHolder prh) throws GFXDException {
    Set<HostAddress> failedServers = null;
    super.lock();
    try {
      // check if node has failed sometime before; keeping inside try-catch
      // block allows automatic failover if possible
      checkUnexpectedNodeFailure(source, "executePreparedBatch");

      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("executePreparedBatch_S", null,
            source.connId, source.token, ns, true, null);
      }

      UpdateResult ur = this.clientService.executePreparedBatch(stmtId,
          paramsBatch, source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("executePreparedBatch_E", null,
            source.connId, source.token, ns, false, null);
      }
      return ur;
    } catch (Throwable t) {
      failedServers = handleException(t, failedServers, true, true,
          "executePreparedBatch");

      source = this.currentHostConnection;
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("executePreparedBatch_S", null,
            source.connId, source.token, ns, true, null);
      }
    } finally {
      super.unlock();
    }

    StatementAttrs attrs = prh.getAttributes();
    if (attrs == null) {
      attrs = new StatementAttrs();
    }
    attrs.setPossibleDuplicate(true);
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        StatementResult sr = this.clientService.prepareAndExecute(
            source.connId, prh.getSQL(), paramsBatch, null, attrs,
            source.token);
        prh.updatePrepareResult(sr.getPreparedResult());
        attrs.setPossibleDuplicate(false);
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("executePreparedBatch_E", null,
              source.connId, source.token, ns, false, null);
        }
        return new UpdateResult()
            .setBatchUpdateCounts(sr.getBatchUpdateCounts())
            .setGeneratedKeys(sr.getGeneratedKeys())
            .setWarnings(sr.getWarnings());
      } catch (Throwable t) {
        attrs.setPossibleDuplicate(false);
        failedServers = handleException(t, failedServers, true, true,
            "executePreparedBatch");
        attrs.setPossibleDuplicate(true);
      } finally {
        super.unlock();
      }
    }
  }

  public StatementResult prepareAndExecute(String sql, List<Row> paramsBatch,
      Map<Integer, OutputParameter> outputParams, StatementAttrs attrs)
      throws GFXDException {
    HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("prepareAndExecute_S", sql,
          source.connId, source.token, ns, true, null);
    }
    Set<HostAddress> failedServers = null;
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        StatementResult sr = this.clientService.prepareAndExecute(
            source.connId, sql, paramsBatch, outputParams, attrs,
            source.token);
        setSourceConnection(sr);
        if (attrs != null) {
          attrs.setPossibleDuplicate(false);
        }
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("preparedAndExecute_E", sql,
              source.connId, source.token, ns, false, null);
        }
        return sr;
      } catch (Throwable t) {
        if (attrs == null) {
          attrs = new StatementAttrs();
        }
        attrs.setPossibleDuplicate(false);
        failedServers = handleException(t, failedServers, true, true,
            "prepareAndExecute");
        attrs.setPossibleDuplicate(true);
      } finally {
        super.unlock();
      }
    }
  }

  public void beginTransaction(int jdbcIsolationLevel,
      Map<TransactionAttribute, Boolean> flags) throws GFXDException {
    HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("beginTransaction_S", null,
          source.connId, source.token, ns, true, null);
    }
    Set<HostAddress> failedServers = null;
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        this.clientService.beginTransaction(source.connId,
            Converters.getThriftTransactionIsolation(jdbcIsolationLevel),
            flags, source.token);
        this.isolationLevel = jdbcIsolationLevel;
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("beginTransaction_E", null,
              source.connId, source.token, ns, false, null);
        }
        return;
      } catch (Throwable t) {
        failedServers = handleException(t, failedServers, true, true,
            "beginTransaction");
      } finally {
        super.unlock();
      }
    }
  }

  public void setTransactionAttributes(
      Map<TransactionAttribute, Boolean> flags) throws GFXDException {
    HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("setTransactionAttributes_S", null,
          source.connId, source.token, ns, true, null);
    }
    Set<HostAddress> failedServers = null;
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        this.clientService.setTransactionAttributes(source.connId, flags,
            source.token);
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("setTransactionAttributes_E",
              null, source.connId, source.token, ns, false, null);
        }
        return;
      } catch (Throwable t) {
        failedServers = handleException(t, failedServers, true, true,
            "setTransactionAttributes");
      } finally {
        super.unlock();
      }
    }
  }

  public void commitTransaction(final HostConnection source,
      boolean startNewTransaction, Map<TransactionAttribute, Boolean> flags)
      throws GFXDException {
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("commitTransaction_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      // check if node has failed sometime before
      if (this.isolationLevel != Connection.TRANSACTION_NONE) {
        checkUnexpectedNodeFailure(source, "commitTransaction");
      }
      this.clientService.commitTransaction(source.connId,
          startNewTransaction, flags, source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("commitTransaction_E", null,
            source.connId, source.token, ns, false, null);
      }
      return;
    } catch (Throwable t) {
      // no failover for transactions yet
      handleException(t, null, false, true, "commitTransaction");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  public void rollbackTransaction(final HostConnection source,
      boolean startNewTransaction, Map<TransactionAttribute, Boolean> flags)
      throws GFXDException {
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("rollbackTransaction_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      // check if node has failed sometime before
      if (this.isolationLevel != Connection.TRANSACTION_NONE) {
        checkUnexpectedNodeFailure(source, "rollbackTransaction");
      }
      this.clientService.rollbackTransaction(source.connId,
          startNewTransaction, flags, source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("rollbackTransaction_E", null,
            source.connId, source.token, ns, false, null);
      }
      return;
    } catch (Throwable t) {
      // no failover for transactions yet
      handleException(t, null, false, true, "rollbackTransaction");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  public boolean prepareCommitTransaction(final HostConnection source,
      Map<TransactionAttribute, Boolean> flags) throws GFXDException {
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("prepareCommitTransaction_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      // check if node has failed sometime before
      if (this.isolationLevel != Connection.TRANSACTION_NONE) {
        checkUnexpectedNodeFailure(source, "prepareCommitTransaction");
      }
      boolean result = this.clientService.prepareCommitTransaction(
          source.connId, flags, source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("prepareCommitTransaction_E", null,
            source.connId, source.token, ns, false, null);
      }
      return result;
    } catch (Throwable t) {
      // no failover for transactions yet
      handleException(t, null, false, true, "prepareCommitTransaction");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  public RowSet getNextResultSet(final HostConnection source, int cursorId,
      byte otherResultSetBehaviour) throws GFXDException {
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("getNextResultSet_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      // check if node has failed sometime before
      checkUnexpectedNodeFailure(source, "getNextResultSet");
      RowSet rs = this.clientService.getNextResultSet(cursorId,
          otherResultSetBehaviour, source.token);
      setSourceConnection(rs);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("getNextResultSet_E", null,
            source.connId, source.token, ns, false, null);
      }
      return rs;
    } catch (Throwable t) {
      // no failover possible
      handleException(t, null, false, true, "getNextResultSet");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  public BlobChunk getBlobChunk(final HostConnection source, int lobId,
      long offset, int chunkSize, boolean freeLobAtEnd) throws GFXDException {
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("getBlobChunk_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      // check if node has failed sometime before
      checkUnexpectedNodeFailure(source, "getBlobChunk");

      BlobChunk blob = this.clientService.getBlobChunk(source.connId, lobId,
          offset, chunkSize, freeLobAtEnd, source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("getBlobChunk_E", null,
            source.connId, source.token, ns, false, null);
      }
      return blob;
    } catch (Throwable t) {
      // no failover possible for multiple chunks
      handleException(t, null, false, true, "getBlobChunk");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  public ClobChunk getClobChunk(final HostConnection source, int lobId,
      long offset, int chunkSize, boolean freeLobAtEnd) throws GFXDException {
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("getClobChunk_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      // check if node has failed sometime before
      checkUnexpectedNodeFailure(source, "getClobChunk");

      ClobChunk clob = this.clientService.getClobChunk(source.connId, lobId,
          offset, chunkSize, freeLobAtEnd, source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("getClobChunk_E", null,
            source.connId, source.token, ns, false, null);
      }
      return clob;
    } catch (Throwable t) {
      // no failover possible for multiple chunks
      handleException(t, null, false, true, "getClobChunk");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  // TODO: currently higher layers send lobs in single first chunk

  public int sendBlobChunk(final HostConnection source, BlobChunk chunk)
      throws GFXDException {
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("sendBlobChunk_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      // check if node has failed sometime before
      checkUnexpectedNodeFailure(source, "sendBlobChunk");

      int lobId = this.clientService.sendBlobChunk(chunk, source.connId,
          source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("getBlobChunk_E", null,
            source.connId, source.token, ns, false, null);
      }
      return lobId;
    } catch (Throwable t) {
      // no failover possible for multiple chunks
      handleException(t, null, false, true, "sendBlobChunk");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  public int sendClobChunk(final HostConnection source, ClobChunk chunk)
      throws GFXDException {
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("getClobChunk_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      // check if node has failed sometime before
      checkUnexpectedNodeFailure(source, "sendClobChunk");

      int lobId = this.clientService.sendClobChunk(chunk, source.connId,
          source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("getClobChunk_E", null,
            source.connId, source.token, ns, false, null);
      }
      return lobId;
    } catch (Throwable t) {
      // no failover possible for multiple chunks
      handleException(t, null, false, true, "sendClobChunk");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  public boolean freeLob(final HostConnection source, int lobId,
      long lockTimeoutMillis) throws GFXDException {
    if (source == null || lobId == gfxdConstants.INVALID_ID) {
      return true;
    }

    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("freeLob_S", null, source.connId,
          source.token, ns, true, null);
    }
    if (!acquireLock(lockTimeoutMillis)) {
      return false;
    }
    try {
      // check if node has failed sometime before
      checkUnexpectedNodeFailure(source, "freeLob");

      this.clientService.freeLob(source.connId, lobId, source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("freeLob_E", null, source.connId,
            source.token, ns, false, null);
      }
      return true;
    } catch (Throwable t) {
      // no failover possible for multiple chunks
      handleException(t, null, false, true, "freeLob");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  public RowSet scrollCursor(final HostConnection source, int cursorId,
      int offset, boolean offsetIsAbsolute, boolean fetchReverseForAbsolute,
      int fetchSize) throws GFXDException {
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("scrollCursor_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      // check if node has failed sometime before
      checkUnexpectedNodeFailure(source, "scrollCursor");

      RowSet rs = this.clientService.scrollCursor(cursorId, offset,
          offsetIsAbsolute, fetchReverseForAbsolute, fetchSize, source.token);
      setSourceConnection(rs);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("scrollCursor_E", null,
            source.connId, source.token, ns, false, null);
      }
      return rs;
    } catch (Throwable t) {
      // no failover possible
      handleException(t, null, false, true, "scrollCursor");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  public void executeCursorUpdate(final HostConnection source, int cursorId,
      List<Byte> operations, List<Row> changedRows,
      List<List<Integer>> changedColumnsList, List<Integer> changedRowIndexes)
      throws GFXDException {
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("executeCursorUpdate_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      // check if node has failed sometime before
      checkUnexpectedNodeFailure(source, "executeCursorUpdate");

      this.clientService.executeCursorUpdate(cursorId, operations,
          changedRows, changedColumnsList, changedRowIndexes, source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("executeCursorUpdate_E", null,
            source.connId, source.token, ns, false, null);
      }
      return;
    } catch (Throwable t) {
      // no failover possible
      handleException(t, null, false, true, "executeCursorUpdate");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  public void executeCursorUpdate(final HostConnection source, int cursorId,
      byte operation, Row changedRow, List<Integer> changedColumns,
      int changedRowIndex) throws GFXDException {
    executeCursorUpdate(source, cursorId,
        Collections.singletonList(operation),
        Collections.singletonList(changedRow),
        Collections.singletonList(changedColumns),
        Collections.singletonList(changedRowIndex));
  }

  public ServiceMetaData getServiceMetaData() throws GFXDException {
    HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("getServiceMetaData_S", null,
          source.connId, source.token, ns, true, null);
    }
    Set<HostAddress> failedServers = null;
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        ServiceMetaData smd = this.clientService.getServiceMetaData(
            source.connId, source.token);
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("getServiceMetaData_E", null,
              source.connId, source.token, ns, false, null);
        }
        return smd;
      } catch (Throwable t) {
        failedServers = handleException(t, failedServers, true, true,
            "getServiceMetaData");
      } finally {
        super.unlock();
      }
    }
  }

  public RowSet getSchemaMetaData(ServiceMetaDataCall schemaCall,
      ServiceMetaDataArgs metadataArgs) throws GFXDException {
    HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("getSchemaMetaData_S", null,
          source.connId, source.token, ns, true, null);
    }
    Set<HostAddress> failedServers = null;
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        metadataArgs.setConnId(source.connId).setToken(source.token);
        RowSet rs = this.clientService.getSchemaMetaData(schemaCall,
            metadataArgs);
        setSourceConnection(rs);
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("getSchemaMetaData_E", null,
              source.connId, source.token, ns, false, null);
        }
        return rs;
      } catch (Throwable t) {
        failedServers = handleException(t, failedServers, true, true,
            "getSchemaMetaData");
      } finally {
        super.unlock();
      }
    }
  }

  public RowSet getIndexInfo(ServiceMetaDataArgs metadataArgs,
      boolean unique, boolean approximate) throws GFXDException {
    HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("getIndexInfo_S", null,
          source.connId, source.token, ns, true, null);
    }
    Set<HostAddress> failedServers = null;
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        metadataArgs.setConnId(source.connId).setToken(source.token);
        RowSet rs = this.clientService.getIndexInfo(metadataArgs, unique,
            approximate);
        setSourceConnection(rs);
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("getIndexInfo_E", null,
              source.connId, source.token, ns, false, null);
        }
        return rs;
      } catch (Throwable t) {
        failedServers = handleException(t, failedServers, true, true,
            "getIndexInfo");
      } finally {
        super.unlock();
      }
    }
  }

  public RowSet getUDTs(ServiceMetaDataArgs metadataArgs, List<GFXDType> types)
      throws GFXDException {
    HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("getUDTs_S", null, source.connId,
          source.token, ns, true, null);
    }
    Set<HostAddress> failedServers = null;
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        metadataArgs.setConnId(source.connId).setToken(source.token);
        RowSet rs = this.clientService.getUDTs(metadataArgs, types);
        setSourceConnection(rs);
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("getUDTs_E", null, source.connId,
              source.token, ns, false, null);
        }
        return rs;
      } catch (Throwable t) {
        failedServers = handleException(t, failedServers, true, true,
            "getUDTs");
      } finally {
        super.unlock();
      }
    }
  }

  public RowSet getBestRowIdentifier(ServiceMetaDataArgs metadataArgs,
      int scope, boolean nullable) throws GFXDException {
    HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("getBestRowIdentifier_S", null,
          source.connId, source.token, ns, true, null);
    }
    Set<HostAddress> failedServers = null;
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        metadataArgs.setConnId(source.connId).setToken(source.token);
        RowSet rs = this.clientService.getBestRowIdentifier(metadataArgs,
            scope, nullable);
        setSourceConnection(rs);
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("getBestRowIdentifier_E", null,
              source.connId, source.token, ns, false, null);
        }
        return rs;
      } catch (Throwable t) {
        failedServers = handleException(t, failedServers, true, true,
            "getBestRowIdentifier");
      } finally {
        super.unlock();
      }
    }
  }

  public List<ConnectionProperties> fetchActiveConnections()
      throws GFXDException {
    HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("fetchActiveConnections_S", null,
          source.connId, source.token, ns, true, null);
    }
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        List<ConnectionProperties> conns = this.clientService
            .fetchActiveConnections(source.connId, source.token);
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("fetchActiveConnections_E", null,
              source.connId, source.token, ns, false, null);
        }
        return conns;
      } catch (Throwable t) {
        // no failover required
        handleException(t, null, false, true, "fetchActiveConnections");
      } finally {
        super.unlock();
      }
    }
  }

  public Map<Integer, String> fetchActiveStatements() throws GFXDException {
    HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("fetchActiveStatements_S", null,
          source.connId, source.token, ns, true, null);
    }
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        Map<Integer, String> stmts = this.clientService
            .fetchActiveStatements(source.connId, source.token);
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("fetchActiveStatements_E", null,
              source.connId, source.token, ns, false, null);
        }
        return stmts;
      } catch (Throwable t) {
        // no failover required
        handleException(t, null, false, true, "fetchActiveStatements");
      } finally {
        super.unlock();
      }
    }
  }

  public void cancelStatement(final HostConnection source, int stmtId)
      throws GFXDException {
    if (source == null || stmtId == gfxdConstants.INVALID_ID) {
      return;
    }

    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("cancelStatement_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      // check if node has failed sometime before
      checkUnexpectedNodeFailure(source, "closeResultSet");

      this.clientService.cancelStatement(stmtId, source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("cancelStatement_E", null,
            source.connId, source.token, ns, false, null);
      }
      return;
    } catch (Throwable t) {
      // no failover should be attempted
      handleException(t, null, false, true, "cancelStatement");
    } finally {
      super.unlock();
    }
  }

  public boolean closeResultSet(final HostConnection source, int cursorId,
      long lockTimeoutMillis) throws GFXDException {
    if (source == null || cursorId == gfxdConstants.INVALID_ID) {
      return true;
    }

    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("closeResultSet_S", null,
          source.connId, source.token, ns, true, null);
    }
    if (!acquireLock(lockTimeoutMillis)) {
      return false;
    }
    try {
      // check if node has failed sometime before
      checkUnexpectedNodeFailure(source, "closeResultSet");

      this.clientService.closeResultSet(cursorId, source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("closeResultSet_E", null,
            source.connId, source.token, ns, false, null);
      }
      return true;
    } catch (Throwable t) {
      // no failover should be attempted
      handleException(t, null, false, true, "closeResultSet");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  public boolean closeStatement(final HostConnection source, int stmtId,
      long lockTimeoutMillis) throws GFXDException {
    if (source == null || stmtId == gfxdConstants.INVALID_ID) {
      return true;
    }

    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("closeStatement_S", null,
          source.connId, source.token, ns, true, null);
    }
    if (!acquireLock(lockTimeoutMillis)) {
      return false;
    }
    try {
      // check if node has failed sometime before
      checkUnexpectedNodeFailure(source, "closeStatement");

      this.clientService.closeStatement(stmtId, source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("closeStatement_E", null,
            source.connId, source.token, ns, false, null);
      }
      return true;
    } catch (Throwable t) {
      // no failover should be attempted
      handleException(t, null, false, true, "closeStatement");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  public boolean closeConnection(long lockTimeoutMillis) throws GFXDException {
    HostConnection source = this.currentHostConnection;
    if (source == null || source.connId == gfxdConstants.INVALID_ID) {
      return true;
    }

    if (SanityManager.TraceClientStatement | SanityManager.TraceClientConn) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("closeConnection_S", null,
          source.connId, source.token, ns, true,
          SanityManager.TraceClientConn ? new Throwable() : null);
    }
    if (!acquireLock(lockTimeoutMillis)) {
      return false;
    }
    try {
      source = this.currentHostConnection;
      if (source == null || source.connId == gfxdConstants.INVALID_ID) {
        return true;
      }
      this.clientService.closeConnection(source.connId, source.token);
      if (SanityManager.TraceClientStatementHA
          | SanityManager.TraceClientConn) {
        if (SanityManager.TraceClientHA) {
          StringBuilder sb = new StringBuilder();
          sb.append("Closed connection @").append(hashCode()).append(" ID=")
              .append(source.connId);
          if (source.token != null && source.token.hasRemaining()) {
            sb.append('@');
            ClientSharedUtils.toHexString(source.token, sb);
          }
          SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
              sb.toString());
        }
        else {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("closeConnection_E", null,
              source.connId, source.token, ns, false, null);
        }
      }
      return true;
    } catch (TException te) {
      // ignore socket exception during connection close
      return true;
    } catch (Throwable t) {
      // no failover should be attempted
      handleException(t, null, false, false, "closeConnection");
      // succeed if above returns
      return true;
    } finally {
      closeService();
      super.unlock();
    }
  }

  final void closeService() {
    final TTransport outTransport = this.clientService.getOutputProtocol()
        .getTransport();
    try {
      outTransport.close();
    } catch (Throwable t) {
      // ignore at this point
    }
    final TTransport inTransport = this.clientService.getInputProtocol()
        .getTransport();
    if (inTransport != outTransport) {
      try {
        inTransport.close();
      } catch (Throwable t) {
        // ignore at this point
      }
    }
    this.isOpen = false;
  }

  public boolean bulkClose(HostConnection thisSource,
      List<EntityId> entities, List<ClientService> closeServices,
      long lockTimeoutMillis) throws GFXDException {
    final HostConnection hostConn = this.currentHostConnection;
    if (thisSource == null || hostConn == null || thisSource != hostConn) {
      return false;
    }

    if (SanityManager.TraceClientStatement | SanityManager.TraceClientConn) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("bulkClose_S", null, hostConn.connId,
          hostConn.token, ns, true, null);
    }
    if (!acquireLock(lockTimeoutMillis)) {
      return false;
    }
    try {
      if (SanityManager.TraceClientStatement | SanityManager.TraceClientConn) {
        if (closeServices != null) {
          final long ns = System.nanoTime();
          HostConnection source;
          for (ClientService service : closeServices) {
            if (service == null
                || (source = service.currentHostConnection) == null) {
              continue;
            }
            SanityManager.DEBUG_PRINT_COMPACT("bulkCloseConnection_S", null,
                source.connId, source.token, ns, true,
                SanityManager.TraceClientConn ? new Throwable() : null);
          }
        }
      }

      this.clientService.bulkClose(entities);

      if (closeServices != null) {
        for (ClientService service : closeServices) {
          if (service != null) {
            service.closeService();
          }
        }
      }

      if (SanityManager.TraceClientStatement | SanityManager.TraceClientConn) {
        final long ns = System.nanoTime();
        if (closeServices != null) {
          HostConnection source;
          for (ClientService service : closeServices) {
            if (service == null
                || (source = service.currentHostConnection) == null) {
              continue;
            }
            SanityManager.DEBUG_PRINT_COMPACT("bulkCloseConnection_E", null,
                source.connId, source.token, ns, false, null);
            if (SanityManager.TraceClientHA) {
              StringBuilder sb = new StringBuilder();
              sb.append("Closed connection @").append(service.hashCode())
                  .append(" ID=").append(source.connId);
              if (source.token != null && source.token.hasRemaining()) {
                sb.append('@');
                ClientSharedUtils.toHexString(source.token, sb);
              }
              SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                  sb.toString());
            }
          }
        }

        SanityManager.DEBUG_PRINT_COMPACT("bulkClose_S", null,
            hostConn.connId, hostConn.token, ns, false, null);
      }
      return true;
    } catch (Throwable t) {
      // no failover should be attempted
      handleException(t, null, false, false, "bulkClose");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  final void checkClosedConnection() throws SQLException {
    if (this.isOpen) {
      return;
    }
    else {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.NO_CURRENT_CONNECTION, null);
    }
  }
}
