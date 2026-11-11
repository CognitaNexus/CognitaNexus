package com.cognitanexus.query.platform.persistence;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * PostgreSQL {@code text[]} 与 {@code String[]} 的互转。
 *
 * <p>依据：编码规范 §5.5。<b>本类必须在 application.yml 里通过
 * {@code mybatis.type-handlers-package} 显式声明扫描包才会生效</b>——MyBatis starter 默认只扫描
 * 自带包，不会注册业务包下的处理器。
 *
 * <p>漏配的后果不是「报错说缺处理器」，而是读取时把 {@code text[]} 当普通对象取出，
 * {@code java.sql.Array} 被直接塞进 String[] 构造参数，表现为反射层的
 * {@code argument type mismatch}，从错误消息几乎看不出根因。这一点在 ingestion-service 上
 * 已经实际踩过一次。
 *
 * <p>{@code java.sql.Array} 是 JDBC 驱动持有的资源，用完必须 free：不释放会在长事务中持续占用
 * 驱动侧的数组句柄。读取侧在 finally 中释放，写入侧创建后交给驱动，由驱动在语句结束时负责。
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

  private static String[] toArray(Array array) throws SQLException {
    if (array == null) {
      return new String[0];
    }
    try {
      Object raw = array.getArray();
      if (raw instanceof String[] strings) {
        return strings;
      }
      if (raw instanceof Object[] objects) {
        String[] strings = new String[objects.length];
        for (int i = 0; i < objects.length; i++) {
          strings[i] = objects[i] == null ? null : objects[i].toString();
        }
        return strings;
      }
      return new String[0];
    } finally {
      array.free();
    }
  }
}
