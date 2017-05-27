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

package com.gemstone.gemfire.internal.shared;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.PrivilegedAction;
import java.util.*;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

import org.apache.log4j.LogManager;
import org.apache.log4j.PropertyConfigurator;

/**
 * Some shared methods now also used by GemFireXD clients so should not have any
 * dependency on other GemFire packages.
 * 
 * @since 7.5
 */
public abstract class ClientSharedUtils {

  private ClientSharedUtils() {
    // no instance allowed
  }

  /** The name of java.util.logging.Logger for GemFireXD. */
  public static final String LOGGER_NAME = "snappystore";

  /** Optional system property to enable GemFire usage of link-local addresses */
  public static final String USE_LINK_LOCAL_ADDRESSES_PROPERTY =
      "net.useLinkLocalAddresses";

  /** True if GemFire should use link-local addresses */
  private static final boolean useLinkLocalAddresses = SystemProperties
      .getClientInstance().getBoolean(USE_LINK_LOCAL_ADDRESSES_PROPERTY, false);

  /**
   * System property (with "gemfirexd." prefix) to specify that Thrift is to be
   * used as the default for default <code>jdbc:gemfirexd://</code> URL
   */
  public static final String USE_THRIFT_AS_DEFAULT_PROP = "thrift-default";

  /**
   * True if using Thrift as default network server and client, false if using
   * DRDA (default).
   */
  public static final boolean USE_THRIFT_AS_DEFAULT = SystemProperties
      .getClientInstance().getBoolean(USE_THRIFT_AS_DEFAULT_PROP, false);

  private static final Object[] staticZeroLenObjectArray = new Object[0];

  /**
   * all classes should use this variable to determine whether to use IPv4 or
   * IPv6 addresses
   */
  private static boolean useIPv6Addresses = !Boolean
      .getBoolean("java.net.preferIPv4Stack")
      && Boolean.getBoolean("java.net.preferIPv6Addresses");

  /** the system line separator */
  public static final String lineSeparator = java.security.AccessController
      .doPrivileged(new PrivilegedAction<String>() {
        @Override
        public String run() {
          return System.getProperty("line.separator");
        }
      });

  /** we cache localHost to avoid bug #40619, access-violation in native code */
  private static final InetAddress localHost;

  private static CommonRunTimeException cre;

  // flags below help avoid logging warnings for failures in setting of
  // additional OS-specific socket TCP keep-alive options
  private static boolean socketKeepAliveIdleWarningLogged;
  private static boolean socketKeepAliveIntvlWarningLogged;
  private static boolean socketKeepAliveCntWarningLogged;

  public static char[] HEX_DIGITS = { '0', '1', '2', '3', '4', '5', '6', '7',
      '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };
  public static char[] HEX_DIGITS_UCASE = { '0', '1', '2', '3', '4', '5', '6',
      '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F' };

  // ------- Constants for date formatting

  private static final int SECONDS = 1000;

  private static final int MINUTES = SECONDS * 60;

  private static final int HOURS = MINUTES * 60;

  private static final long DAYS = HOURS * 24;

  private static final long NORMAL_YEAR = DAYS * 365L;

  private static final long LEAP_YEAR = NORMAL_YEAR + DAYS;

  private static final long FOURYEARS = (NORMAL_YEAR * 3L) + LEAP_YEAR;

  private static final long END_OF_FIRST_YEAR = NORMAL_YEAR;

  private static final long END_OF_SECOND_YEAR = END_OF_FIRST_YEAR + LEAP_YEAR;

  private static final long END_OF_THIRD_YEAR = END_OF_SECOND_YEAR
      + NORMAL_YEAR;

  private static final int[] DAYS_IN_MONTH = { 31, 28, 31, 30, 31, 30, 31, 31,
      30, 31, 30, 31 };

  private static final int FEBRUARY = 1;

  // ------- End constants for date formatting

  private static Logger DEFAULT_LOGGER = Logger.getLogger("");
  private static Logger logger = DEFAULT_LOGGER;

  private static final JdkHelper helper;

  private static final Field bigIntMagnitude;

  static {
    // JdkHelper instance initialization
    final String[] classNames = new String[] {
        "com.gemstone.gemfire.internal.shared.Jdk6Helper",
        "com.gemstone.gemfire.internal.shared.Jdk5Helper"
      };
    JdkHelper impl = null;
    Exception lastEx = null;
    for (int index = 0; index < classNames.length; ++index) {
      try {
        final Class<?> c = Class.forName(classNames[index]);
        if (c != null) {
          impl = (JdkHelper)c.newInstance();
          break;
        }
      } catch (Exception ex) {
        // ignore and try to load next JdkHelper implementation
        lastEx = ex;
      }
    }
    if (impl == null) {
      final IllegalStateException ise = new IllegalStateException(
          "failed to load any JdkHelper class; last exception");
      ise.initCause(lastEx);
      throw ise;
    }
    helper = impl;

    InetAddress lh = null;
    try {
      lh = getHostAddress();
    } catch (UnknownHostException e) {
      e.printStackTrace();
    } catch (SocketException e) {
    }
    localHost = lh;
    cre = new ClientRunTimeException();

    Field mag;
    try {
      mag = BigInteger.class.getDeclaredField("mag");
      mag.setAccessible(true);
      // check if we really got the required field
      if (mag.getType() != int[].class) {
        mag = null;
      }
    } catch (Exception e) {
      // try words for gcj
      try {
        mag = BigInteger.class.getDeclaredField("words");
        mag.setAccessible(true);
        // check if we really got the required field
        if (mag.getType() != int[].class) {
          mag = null;
        }
      } catch (Exception ex) {
        mag = null;
      }
    }
    bigIntMagnitude = mag;
  }

  public static final JdkHelper getJdkHelper() {
    return helper;
  }

  /**
   * All GemFire code should use this method instead of
   * InetAddress.getLocalHost(). See bug #40623
   */
  public static InetAddress getLocalHost() throws UnknownHostException {
    if (localHost == null) {
      throw new UnknownHostException();
    }
    return localHost;
  }

  public static InetAddress getCachedLocalHost() {
    return localHost;
  }

  /** All classes should use this instead of relying on the JRE system property */
  public static boolean preferIPv6Addresses() {
    return useIPv6Addresses;
  }

  /**
   * Tries hard to find a real host address for this machine even if the
   * hostname itself points to a dummy loopback address.
   */
  private static InetAddress getHostAddress() throws UnknownHostException,
      SocketException {
    InetAddress lh = InetAddress.getLocalHost();
    if (lh.isLoopbackAddress()) {
      InetAddress ipv4Fallback = null;
      InetAddress ipv6Fallback = null;
      // try to find a non-loopback address
      Set<InetAddress> myInterfaces = getMyAddresses(false);
      boolean preferIPv6 = useIPv6Addresses;
      String lhName = null;
      for (Iterator<InetAddress> it = myInterfaces.iterator(); lhName == null
          && it.hasNext();) {
        InetAddress addr = it.next();
        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()) {
          break;
        }
        boolean ipv6 = addr instanceof Inet6Address;
        boolean ipv4 = addr instanceof Inet4Address;
        if ((preferIPv6 && ipv6) || (!preferIPv6 && ipv4)) {
          String addrName = reverseDNS(addr);
          if (lh.isLoopbackAddress()) {
            lh = addr;
            lhName = addrName;
          }
          else if (addrName != null) {
            lh = addr;
            lhName = addrName;
          }
        }
        else {
          if (preferIPv6 && ipv4 && ipv4Fallback == null) {
            ipv4Fallback = addr;
          }
          else if (!preferIPv6 && ipv6 && ipv6Fallback == null) {
            ipv6Fallback = addr;
          }
        }
      }
      // vanilla Ubuntu installations will have a usable IPv6 address when
      // running as a guest OS on an IPv6-enabled machine. We also look for
      // the alternative IPv4 configuration.
      if (lh.isLoopbackAddress()) {
        if (ipv4Fallback != null) {
          lh = ipv4Fallback;
          useIPv6Addresses = false;
        }
        else if (ipv6Fallback != null) {
          lh = ipv6Fallback;
          useIPv6Addresses = true;
        }
      }
    }
    return lh;
  }

  /**
   * This method uses JNDI to look up an address in DNS and return its name.
   * 
   * @param addr
   *          the address to be looked up
   * 
   * @return the host name associated with the address or null if lookup isn't
   *         possible or there is no host name for this address
   */
  public static String reverseDNS(InetAddress addr) {
    byte[] addrBytes = addr.getAddress();
    // adjust the address to be suitable for reverse lookup
    StringBuilder lookupsb = new StringBuilder();
    for (int index = addrBytes.length - 1; index >= 0; index--) {
      lookupsb.append(addrBytes[index] & 0xff).append('.');
    }
    lookupsb.append("in-addr.arpa");
    final String lookup = lookupsb.toString();

    DirContext ctx = null;
    try {
      Hashtable<Object, Object> env = new Hashtable<Object, Object>();
      env.put(Context.INITIAL_CONTEXT_FACTORY,
          "com.sun.jndi.dns.DnsContextFactory");
      ctx = new InitialDirContext(env);
      Attributes attrs = ctx.getAttributes(lookup, new String[] { "PTR" });
      for (NamingEnumeration<?> ae = attrs.getAll(); ae.hasMoreElements();) {
        Attribute attr = (Attribute)ae.next();
        for (Enumeration<?> vals = attr.getAll(); vals.hasMoreElements();) {
          Object elem = vals.nextElement();
          if ("PTR".equals(attr.getID()) && elem != null) {
            return elem.toString();
          }
        }
      }
    } catch (Exception e) {
      // ignored
    } finally {
      if (ctx != null) {
        try {
          ctx.close();
        } catch (Exception e) {
          // ignored
        }
      }
    }
    return null;
  }

  /** returns a set of the non-loopback InetAddresses for this machine */
  public static Set<InetAddress> getMyAddresses(boolean includeLocal)
      throws SocketException {
    Set<InetAddress> result = new HashSet<InetAddress>();
    Set<InetAddress> locals = new HashSet<InetAddress>();
    Enumeration<NetworkInterface> interfaces = NetworkInterface
        .getNetworkInterfaces();
    while (interfaces.hasMoreElements()) {
      NetworkInterface face = interfaces.nextElement();
      boolean faceIsUp = false;
      try {
        // invoking using JdkHelper since GemFireXD JDBC3 clients require JDK 1.5
        faceIsUp = helper.isInterfaceUp(face);
      } catch (SocketException se) {
        final Logger log = getLogger();
        if (log != null) {
          LogRecord lr = new LogRecord(Level.INFO,
              "Failed to check if network interface is up. Skipping " + face);
          lr.setThrown(se);
          log.log(lr);
        }
      }
      if (faceIsUp) {
        Enumeration<InetAddress> addrs = face.getInetAddresses();
        while (addrs.hasMoreElements()) {
          InetAddress addr = addrs.nextElement();
          if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
              || (!useLinkLocalAddresses && addr.isLinkLocalAddress())) {
            locals.add(addr);
          }
          else {
            result.add(addr);
          }
        } // while
      }
    } // while
    // fix for bug #42427 - allow product to run on a standalone box by using
    // local addresses if there are no non-local addresses available
    if (result.size() == 0) {
      return locals;
    }
    else {
      if (includeLocal) {
        result.addAll(locals);
      }
      return result;
    }
  }

  /**
   * Enable TCP KeepAlive settings for the socket. This will use the native OS
   * API to set per-socket configuration, if available, else will log warning
   * (only once) if one or more settings cannot be enabled.
   * 
   * @param sock
   *          the underlying Java {@link Socket} to set the keep-alive
   * @param sockStream
   *          the InputStream of the socket (can be null); if non-null then it
   *          is used to determine the underlying socket kernel handle else if
   *          null then reflection on the socket itself is used
   * @param keepIdle
   *          keep-alive time between two transmissions on socket in idle
   *          condition (in seconds)
   * @param keepInterval
   *          keep-alive duration between successive transmissions on socket if
   *          no reply to packet sent after idle timeout (in seconds)
   * @param keepCount
   *          number of retransmissions to be sent before declaring the other
   *          end to be dead
   * 
   * @throws SocketException
   *           if the base keep-alive cannot be enabled on the socket
   * 
   * @see <a
   *      href="http://tldp.org/HOWTO/html_single/TCP-Keepalive-HOWTO/#programming">
   *      TCP Keepalive HOWTO</a>
   * @see <a
   *      href="http://docs.oracle.com/cd/E19082-01/819-2724/6n50b07lr/index.html">
   *      TCP Tunable Parameters</a>
   * @see <a
   *      href="http://msdn.microsoft.com/en-us/library/ms741621%28VS.85%29.aspx">
   *      WSAIoctl function</a>
   * @see <a 
   *      href="http://technet.microsoft.com/en-us/library/dd349797%28WS.10%29.aspx>
   *      TCP/IP-Related Registry Entries</a>
   * @see <a
   *      href="http://msdn.microsoft.com/en-us/library/dd877220%28v=vs.85%29.aspx">
   *      SIO_KEEPALIVE_VALS control code</a>
   */
  public static void setKeepAliveOptions(Socket sock, InputStream sockStream,
      int keepIdle, int keepInterval, int keepCount) throws SocketException {
    final Logger log = getLogger();
    if (log != null && log.isLoggable(Level.FINE)) {
      log.fine("setKeepAliveOptions: setting keepIdle=" + keepIdle
          + ", keepInterval=" + keepInterval + ", keepCount=" + keepCount
          + " for " + sock);
    }
    // first enable keep-alive flag
    sock.setKeepAlive(true);
    // now the OS-specific settings using NativeCalls
    NativeCalls nc = NativeCalls.getInstance();
    Map<TCPSocketOptions, Object> optValueMap =
        new HashMap<TCPSocketOptions, Object>(4);
    if (keepIdle >= 0) {
      optValueMap.put(TCPSocketOptions.OPT_KEEPIDLE, Integer.valueOf(keepIdle));
    }
    if (keepInterval >= 0) {
      optValueMap.put(TCPSocketOptions.OPT_KEEPINTVL,
          Integer.valueOf(keepInterval));
    }
    if (keepCount >= 0) {
      optValueMap.put(TCPSocketOptions.OPT_KEEPCNT, Integer.valueOf(keepCount));
    }
    Map<TCPSocketOptions, Throwable> failed = null;
    try {
      failed = nc.setSocketOptions(sock, sockStream, optValueMap);
    } catch (UnsupportedOperationException e) {
      if (!socketKeepAliveIdleWarningLogged) {
        socketKeepAliveIdleWarningLogged = true;
        if (log != null) {
          log.warning("Failed to set options " + optValueMap + " on socket: "
              + e);
          if (log.isLoggable(Level.FINE)) {
            LogRecord lr = new LogRecord(Level.FINE, "Exception trace:");
            lr.setThrown(e);
            log.log(lr);
          }
        }
      }
      return;
    }
    if (failed != null && failed.size() > 0) {
      if (log != null) {
        for (Map.Entry<TCPSocketOptions, Throwable> e : failed.entrySet()) {
          final TCPSocketOptions opt = e.getKey();
          final Throwable ex = e.getValue();
          byte doLogWarning = 0;
          switch (opt) {
            case OPT_KEEPIDLE:
              if (ex instanceof UnsupportedOperationException) {
                if (!socketKeepAliveIdleWarningLogged) {
                  socketKeepAliveIdleWarningLogged = true;
                  // KEEPIDLE is the minumum required for this, so
                  // log as a warning
                  doLogWarning = 1;
                }
              }
              else {
                doLogWarning = 1;
              }
              break;
            case OPT_KEEPINTVL:
              if (ex instanceof UnsupportedOperationException) {
                if (!socketKeepAliveIntvlWarningLogged) {
                  socketKeepAliveIntvlWarningLogged = true;
                  // KEEPINTVL is not critical to have so log as information
                  doLogWarning = 2;
                }
              }
              else {
                doLogWarning = 2;
              }
              break;
            case OPT_KEEPCNT:
              if (ex instanceof UnsupportedOperationException) {
                if (!socketKeepAliveCntWarningLogged) {
                  socketKeepAliveCntWarningLogged = true;
                  // KEEPCNT is not critical to have so log as information
                  doLogWarning = 2;
                }
              }
              else {
                doLogWarning = 2;
              }
              break;
          }
          if (doLogWarning > 0) {
            if (doLogWarning == 1) {
              log.warning("Failed to set " + opt + " on socket: " + ex);
            }
            else if (doLogWarning == 2) {
              // just log as an information rather than warning
              if (log != DEFAULT_LOGGER) { // SNAP-255
                log.info("Failed to set " + opt + " on socket: "
                    + ex.getMessage());
              }
            }
            if (log.isLoggable(Level.FINE)) {
              LogRecord lr = new LogRecord(Level.FINE, "Exception trace:");
              lr.setThrown(ex);
              log.log(lr);
            }
          }
        }
      }
    }
    else if (log != null && log.isLoggable(Level.FINE)) {
      log.fine("setKeepAliveOptions(): successful for " + sock);
    }
  }

  /**
   * Append the backtrace of given exception to provided {@link StringBuilder}.
   * 
   * @param t
   *          the exception whose backtrace is required
   * @param sb
   *          the {@link StringBuilder} to which the stack trace is to be
   *          appended
   * @param lineSep
   *          the line separator to use, or null to use the default from system
   *          "line.separator" property
   */
  public static void getStackTrace(final Throwable t, StringBuilder sb,
      String lineSep) {
    t.printStackTrace(new StringPrintWriter(sb, lineSep));
  }

  public static Object[] getZeroLenObjectArray() {
    return staticZeroLenObjectArray;
  }

  /**
   * The cached timezone of this VM.
   */
  public static final TimeZone currentTimeZone = TimeZone.getDefault();

  /**
   * The cached timezone offset not including daylight savings.
   */
  public static final long currentTimeZoneOffset = currentTimeZone
      .getRawOffset();

  /** short display name of current timezone in current locale */
  public static final String currentTZShortName = currentTimeZone
      .getDisplayName(false, TimeZone.SHORT);

  /** short display name of current timezone+daylight in current locale */
  public static final String currentTZShortNameDST = currentTimeZone
      .getDisplayName(true, TimeZone.SHORT);

  /**
   * Cached current date information so don't have to create new one
   * everytime. It gets refreshed (individually by each thread,
   * if required) in intervals of {@link #CALENDAR_REFRESH_INTERVAL}.
   */
  private static DateTimeStatics currentDate = new DateTimeStatics(
      System.currentTimeMillis());

  private static final class DateTimeStatics {

    final int tzOffset;

    final long lastUpdatedTime;

    final String tzDisplayName;

    DateTimeStatics(final long currentTime) {
      this(currentTimeZone.getOffset(currentTime), currentTime);
    }

    DateTimeStatics(final int tzOffset, final long currentTime) {
      this(tzOffset, currentTime, (currentTimeZoneOffset == tzOffset)
          ? currentTZShortName : currentTZShortNameDST);
    }

    DateTimeStatics(final int tzOffset, final long currentTime,
        final String tzDisplayName) {
      this.tzOffset = tzOffset;
      this.lastUpdatedTime = currentTime;
      this.tzDisplayName = tzDisplayName;
    }
  }

  // we refresh the calendar after some intervals since it is only
  // used to determine the DST offset that will not change frequently
  private final static long CALENDAR_REFRESH_INTERVAL = 1000 * 60;

  public static void formatDate(final long currentTimeInMillis,
      final StringBuilder sb) {
    try {
      final DateTimeStatics dts = currentDate; // non-volatile snapshot
      int tzOffset = dts.tzOffset;
      String tzDisplayName = dts.tzDisplayName;
      if (currentTimeInMillis >
          (dts.lastUpdatedTime + CALENDAR_REFRESH_INTERVAL)) {
        final int newTzOffset = currentTimeZone.getOffset(
            currentTimeInMillis);
        if (tzOffset != newTzOffset) {
          tzOffset = newTzOffset;
          tzDisplayName = (currentTimeZoneOffset == tzOffset)
              ? currentTZShortName : currentTZShortNameDST;
          currentDate = new DateTimeStatics(tzOffset,
              currentTimeInMillis, tzDisplayName); // non-volatile write
        }
      }
      formatDate(currentTimeInMillis, tzOffset, sb);
      sb.append(' ').append(tzDisplayName);
    } catch (Exception e1) {
      // Fix bug 21857
      try {
        sb.append(new Date(currentTimeInMillis).toString());
      } catch (Exception e2) {
        try {
          sb.append(Long.toString(currentTimeInMillis));
        } catch (Exception e3) {
          sb.append("timestampFormatFailed");
        }
      }
    }
  }

  /**
   * Adapted from Derby's CheapDateFormatter to make use of given calendar and
   * make it more efficient using StringBuilder. Also moved to this class to
   * enable sharing between server and client code.
   * 
   * This method formats the current date into a String. The input is a long
   * representing the number of milliseconds since Jan. 1, 1970. The output is a
   * String in the form yyyy/mm/dd hh:mm:ss.ddd GMT.
   * 
   * The purpose of this class is to format date strings without paying the
   * price of instantiating ResourceBundles and Locales, which the
   * java.util.Date class does whenever you format a date string. As a result,
   * the output of this class is not localized, it does not take the local time
   * zone into account, and it is possible that it will not be as accurate as
   * the standard Date class. It is OK to use this method when, for example,
   * formatting timestamps to write to db2j.LOG, but not for manipulating dates
   * in language processing.
   * 
   * @param time
   *          The current time in milliseconds since Jan. 1, 1970
   * @param tzOffset
   *          The offset of TimeZone including DST.
   * @param sb
   *          Append the date formatted as yyyy/mm/dd hh:mm:ss.ddd to the
   *          StringBuilder.
   */
  public static void formatDate(long time, int tzOffset,
      final StringBuilder sb) {

    // adjust the time as per the given zone offset
    time += tzOffset;

    // Assume not a leap year until we know otherwise
    boolean leapYear = false;

    // How many four year periods since Jan. 1, 1970?
    int year = ((int)(time / FOURYEARS)) * 4;

    // How much time is left over after the four-year periods?
    long leftover = time % FOURYEARS;
    time = leftover;

    year += 1970;

    // Does time extend past end of first year in four-year period?
    if (leftover >= END_OF_FIRST_YEAR) {
      ++year;
      time -= NORMAL_YEAR;
    }

    // Does time extend past end of second year in four-year period?
    if (leftover >= END_OF_SECOND_YEAR) {
      ++year;
      time -= NORMAL_YEAR;
    }

    // Does time extend past end of third year in four-year period?
    if (leftover >= END_OF_THIRD_YEAR) {
      ++year;
      time -= LEAP_YEAR;
    }

    // It's a leap year if divisible by 4, unless divisible by 100,
    // unless divisible by 400.
    if ((year % 4) == 0) {
      if ((year % 100) == 0) {
        if ((year % 400) == 0) {
          leapYear = true;
        }
      }
      else {
        leapYear = true;
      }
    }

    // What day of the year is this, starting at 1?
    int days = (int)(time / DAYS) + 1;

    // What month is this, starting at 1?
    int month = 1;
    for (int i = 0; i < DAYS_IN_MONTH.length; i++) {
      int daysInMonth;

      if (leapYear && (i == FEBRUARY)) {
        // February has 29 days in a leap year
        daysInMonth = 29;
      }
      else {
        // Get number of days in next month
        daysInMonth = DAYS_IN_MONTH[i];
      }

      // Is date after the month we are looking at?
      if (days > daysInMonth) {
        // Count number of months
        month++;

        // Subtract number of days in month
        days -= daysInMonth;
      }
      else {
        // Don't bother to look any more - the date is within
        // the current month.
        break;
      }
    }

    // How much time is left after days are accounted for?
    int remaining = (int)(time % DAYS);

    int hours = remaining / HOURS;

    // How much time is left after hours are accounted for?
    remaining %= HOURS;

    int minutes = remaining / MINUTES;

    // How much time is left after minutes are accounted for?
    remaining %= MINUTES;

    int seconds = remaining / SECONDS;

    // How much time is left after seconds are accounted for?
    remaining %= SECONDS;

    sb.append(year).append('/');
    twoDigits(month, sb);
    sb.append('/');
    twoDigits(days, sb);
    sb.append(' ');
    twoDigits(hours, sb);
    sb.append(':');
    twoDigits(minutes, sb);
    sb.append(':');
    twoDigits(seconds, sb);
    sb.append('.');
    threeDigits(remaining, sb);
  }

  private static void twoDigits(int val, StringBuilder sb) {
    if (val < 10) {
      sb.append('0').append(val);
    }
    else {
      sb.append(val);
    }
  }

  private static void threeDigits(int val, StringBuilder sb) {
    if (val < 10) {
      sb.append("00").append(val);
    }
    else if (val < 100) {
      sb.append('0').append(val);
    }
    else {
      sb.append(val);
    }
  }

  /**
   * Convert a byte array to a String with a hexidecimal format. The String may
   * be converted back to a byte array using fromHexString. <BR>
   * For each byte (b) two characaters are generated, the first character
   * represents the high nibble (4 bits) in hexidecimal (<code>b & 0xf0</code>),
   * the second character represents the low nibble (<code>b & 0x0f</code>). <BR>
   * The byte at <code>data[offset]</code> is represented by the first two
   * characters in the returned String.
   * 
   * @param data
   *          byte array
   * @param offset
   *          starting byte (zero based) to convert.
   * @param length
   *          number of bytes to convert.
   * @return the String (with hexidecimal format) form of the byte array
   */
  public static String toHexString(final byte[] data, int offset,
      final int length) {
    final char[] chars = toHexChars(data, offset, length);
    return getJdkHelper().newWrappedString(chars, 0, chars.length);
  }

  /**
   * Convert a byte array to a String with upper case hexidecimal format. The
   * String may be converted back to a byte array using fromHexString. <BR>
   * For each byte (b) two characaters are generated, the first character
   * represents the high nibble (4 bits) in hexidecimal (<code>b & 0xf0</code>),
   * the second character represents the low nibble (<code>b & 0x0f</code>). <BR>
   * The byte at <code>data[offset]</code> is represented by the first two
   * characters in the returned String.
   * 
   * @param data
   *          byte array
   * @param offset
   *          starting byte (zero based) to convert.
   * @param length
   *          number of bytes to convert.
   * @return the String (with hexidecimal format) form of the byte array
   */
  public static String toHexStringUpperCase(final byte[] data, int offset,
      final int length) {
    final char[] chars = new char[length << 1];
    final int end = offset + length;
    int index = -1;
    while (offset < end) {
      final byte b = data[offset];
      final int highByte = (b & 0xf0) >>> 4;
      final int lowByte = (b & 0x0f);
      chars[++index] = HEX_DIGITS_UCASE[highByte];
      chars[++index] = HEX_DIGITS_UCASE[lowByte];
      ++offset;
    }
    return getJdkHelper().newWrappedString(chars, 0, chars.length);
  }

  /**
   * Convert a byte array to a char[] with a hexidecimal format. The char[] may
   * be converted back to a byte array using fromHexString. <BR>
   * For each byte (b) two characaters are generated, the first character
   * represents the high nibble (4 bits) in hexidecimal (<code>b & 0xf0</code>),
   * the second character represents the low nibble (<code>b & 0x0f</code>). <BR>
   * The byte at <code>data[offset]</code> is represented by the first two
   * characters in the returned char[].
   * 
   * @param data
   *          byte array
   * @param offset
   *          starting byte (zero based) to convert.
   * @param length
   *          number of bytes to convert.
   *          
   * @return the char[] (with hexidecimal format) form of the byte array
   */
  public static char[] toHexChars(final byte[] data, int offset,
      final int length) {
    final char[] chars = new char[length << 1];
    final int end = offset + length;
    int index = 0;
    while (offset < end) {
      index = toHexChars(data[offset], chars, index);
      ++offset;
    }
    return chars;
  }

  /**
   * Convert a ByteBuffer to a String with a hexidecimal format. The String may
   * be converted back to a byte array using fromHexString. <BR>
   * For each byte (b) two characaters are generated, the first character
   * represents the high nibble (4 bits) in hexidecimal (<code>b & 0xf0</code>),
   * the second character represents the low nibble (<code>b & 0x0f</code>). <BR>
   * The byte at <code>data[offset]</code> is represented by the first two
   * characters in the returned String.
   * 
   * @param buffer
   *          the ByteBuffer to convert
   * 
   * @return the String (with hexidecimal format) form of the byte array
   */
  public static String toHexString(ByteBuffer buffer) {
    if (wrapsFullArray(buffer)) {
      final byte[] bytes = buffer.array();
      return toHexString(bytes, 0, bytes.length);
    }
    else {
      int pos = buffer.position();
      final int limit = buffer.limit();
      final char[] chars = new char[(limit - pos) << 1];
      int index = 0;
      while (pos < limit) {
        byte b = buffer.get(pos);
        ++pos;
        index = toHexChars(b, chars, index);
      }
      return getJdkHelper().newWrappedString(chars, 0, chars.length);
    }
  }

  public static boolean wrapsFullArray(ByteBuffer byteBuffer) {
    return byteBuffer.hasArray() && byteBuffer.position() == 0
        && byteBuffer.arrayOffset() == 0
        && byteBuffer.remaining() == byteBuffer.capacity();
  }

  /**
   * Convert a ByteBuffer to a string appending to given {@link StringBuilder}
   * with a hexidecimal format. The string may be converted back to a byte array
   * using fromHexString. <BR>
   * For each byte (b) two characaters are generated, the first character
   * represents the high nibble (4 bits) in hexidecimal (<code>b & 0xf0</code>),
   * the second character represents the low nibble (<code>b & 0x0f</code>). <BR>
   * The byte at <code>data[offset]</code> is represented by the first two
   * characters in the returned string.
   * 
   * @param buffer
   *          the ByteBuffer to convert
   * @param sb
   *          the StringBuilder to which conversion result is appended
   */
  public static void toHexString(ByteBuffer buffer, StringBuilder sb) {
    if (wrapsFullArray(buffer)) {
      final byte[] bytes = buffer.array();
      sb.append(toHexChars(bytes, 0, bytes.length));
    }
    else {
      int pos = buffer.position();
      final int limit = buffer.limit();
      while (pos++ < limit) {
        byte b = buffer.get(pos);
        toHexChars(b, sb);
      }
    }
  }

  static final int toHexChars(final byte b, final char[] chars, int index) {
    final int highByte = (b & 0xf0) >>> 4;
    final int lowByte = (b & 0x0f);
    chars[index] = HEX_DIGITS[highByte];
    chars[++index] = HEX_DIGITS[lowByte];
    return (index + 1);
  }

  static void toHexChars(final byte b, StringBuilder sb) {
    final int highByte = (b & 0xf0) >>> 4;
    final int lowByte = (b & 0x0f);
    sb.append(HEX_DIGITS[highByte]);
    sb.append(HEX_DIGITS[lowByte]);
  }

  /**
   * Convert a hexidecimal string generated by toHexString() back into a byte
   * array.
   * 
   * @param s
   *          String to convert
   * @param offset
   *          starting character (zero based) to convert
   * @param length
   *          number of characters to convert
   * 
   * @return the converted byte array
   * 
   * @throws IllegalArgumentException
   *           if the given string is not a hex-encoded one
   */
  public static byte[] fromHexString(final String s, int offset,
      final int length) {
    int j = 0;
    final byte[] bytes;
    if ((length % 2) != 0) {
      // prepend a zero at the start
      bytes = new byte[(length + 1) >>> 1];
      final int lowByte = hexDigitToBinary(s.charAt(offset));
      bytes[j++] = (byte)(lowByte & 0x0f);
      offset++;
    }
    else {
      bytes = new byte[length >>> 1];
    }

    final int end = offset + length;
    while (offset < end) {
      final int highByte = hexDigitToBinary(s.charAt(offset++));
      final int lowByte = hexDigitToBinary(s.charAt(offset++));
      bytes[j++] = (byte)(((highByte << 4) & 0xf0) | (lowByte & 0x0f));
    }
    return bytes;
  }

  static final int hexDigitToBinary(char c) {
    if (c >= '0' && c <= '9') {
      return c - '0';
    }
    else if (c >= 'a' && c <= 'f') {
      return c - 'a' + 10;
    }
    else if (c >= 'A' && c <= 'F') {
      return c - 'A' + 10;
    }
    else {
      throw new IllegalArgumentException("Illegal hexadecimal char=" + c);
    }
  }

  public static void setCommonRuntimeException(CommonRunTimeException e) {
    cre = e;
  }

  public static RuntimeException newRuntimeException(String message,
      Throwable cause) {
    return cre.newRunTimeException(message, cause);
  }

  public static void initLog4J(String logFile,
      Level level) throws IOException {
    // set the log file location
    Properties props = new Properties();
    InputStream in = ClientSharedUtils.class.getResourceAsStream(
        "/store-log4j.properties");
    try {
      props.load(in);
    } finally {
      in.close();
    }

    // override file location and level
    if (level != null) {
      String levelStr = "INFO";
      // convert to log4j level
      if (level == Level.SEVERE) {
        levelStr = "ERROR";
      } else if (level == Level.WARNING) {
        levelStr = "WARN";
      } else if (level == Level.INFO || level == Level.CONFIG) {
        levelStr = "INFO";
      } else if (level == Level.FINE || level == Level.FINER ||
          level == Level.FINEST) {
        levelStr = "TRACE";
      } else if (level == Level.ALL) {
        levelStr = "DEBUG";
      } else if (level == Level.OFF) {
        levelStr = "OFF";
      }
      if (logFile != null) {
        props.setProperty("log4j.rootCategory", levelStr + ", file");
      } else {
        props.setProperty("log4j.rootCategory", levelStr + ", console");
      }
    }
    if (logFile != null) {
      props.setProperty("log4j.appender.file.file", logFile);
    }
    // lastly override with any user provided properties file
    in = ClientSharedUtils.class.getResourceAsStream("/log4j.properties");
    if (in != null) {
      Properties setProps = new Properties();
      try {
        setProps.load(in);
      } finally {
        in.close();
      }
      props.putAll(setProps);
    }
    LogManager.resetConfiguration();
    PropertyConfigurator.configure(props);
  }

  public static void initLogger(String loggerName, String logFile,
      boolean initLog4j, Level level, final Handler handler) {
    clearLogger();
    if (initLog4j) {
      try {
        initLog4J(logFile, level);
      } catch (IOException ioe) {
        throw newRuntimeException(ioe.getMessage(), ioe);
      }
    }
    Logger log = Logger.getLogger(loggerName);
    log.addHandler(handler);
    log.setLevel(level);
    log.setUseParentHandlers(false);
    logger = log;
  }

  public static void setLogger(Logger log) {
    clearLogger();
    logger = log;
  }

  public static Logger getLogger() {
    return logger;
  }

  public static final long MAG_MASK = 0xFFFFFFFFL;

  /**
   * Returns the internal int[] magnitude from given BigInteger, if possble,
   * else returns null.
   */
  public static int[] getBigIntInternalMagnitude(final BigInteger val) {
    if (bigIntMagnitude != null) {
      try {
        return (int[])bigIntMagnitude.get(val);
      } catch (Exception ex) {
        throw newRuntimeException("unexpected exception", ex);
      }
    }
    else {
      return null;
    }
  }

  public static int getBigIntMagnitudeSizeInBytes(final int[] magnitude) {
    final int size_1 = magnitude.length - 1;
    if (size_1 >= 0) {
      int numBytes = size_1 << 2;
      // check number of bytes encoded by first int (most significant value)
      final long firstInt = (magnitude[0] & MAG_MASK);
      return numBytes + numBytesWithoutZeros(firstInt);
    }
    return 0;
  }

  /**
   * Get the number of bytes occupied in the given unsigned integer value.
   */
  public static final int numBytesWithoutZeros(final long value) {
    if (value <= 0xFF) {
      return 1;
    }
    if (value <= 0xFFFF) {
      return 2;
    }
    if (value <= 0xFFFFFF) {
      return 3;
    }
    return 4;
  }

  public static void clear() {
    clearLogger();
    socketKeepAliveIdleWarningLogged = false;
    socketKeepAliveIntvlWarningLogged = false;
    socketKeepAliveCntWarningLogged = false;
  }

  private static void clearLogger() {
    final Logger log = logger;
    if (log != null) {
      logger = DEFAULT_LOGGER;
      for (Handler h : log.getHandlers()) {
        log.removeHandler(h);
        // try and close the handler ignoring any exceptions
        try {
          h.close();
        } catch (Exception ex) {
          // ignore
        }
      }
    }
  }

  /**
   * Get proper string for an an object including arrays with upto one dimension
   * of arrays.
   */
  public static void objectStringNonRecursive(Object obj, StringBuilder sb) {
    if (obj instanceof Object[]) {
      sb.append('(');
      boolean first = true;
      for (Object o : (Object[])obj) {
        if (!first) {
          sb.append(',');
          sb.append(o);
        }
        else {
          first = false;
          // for GemFireXD show the first byte[] for byte[][] storage
          objectStringWithBytes(o, sb);
        }
      }
      sb.append(')');
    }
    else {
      objectStringWithBytes(obj, sb);
    }
  }
  
  /** Get proper string for an object including arrays. */
  public static void objectString(Object obj, StringBuilder sb) {
    if (obj instanceof Object[]) {
      sb.append('(');
      boolean first = true;
      for (Object o : (Object[])obj) {
        if (!first) {
          sb.append(',');
        }
        else {
          first = false;
        }
        objectString(o, sb);
      }
      sb.append(')');
    }
    else {
      objectStringWithBytes(obj, sb);
    }
  }

  private static void objectStringWithBytes(Object obj, StringBuilder sb) {
    if (obj instanceof byte[]) {
      sb.append('(');
      boolean first = true;
      final byte[] bytes = (byte[])obj;
      int numBytes = 0;
      for (byte b : bytes) {
        if (!first) {
          sb.append(',');
        }
        else {
          first = false;
        }
        sb.append(b);
        // terminate with ... for large number of bytes
        if (numBytes++ >= 5000 && numBytes < bytes.length) {
          sb.append(" ...");
          break;
        }
      }
      sb.append(')');
    }
    else {
      sb.append(obj);
    }
  }

  public static boolean equalBuffers(final ByteBuffer connToken,
      final ByteBuffer otherId) {
    if (connToken == otherId) {
      return true;
    }

    // this.connId always wraps full array
    assert ClientSharedUtils.wrapsFullArray(connToken);

    if (otherId != null) {
      if (ClientSharedUtils.wrapsFullArray(otherId)) {
        return Arrays.equals(otherId.array(), connToken.array());
      }
      else {
        // don't create intermediate byte[]
        return equalBuffers(connToken.array(), otherId);
      }
    }
    else {
      return false;
    }
  }

  public static boolean equalBuffers(final byte[] bytes,
      final ByteBuffer buffer) {
    final int len = bytes.length;
    if (len != buffer.remaining()) {
      return false;
    }
    // read in longs to minimize ByteBuffer get() calls
    int index = 0;
    int pos = buffer.position();
    final int endPos = (pos + len);
    // round off to nearest factor of 8 to read in longs
    final int endRound8Pos = (len % 8) != 0 ? (endPos - 8) : endPos;
    byte b;
    if (buffer.order() == ByteOrder.BIG_ENDIAN) {
      while (pos < endRound8Pos) {
        // splitting into longs is faster than reading one byte at a time even
        // though it costs more operations (about 20% in micro-benchmarks)
        final long v = buffer.getLong(pos);
        b = (byte)(v >>> 56);
        if (b != bytes[index++]) {
          return false;
        }
        b = (byte)(v >>> 48);
        if (b != bytes[index++]) {
          return false;
        }
        b = (byte)(v >>> 40);
        if (b != bytes[index++]) {
          return false;
        }
        b = (byte)(v >>> 32);
        if (b != bytes[index++]) {
          return false;
        }
        b = (byte)(v >>> 24);
        if (b != bytes[index++]) {
          return false;
        }
        b = (byte)(v >>> 16);
        if (b != bytes[index++]) {
          return false;
        }
        b = (byte)(v >>> 8);
        if (b != bytes[index++]) {
          return false;
        }
        b = (byte)(v >>> 0);
        if (b != bytes[index++]) {
          return false;
        }

        pos += 8;
      }
    }
    else {
      while (pos < endRound8Pos) {
        // splitting into longs is faster than reading one byte at a time even
        // though it costs more operations (about 20% in micro-benchmarks)
        final long v = buffer.getLong(pos);
        b = (byte)(v >>> 0);
        if (b != bytes[index++]) {
          return false;
        }
        b = (byte)(v >>> 8);
        if (b != bytes[index++]) {
          return false;
        }
        b = (byte)(v >>> 16);
        if (b != bytes[index++]) {
          return false;
        }
        b = (byte)(v >>> 24);
        if (b != bytes[index++]) {
          return false;
        }
        b = (byte)(v >>> 32);
        if (b != bytes[index++]) {
          return false;
        }
        b = (byte)(v >>> 40);
        if (b != bytes[index++]) {
          return false;
        }
        b = (byte)(v >>> 48);
        if (b != bytes[index++]) {
          return false;
        }
        b = (byte)(v >>> 56);
        if (b != bytes[index++]) {
          return false;
        }

        pos += 8;
      }
    }
    while (pos < endPos) {
      if (bytes[index] != buffer.get(pos)) {
        return false;
      }
      pos++;
      index++;
    }
    return true;
  }
}
