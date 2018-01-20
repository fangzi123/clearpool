package com.github.xionghuicoder.clearpool.datasource.proxy.dynamic;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

/**
 * {@link DatabaseMetaData DatabaseMetaData}的动态代理
 *
 * @author xionghui
 * @version 1.0.0
 * @since 1.0.0
 */
class DatabaseMetaDataHandler implements InvocationHandler {
  private static final String TOSTRING_METHOD = "toString";
  private static final String EQUALS_METHOD = "equals";
  private static final String HASHCODE_METHOD = "hashCode";
  private static final String GETCONNECTION_METHOD = "getConnection";

  private Connection con;
  private DatabaseMetaData metaData;

  DatabaseMetaDataHandler(Connection con, DatabaseMetaData metaData) {
    this.con = con;
    this.metaData = metaData;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    Object result;
    String methodName = method.getName();
    if (TOSTRING_METHOD.equals(methodName)) {
      result = this.toString();
    } else if (EQUALS_METHOD.equals(methodName)) {
      result = this.equals(args[0]);
    } else if (HASHCODE_METHOD.equals(methodName)) {
      result = this.hashCode();
    } else if (GETCONNECTION_METHOD.equals(methodName)) {
      result = this.getConnection();
    } else {
      try {
        result = method.invoke(this.metaData, args);
      } catch (InvocationTargetException e) {
        throw e.getTargetException();
      }
    }
    return result;
  }

  private Connection getConnection() {
    return this.con;
  }
}
