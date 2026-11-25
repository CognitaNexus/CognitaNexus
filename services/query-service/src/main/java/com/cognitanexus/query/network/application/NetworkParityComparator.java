package com.cognitanexus.query.network.application;

import com.cognitanexus.query.network.domain.ParityDiffReport;
import com.cognitanexus.query.network.domain.ParityDiffReport.FieldDifference;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 新旧查询对照器：比较同一请求在 PostgreSQL 与图库上的响应。
 *
 * <p>依据：ADR-005 §6.4、§10；SRS/AC {@code AC-IMPL-IN01-01/02/03}。对照的意义是把「两条实现口径
 * 是否一致」变成可执行断言，而不是靠人眼抽查两次响应。比较对象是<b>两条路径各自产出的响应体</b>，
 * 而不是「图领域对象 vs PG 领域对象」——因为用户看到的是响应，字段名、顺序、空值形态都算契约的一部分。
 *
 * <p>刻意<b>只不</b>比较一个字段：{@code generatedAt}——两条路径各自取 {@code Instant.now()}，永远
 * 不同，它不是业务结论。
 *
 * <p>{@code withinLimits}（本次搜索实际载入的节点/边数）<b>参与比较</b>。它曾经被排除在外，因为图侧
 * 路径查询用「路径节点数/跳数」冒充加载规模，与 PG 口径不同；把这种偏差屏蔽掉等于让对照器给一个
 * 错口径盖章。现在两侧都由各自的搜索子图统计口径产出（{@code ScopedGraphLoader} /
 * {@code CollaborationGraphService} + {@code GraphPathResult}），差异就是真差异（例如图侧少扩了一层、
 * 或二部图分支把没有合作边的作者对算进加载规模），必须被报出来。
 *
 * <p>其余字段全部逐层比较，包括列表<b>顺序</b>：节点按 BFS 层次与规范 ID、边按两端 ID、路径按跳序。
 * 顺序被纳入契约的理由与上面相同——同一请求两次对答顺序不同，会被读成「图在变」。
 *
 * <p>差异报告有上限：{@link #MAX_REPORTED_DIFFERENCES} 条之后只累计条数，避免一次大面积不一致
 * 把日志冲掉。上限内的明细已足够定位首个分叉点。
 */
public class NetworkParityComparator {

  /** 不参与一致性比较的诊断字段（见类注释）：只排除墙钟时间。 */
  public static final Set<String> DIAGNOSTIC_FIELDS = Set.of("generatedAt");

  /** 单次报告最多列出的差异条数。 */
  public static final int MAX_REPORTED_DIFFERENCES = 32;

  /** 「该字段只在一侧存在」的占位值。必须原样输出，不能像普通字符串那样加引号。 */
  private static final String MISSING = "<missing>";

  public ParityDiffReport compare(
      String operation,
      String requestKey,
      Map<String, Object> postgres,
      Map<String, Object> graph) {
    List<FieldDifference> differences = new ArrayList<>();
    int[] truncated = {0};
    compareValues("", postgres, graph, differences, truncated);
    return new ParityDiffReport(
        operation, requestKey, differences, truncated[0], nodeCount(postgres), edgeCount(postgres));
  }

  private void compareValues(
      String path,
      Object postgres,
      Object graph,
      List<FieldDifference> differences,
      int[] truncated) {
    if (postgres == null || graph == null) {
      if (postgres != graph) {
        add(differences, truncated, path, postgres, graph);
      }
      return;
    }
    if (postgres instanceof Map<?, ?> postgresMap && graph instanceof Map<?, ?> graphMap) {
      compareMaps(path, postgresMap, graphMap, differences, truncated);
      return;
    }
    if (postgres instanceof List<?> postgresList && graph instanceof List<?> graphList) {
      compareLists(path, postgresList, graphList, differences, truncated);
      return;
    }
    if (postgres instanceof Number postgresNumber && graph instanceof Number graphNumber) {
      // 用数值比较而不是 equals：Long 1 与 Integer 1 是同一个业务值，类型差异不算契约不一致。
      if (new BigDecimal(postgresNumber.toString())
              .compareTo(new BigDecimal(graphNumber.toString()))
          != 0) {
        add(differences, truncated, path, postgres, graph);
      }
      return;
    }
    if (!Objects.equals(postgres, graph)) {
      add(differences, truncated, path, postgres, graph);
    }
  }

  private void compareMaps(
      String path,
      Map<?, ?> postgres,
      Map<?, ?> graph,
      List<FieldDifference> differences,
      int[] truncated) {
    TreeSet<String> keys = new TreeSet<>();
    for (Object key : postgres.keySet()) {
      keys.add(String.valueOf(key));
    }
    for (Object key : graph.keySet()) {
      keys.add(String.valueOf(key));
    }
    for (String key : keys) {
      if (DIAGNOSTIC_FIELDS.contains(key)) {
        continue;
      }
      String childPath = path.isEmpty() ? key : path + "." + key;
      boolean inPostgres = containsKey(postgres, key);
      boolean inGraph = containsKey(graph, key);
      if (!inPostgres || !inGraph) {
        // 「少一个字段」比「值不同」更严重：调用方按字段取数会直接拿到 null。
        add(
            differences,
            truncated,
            childPath,
            inPostgres ? postgres.get(key) : MISSING,
            inGraph ? graph.get(key) : MISSING);
        continue;
      }
      compareValues(childPath, postgres.get(key), graph.get(key), differences, truncated);
    }
  }

  private void compareLists(
      String path,
      List<?> postgres,
      List<?> graph,
      List<FieldDifference> differences,
      int[] truncated) {
    if (postgres.size() != graph.size()) {
      add(differences, truncated, path + ".size", postgres.size(), graph.size());
    }
    int shared = Math.min(postgres.size(), graph.size());
    for (int index = 0; index < shared; index++) {
      compareValues(
          path + "[" + index + "]", postgres.get(index), graph.get(index), differences, truncated);
    }
  }

  private static boolean containsKey(Map<?, ?> map, String key) {
    for (Object candidate : map.keySet()) {
      if (candidate != null && String.valueOf(candidate).equals(key)) {
        return true;
      }
    }
    return false;
  }

  private static void add(
      List<FieldDifference> differences,
      int[] truncated,
      String path,
      Object postgres,
      Object graph) {
    if (differences.size() >= MAX_REPORTED_DIFFERENCES) {
      truncated[0]++;
      return;
    }
    differences.add(new FieldDifference(path, format(postgres), format(graph)));
  }

  private static String format(Object value) {
    if (value == null) {
      return "null";
    }
    if (MISSING.equals(value)) {
      return MISSING;
    }
    String text = value instanceof String string ? "\"" + string + "\"" : String.valueOf(value);
    return text.length() <= 400 ? text : text.substring(0, 400) + "…";
  }

  private static int nodeCount(Map<String, Object> response) {
    return sizeOf(response, "nodes");
  }

  private static int edgeCount(Map<String, Object> response) {
    return sizeOf(response, "edges");
  }

  private static int sizeOf(Map<String, Object> response, String key) {
    if (response == null) {
      return 0;
    }
    Object value = response.get(key);
    return value instanceof List<?> list ? list.size() : 0;
  }
}
