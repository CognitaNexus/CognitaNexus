package com.cognitanexus.ingestion.collect.infrastructure;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * PostgreSQL {@code text[]} 与 Java {@code String[]} 的映射。
 *
 * <p>依据：编码规范 §5.1。字段白名单是 text[] 而非 jsonb，因为它在库级可参与 CHECK 与数组包含
 * 判断；但 JDBC 不认识这个类型，必须由类型处理器显式转换。写回时用
 * {@code Connection.createArrayOf} 而不是拼字符串，避免元素内的引号破坏 SQL。
 */
@MappedTypes(String[].class)
public class StringArrayTypeHandler extends BaseTypeHandler<String[]> {

  @Override
  public void setNonNullParameter(
      PreparedStatement ps, int i, String[] parameter, JdbcType jdbcType) throws SQLException {
    Connection connection = ps.getConnection();
    java.sql.Array array = connection.createArrayOf("text", parameter);
    try {
      ps.setArray(i, array);
    } finally {
      // 数组对象持有连接资源，不释放会在连接复用时泄漏
      array.free();
    }
  }

  @Override
  public String[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return toArray(rs.getArray(columnName));
  }

  @Override
  public String[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return toArray(rs.getArray(columnIndex));
  }

  @Override
  public String[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    return toArray(cs.getArray(columnIndex));
  }

  private static String[] toArray(java.sql.Array sqlArray) throws SQLException {
    if (sqlArray == null) {
      return new String[0];
    }
    try {
      Object raw = sqlArray.getArray();
      if (raw instanceof String[] strings) {
        return strings;
      }
      if (raw instanceof Object[] objects) {
        String[] result = new String[objects.length];
        for (int i = 0; i < objects.length; i++) {
          result[i] = objects[i] == null ? null : String.valueOf(objects[i]);
        }
        return result;
      }
      return new String[0];
    } finally {
      sqlArray.free();
    }
  }
}
