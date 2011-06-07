package me.prettyprint.cassandra.connection;

import java.net.SocketException;
import java.util.concurrent.atomic.AtomicLong;

import me.prettyprint.hector.SystemProperties;
import me.prettyprint.hector.api.exceptions.HInvalidRequestException;
import me.prettyprint.hector.api.exceptions.HectorTransportException;

import org.apache.cassandra.thrift.Cassandra;
import org.apache.cassandra.thrift.InvalidRequestException;
import org.apache.commons.lang.StringUtils;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class HThriftClientImpl implements HThriftClient {

  private static Logger log = LoggerFactory.getLogger(HThriftClientImpl.class);

  private static final AtomicLong serial = new AtomicLong(0);

  final HCassandraHost cassandraHost;

  private final long mySerial;
  private final int timeout;
  private String keyspaceName;
  private long useageStartTime;

  private TTransport transport;
  private Cassandra.Client cassandraClient;

  public HThriftClientImpl(HCassandraHost cassandraHost) {
    this.cassandraHost = cassandraHost;
    this.timeout = getTimeout(cassandraHost);
    mySerial = serial.incrementAndGet();
  }

  /**
   * Returns a new Cassandra.Client on each invocation using the underlying transport
   *
   */
  public Cassandra.Client getCassandra() {
    if ( !isOpen() ) {
      throw new IllegalStateException("getCassandra called on client that was not open. You should not have gotten here.");
    }
    if ( cassandraClient == null ) {
      cassandraClient = new Cassandra.Client(new TBinaryProtocol(transport));
    }
    return cassandraClient;
  }

  public Cassandra.Client getCassandra(String keyspaceNameArg) {
    getCassandra();    
    if ( keyspaceNameArg != null && !StringUtils.equals(keyspaceName, keyspaceNameArg)) {
      if ( log.isDebugEnabled() )
        log.debug("keyspace reseting from {} to {}", keyspaceName, keyspaceNameArg);
      keyspaceName = keyspaceNameArg;
      try {
        cassandraClient.set_keyspace(keyspaceName);        
      } catch (InvalidRequestException ire) {
        throw new HInvalidRequestException(ire);
      } catch (TException e) {
        throw new HectorTransportException(e);
      } 

    }
    return cassandraClient;
  }

  public HThriftClient close() {
    if ( log.isDebugEnabled() ) {
      log.debug("Closing client {}", this);
    }
    if ( isOpen() ) {
      try {
        transport.flush();
      } catch (Exception e) {
        log.error("Could not flush transport (to be expected if the pool is shutting down) in close for client: " + toString(), e);
      } finally {
        try {
          transport.close();
        } catch (Exception e) {
          log.error("Error on transport close for client: " +toString(), e);
        }
      }
    }
    return this;
  }


  public HThriftClient open() {
    if ( isOpen() ) {
      throw new IllegalStateException("Open called on already open connection. You should not have gotten here.");
    }
    if ( log.isDebugEnabled() ) {
      log.debug("Creating a new thrift connection to {}", cassandraHost);
    }

    TSocket socket = new TSocket(cassandraHost.getHost(), cassandraHost.getPort(), timeout);
    if ( cassandraHost.getUseSocketKeepalive() ) {
      try {
        socket.getSocket().setKeepAlive(true);
      } catch (SocketException se) {
        throw new HectorTransportException("Could not set SO_KEEPALIVE on socket: ", se);
      }
    }
    if (cassandraHost.getUseThriftFramedTransport()) {
      transport = new TFramedTransport(socket);
    } else {
      transport = socket;
    }
    
    try {
      transport.open();
    } catch (TTransportException e) {
      // Thrift exceptions aren't very good in reporting, so we have to catch the exception here and
      // add details to it.
      log.debug("Unable to open transport to " + cassandraHost.getName());
      //clientMonitor.incCounter(Counter.CONNECT_ERROR);
      throw new HectorTransportException("Unable to open transport to " + cassandraHost.getName() +" , " +
          e.getLocalizedMessage(), e);
    }
    return this;
  }


  public boolean isOpen() {
    boolean open = false;
    if (transport != null) {
      open = transport.isOpen();
    }
    if ( log.isDebugEnabled() ) {
      log.debug("Transport open status {} for client {}", open, this);
    }
    return open;
  }

  /**
   * If CassandraHost was not null we use {@link CassandraHost#getCassandraThriftSocketTimeout()}
   * if it was greater than zero. Otherwise look for an environment
   * variable name CASSANDRA_THRIFT_SOCKET_TIMEOUT value.
   * If doesn't exist, returns 0.
   * @param cassandraHost
   */
  public int getTimeout(HCassandraHost cassandraHost) {
    int timeoutVar = 0;
    if ( cassandraHost != null && cassandraHost.getCassandraThriftSocketTimeout() > 0 ) {
      timeoutVar = cassandraHost.getCassandraThriftSocketTimeout();
    } else {
      String timeoutStr = System.getProperty(
          SystemProperties.CASSANDRA_THRIFT_SOCKET_TIMEOUT.toString());
      if (timeoutStr != null && timeoutStr.length() > 0) {
        try {
          timeoutVar = Integer.valueOf(timeoutStr);
        } catch (NumberFormatException e) {
          log.error("Invalid value for CASSANDRA_THRIFT_SOCKET_TIMEOUT", e);
        }
      }
    }
    return timeoutVar;
  }

  public void startToUse() {
      useageStartTime = System.currentTimeMillis();
  }
  
  /**
   * @return Time in MS since it was used.
   */
  public long getSinceLastUsed() {
	  return System.currentTimeMillis() - useageStartTime;
  }

  @Override
  public String toString() {
    return String.format(NAME_FORMAT, cassandraHost.getUrl(), mySerial);
  }

  /**
   * Compares the toString of these clients
   */
  @Override
  public boolean equals(Object obj) {
    return this.toString().equals(obj.toString());
  }



  private static final String NAME_FORMAT = "CassandraClient<%s-%d>";

  @Override
  public HCassandraHost getHCassandraHost() {
    return cassandraHost;
  }
}
