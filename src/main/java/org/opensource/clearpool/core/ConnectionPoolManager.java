package org.opensource.clearpool.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.sql.PooledConnection;

import org.opensource.clearpool.configuration.ConfigurationVO;
import org.opensource.clearpool.core.chain.BinaryHeap;
import org.opensource.clearpool.datasource.connection.CommonConnection;
import org.opensource.clearpool.datasource.factory.DataSourceAbstractFactory;
import org.opensource.clearpool.datasource.proxy.ConnectionProxy;
import org.opensource.clearpool.exception.ConnectionPoolException;
import org.opensource.clearpool.logging.PoolLogger;
import org.opensource.clearpool.logging.PoolLoggerFactory;

/**
 * This class save the connection to {@link #connectionChain},it's duty is to manage the pool.
 *
 * The pool will increment when there is no free connection in the pool's size is less than the max
 * pool size.
 *
 * @author xionghui
 * @date 26.07.2014
 * @version 1.0
 */
public class ConnectionPoolManager {
  private static final PoolLogger LOGGER = PoolLoggerFactory.getLogger(ConnectionPoolManager.class);

  private Lock lock = new ReentrantLock();
  private Condition notEmpty = this.lock.newCondition();

  private final BinaryHeap connectionChain = new BinaryHeap();;

  // save all the connection to close in case we shutdown the JVM
  private volatile Set<ConnectionProxy> connectionSet = new HashSet<ConnectionProxy>();

  // this is the sign of if the pool is removed
  private volatile boolean closed;

  private ConfigurationVO cfgVO;

  private AtomicInteger poolSize = new AtomicInteger();

  // record the peak of the connection in the pool.
  private int peakPoolSize;

  ConnectionPoolManager(ConfigurationVO cfgVO) {
    this.cfgVO = cfgVO;
  }

  /**
   * Init the pool by the corePoolsize of {@link #cfgVO}
   */
  void initPool() {
    int coreSize = this.cfgVO.getCorePoolSize();
    this.fillPool(coreSize);
    String tableName = this.cfgVO.getTestTableName();
    if (tableName != null) {
      this.initTestTable();
    }
  }

  /**
   * Return connection to the pool
   */
  public void entryPool(ConnectionProxy conProxy) {
    if (conProxy == null) {
      throw new NullPointerException();
    }
    this.lock.lock();
    try {
      this.connectionChain.add(conProxy);
      this.notEmpty.signal();
    } finally {
      this.lock.unlock();
    }
  }

  /**
   * Get a connection from the pool
   */
  public PooledConnection exitPool() throws SQLException {
    ConnectionProxy conProxy = null;
    for (;;) {
      this.lock.lock();
      try {
        do {
          conProxy = this.connectionChain.removeFirst();
          // if we couln't get a connection from the pool,we should get
          // new
          // connection.
          if (conProxy == null) {
            int maxIncrement = this.cfgVO.getMaxPoolSize() - this.poolSize.get();
            // if pool is full,we shouldn't grow it
            if (maxIncrement == 0) {
              if (this.cfgVO.getUselessConnectionException()) {
                throw new ConnectionPoolException("there is no connection left in the pool");
              } else {
                // wait for connection
                while (this.connectionChain.size() == 0) {
                  this.notEmpty.await();
                }
              }
            } else {
              this.fillPoolByAcquireIncrement();
            }
          }
        } while (conProxy == null);
      } catch (InterruptedException e) {
        LOGGER.error("exitPool error: ", e);
        throw new ConnectionPoolException(e);
      } finally {
        this.lock.unlock();
      }
      if (this.cfgVO.isTestBeforeUse()) {
        boolean isValid = this.checkTestTable(conProxy, false);
        if (!isValid) {
          this.decrementPoolSize();
          this.closeConnection(conProxy);
          this.incrementOneConnection();
          continue;
        }
      }
      break;
    }
    DataSourceAbstractFactory factory = this.cfgVO.getFactory();
    PooledConnection pooledConnection = factory.createPooledConnection(conProxy);
    return pooledConnection;
  }

  public ConnectionProxy exitPool(long period) {
    this.lock.lock();
    try {
      return this.connectionChain.removeIdle(period);
    } finally {
      this.lock.unlock();
    }
  }

  /**
   * fill the pool by acquireIncrement
   */
  private void fillPoolByAcquireIncrement() {
    int maxIncrement = this.cfgVO.getMaxPoolSize() - this.poolSize.get();
    // double check
    if (maxIncrement != 0) {
      int increment = this.cfgVO.getAcquireIncrement();
      if (increment > maxIncrement) {
        increment = maxIncrement;
      }
      this.fillPool(increment);
    }
  }

  /**
   * increment one connection
   */
  public void incrementOneConnection() {
    this.lock.lock();
    try {
      this.fillPool(1);
    } finally {
      this.lock.unlock();
    }
  }

  /**
   * fill the pool by poolNum
   */
  private void fillPool(int poolNum) {
    int retryTimes = this.cfgVO.getAcquireRetryTimes();
    for (int i = 0; i < poolNum; i++) {
      // try to get a connection
      ConnectionProxy conProxy = this.tryGetConnection(retryTimes);
      if (this.closed) {
        this.remove();
        return;
      }
      this.connectionChain.add(conProxy);
      this.handlePeakPoolSize(i + 1);
    }
    this.poolSize.addAndGet(poolNum);
  }

  /**
   * try retryTimes times to get a connection
   */
  private ConnectionProxy tryGetConnection(int retryTimes) {
    int count = 0;
    CommonConnection cmnCon = null;
    do {
      try {
        cmnCon = this.cfgVO.getDataSource().getCommonConnection();
      } catch (SQLException e) {
        LOGGER.error("try connect error(" + count++ + " time): ", e);
        if (count > retryTimes) {
          throw new ConnectionPoolException("get connection error" + e.getMessage());
        }
      }
    } while (cmnCon == null);
    ConnectionProxy conProxy = new ConnectionProxy(this, cmnCon);
    this.connectionSet.add(conProxy);
    return conProxy;
  }

  /**
   * Init test table by testTableName in {@link #cfgVO}
   */
  private void initTestTable() {
    int coreSize = this.cfgVO.getCorePoolSize();
    ConnectionProxy conProxy;
    if (coreSize > 0) {
      conProxy = this.connectionChain.removeFirst();
    } else {
      int retryTimes = this.cfgVO.getAcquireRetryTimes();
      conProxy = this.tryGetConnection(retryTimes);
    }
    try {
      this.checkTestTable(conProxy, true);
    } catch (ConnectionPoolException e) {
      LOGGER.error("initTestTable test: ", e);
      throw e;
    }
    if (coreSize > 0) {
      this.connectionChain.add(conProxy);
    } else {
      this.closeConnection(conProxy);
    }
  }

  public BinaryHeap getConnectionChain() {
    return this.connectionChain;
  }

  public Set<ConnectionProxy> getConnectionSet() {
    return this.connectionSet;
  }

  public ConfigurationVO getCfgVO() {
    return this.cfgVO;
  }

  public boolean isClosed() {
    return this.closed;
  }

  public boolean isNeedCollected() {
    return this.poolSize.get() > this.cfgVO.getCorePoolSize();
  }

  /**
   * Save the peak pool size
   */
  private void handlePeakPoolSize(int poolNum) {
    int size = this.poolSize.get() + poolNum;
    if (size > this.peakPoolSize) {
      this.peakPoolSize = size;
    }
  }

  public void decrementPoolSize() {
    this.poolSize.decrementAndGet();
  }

  public int getPoolSize() {
    return this.poolSize.get();
  }

  public int getPeakPoolSize() {
    return this.peakPoolSize;
  }

  /**
   * check if test table existed
   */
  public boolean checkTestTable(ConnectionProxy conProxy, boolean autoCreateTable) {
    PreparedStatement queryPreparedStatement = null;
    try {
      DataSourceAbstractFactory factory = this.cfgVO.getFactory();
      PooledConnection pooledConnection = factory.createPooledConnection(conProxy);
      Connection con = pooledConnection.getConnection();
      queryPreparedStatement = con.prepareStatement(this.cfgVO.getTestQuerySql());
      queryPreparedStatement.execute();
    } catch (SQLException e) {
      LOGGER.error(this.cfgVO.getTestQuerySql() + " error: ", e);
      if (autoCreateTable) {
        this.createTestTable(conProxy);
      } else {
        return false;
      }
    } finally {
      if (queryPreparedStatement != null) {
        try {
          queryPreparedStatement.close();
        } catch (SQLException e) {
          LOGGER.error("close queryPreparedStatement error: ", e);
        }
      }
    }
    return true;
  }

  /**
   * create test table if test table is not existed
   */
  private void createTestTable(ConnectionProxy conProxy) {
    PreparedStatement createPreparedStatement = null;
    try {
      DataSourceAbstractFactory factory = this.cfgVO.getFactory();
      PooledConnection pooledConnection = factory.createPooledConnection(conProxy);
      Connection con = pooledConnection.getConnection();
      createPreparedStatement = con.prepareStatement(this.cfgVO.getTestCreateSql());
      createPreparedStatement.execute();
      con.commit();
    } catch (SQLException e) {
      LOGGER.error("createTestTable error: ", e);
      throw new ConnectionPoolException(e);
    } finally {
      if (createPreparedStatement != null) {
        try {
          createPreparedStatement.close();
        } catch (SQLException e) {
          LOGGER.error("close createPreparedStatement error: ", e);
        }
      }
    }
  }

  /**
   * remove the connection of the free connection. note:we shouldn't close the using connection
   * because it may cause a exception when people is using it.
   */
  public void remove() {
    this.closed = true;
    Set<ConnectionProxy> tempSet = this.connectionSet;
    // help gc
    this.connectionSet = new HashSet<ConnectionProxy>();
    for (ConnectionProxy conProxy : tempSet) {
      this.closeConnection(conProxy);
    }
  }

  /**
   * close pool connection
   */
  public void closeConnection(ConnectionProxy conProxy) {
    if (conProxy != null) {
      try {
        conProxy.getConnection().close();
      } catch (SQLException e) {
        LOGGER.error("it cause a exception when we close a pool connection: ", e);
      }
      this.connectionSet.remove(conProxy);
    }
  }
}
