package org.opensource.clearpool.jta;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Random;

import javax.transaction.Transaction;
import javax.transaction.UserTransaction;

import junit.framework.TestCase;

import org.opensource.clearpool.core.ClearPoolDataSource;
import org.opensource.clearpool.jta.UserTransactionImpl;
import org.opensource.clearpool.logging.PoolLoggerFactory;

/**
 * Note: <br />
 * 1.replace jdbcClass which is in clearpool-test-jta-unique.xml with your database's jdbc-class
 * please; <br />
 * 2.replace url which is in clearpool-test-jta-unique.xml with your database's url please; <br />
 * 3.replace user which is in clearpool-test-jta-unique.xml with your database's user please; <br />
 * 4.replace password which is in clearpool-test-jta-unique.xml with your database's password
 * please.
 * 
 * @author xionghui
 * @date 16.08.2014
 * @version 1.0
 */
public class JtaSuspend extends TestCase {
  private static final Random RANDOM = new Random();

  private static ClearPoolDataSource dataSource;

  private String tableName;

  static {
    System.setProperty(PoolLoggerFactory.LOG_UNABLE, "true");
  }

  @Override
  public void setUp() throws Exception {
    dataSource = new ClearPoolDataSource();
    dataSource.initPath("clearpool/jta/clearpool-test-jta-unique.xml");
    this.init();
  }

  private void init() throws Exception {
    Connection con = dataSource.getConnection();
    Statement st = con.createStatement();
    int count = 0;
    for (;;) {
      this.tableName = "clearpool_jta_" + RANDOM.nextInt(1000000000);
      try {
        st.execute("select 1 from " + this.tableName);
      } catch (SQLException e) {
        break;
      }
      count++;
      if (count > 100000000) {
        throw new RuntimeException(
            "you got too many tables which's name begin with clearpool_jta_ in the database");
      }
    }
    st.execute("create table " + this.tableName + "(id int primary key,name varchar(10))");
    System.out.println("table name is " + this.tableName);
    con.close();
  }

  public void testJta_suspend() throws Exception {
    if (this.tableName == null) {
      return;
    }
    Connection con = dataSource.getConnection();
    // con.setAutoCommit(false);
    UserTransaction tx = new UserTransactionImpl();
    Statement st = con.createStatement();
    tx.begin();
    st.execute("insert into " + this.tableName + "(id,name) values(1,'name1')");
    System.out.print("between the jtx:");
    this.showQueryResult(1);
    Transaction ac = ((UserTransactionImpl) tx).suspend();
    System.out.println("test suspend:");
    st.execute("insert into " + this.tableName + "(id,name) values(2,'name2')");
    this.showQueryResult(2);
    ((UserTransactionImpl) tx).resume(ac);
    tx.commit();
    System.out.print("end the jtx:");
    this.showQueryResult(1);
    this.showQueryResult(2);
    con.close();
  }

  private void showQueryResult(int id) throws Exception {
    Connection con = dataSource.getConnection();
    Statement st = con.createStatement();
    ResultSet rs = st.executeQuery("select name from " + this.tableName + " where id=" + id);
    while (rs.next()) {
      System.out.println(rs.getString(1));
      return;
    }
    System.out.println(" has no result");
    con.close();
  }

  @Override
  public void tearDown() throws Exception {
    if (this.tableName != null) {
      try {
        Connection con = dataSource.getConnection();
        Statement st = con.createStatement();
        st.execute("drop table " + this.tableName);
      } catch (SQLException e) {
        System.out.println(e.getMessage());
      }
    }
    dataSource.close();
  }
}
