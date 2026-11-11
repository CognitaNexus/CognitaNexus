package com.cognitanexus.core.platform.persistence;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * PostgreSQL text[] 与 String[] 的互转。
 *
 * <p>依据：编码规范 §12。MyBatis 默认没有 text[] 处理器，直接用 getString 会读到
 * <code>{a,b}</code> 这种数组字面量而把它当成普通字符串，属于静默的错误解析。
 *
 * <p>写入时通过 Connection.createArrayOf 生成真正的数组对象，不使用字符串拼接，
 * 避免元素里含逗号或引号时被拆错。
 */
@MappedTypes(String[].class)
public class StringArrayTypeHandler extends BaseTypeHandler<String[]> {

  @Override
  public void setNonNullParameter(
      PreparedStatement ps, int i, String[] parameter, JdbcType jdbcType) throws SQLException {
    Array array = ps.getConnection().createArrayOf("text", parameter);
    ps.setArray(i, array);
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

  private String[] toArray(Array array) throws SQLException {
    if (array == null) {
      return null;
    }
    try {
      Object raw = array.getArray();
      if (raw instanceof String[] values) {
        return values;
      }
      return null;
    } finally {
      array.free();
    }
  }
}
