package dev.roomlock.booking.support;

import java.util.List;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test isolation without {@code @Transactional}: empties every application table (rule T-6). */
@TestComponent
public class DatabaseCleaner {

  private static final String APPLICATION_TABLES =
      """
      SELECT tablename FROM pg_tables
      WHERE schemaname = current_schema() AND tablename <> 'flyway_schema_history'
      """;

  private final JdbcTemplate jdbc;

  public DatabaseCleaner(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void truncateAll() {
    List<String> tables = jdbc.queryForList(APPLICATION_TABLES, String.class);
    if (tables.isEmpty()) {
      return;
    }
    jdbc.execute("TRUNCATE TABLE " + String.join(", ", tables) + " CASCADE");
  }
}
