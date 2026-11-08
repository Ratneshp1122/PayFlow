package dev.ratnesh.payflow;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.*;

/** Optional timings: acquisition, statement execution, and commit are different distributions. */
public class ProfilingDataSource extends HikariDataSource {
  private MeterRegistry metrics;
  private boolean enabled;
  private final Map<String, String> catalog = new ConcurrentHashMap<>();

  public void configureProfiling(MeterRegistry metrics, boolean enabled) {
    this.metrics = metrics;
    this.enabled = enabled;
  }

  public Map<String, String> catalog() {
    return Map.copyOf(catalog);
  }

  @Override
  public Connection getConnection() throws SQLException {
    if (!enabled) return super.getConnection();
    long start = System.nanoTime();
    String result = "success";
    try {
      return wrapConnection(super.getConnection());
    } catch (SQLException e) {
      result = "failure";
      throw e;
    } finally {
      record("payflow.pool.acquire", "all", result, start);
    }
  }

  private void record(String metric, String id, String outcome, long start) {
    metrics
        .timer(metric, "statement", id, "outcome", outcome)
        .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
  }

  private Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  private Connection wrapConnection(Connection connection) {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              if (method.getName().equals("commit") || method.getName().equals("rollback")) {
                long start = System.nanoTime();
                String outcome = "success";
                try {
                  return invoke(connection, method, args);
                } catch (Throwable e) {
                  outcome = "failure";
                  throw e;
                } finally {
                  record("payflow.db." + method.getName(), "all", outcome, start);
                }
              }
              Object value = invoke(connection, method, args);
              if (value instanceof Statement statement) {
                String sql =
                    args != null && args.length > 0 && args[0] instanceof String
                        ? (String) args[0]
                        : null;
                return wrapStatement(statement, sql);
              }
              return value;
            });
  }

  private Statement wrapStatement(Statement statement, String preparedSql) {
    Class<?> type =
        statement instanceof CallableStatement
            ? CallableStatement.class
            : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
    return (Statement)
        Proxy.newProxyInstance(
            Statement.class.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              if (!method.getName().startsWith("execute")) return invoke(statement, method, args);
              String sql =
                  preparedSql != null
                      ? preparedSql
                      : args != null && args.length > 0 && args[0] instanceof String
                          ? (String) args[0]
                          : "batch";
              String normalized =
                  sql.replaceAll("'([^']|'')*'", "?")
                      .replaceAll("\\b\\d+\\b", "?")
                      .replaceAll("\\s+", " ")
                      .trim();
              String id = Rules.hash(normalized).substring(0, 16);
              if (catalog.size() < 256) catalog.putIfAbsent(id, normalized);
              else if (!catalog.containsKey(id)) id = "overflow";
              long start = System.nanoTime();
              String outcome = "success";
              try {
                return invoke(statement, method, args);
              } catch (Throwable e) {
                outcome = "failure";
                throw e;
              } finally {
                record("payflow.sql.execute", id, outcome, start);
              }
            });
  }
}

@Configuration
class ProfilingDatabaseConfiguration {
  @Bean
  @ConfigurationProperties("spring.datasource.hikari")
  ProfilingDataSource dataSource(
      DataSourceProperties properties,
      MeterRegistry registry,
      @Value("${payflow.profiling:false}") boolean enabled) {
    var ds = properties.initializeDataSourceBuilder().type(ProfilingDataSource.class).build();
    ds.configureProfiling(registry, enabled);
    return ds;
  }
}
