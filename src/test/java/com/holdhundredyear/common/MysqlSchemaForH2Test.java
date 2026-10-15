package com.holdhundredyear.common;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "解压即玩"的可行性契约：权威 schema.sql 经 {@link MysqlSchemaForH2} 翻译后，必须能在
 * H2(MySQL 兼容模式) 上建出全部 16 张表，并且游戏真正依赖的三种 MySQL 写法照常工作——
 * `INSERT IGNORE`（播种 stock_info）、`SELECT ... FOR UPDATE`（交易悲观锁）、
 * `TINYINT(1)` 读回 Boolean（halted / is_st / is_read）。
 *
 * <p>任何一条断了，portable 档就不能发出去（宁可让大家用 MySQL，也不要一个跑起来的假版本）。
 */
class MysqlSchemaForH2Test {

    private static final String URL = "jdbc:h2:mem:schema_for_h2;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @Test
    void translatedSchemaCreatesAllTablesAndKeepsMysqlSemantics() throws Exception {
        List<String> statements = MysqlSchemaForH2.statements(schemaSql());
        try (Connection c = DriverManager.getConnection(URL, "sa", "")) {
            exec(c, statements);
            exec(c, statements); // 文件库第二次启动会重跑：必须幂等
            assertEquals(16, tableCount(c), "all 16 tables must exist on H2");

            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO stock_info (id, stock_code, stock_name, sector, base_price, beta, index_weight) "
                        + "VALUES (1, '600001', 'A', 'baijiu', 100, 1, 1)");
                s.execute("INSERT IGNORE INTO stock_info (id, stock_code, stock_name, sector, base_price, beta, index_weight) "
                        + "VALUES (1, '600001', 'B', 'baijiu', 100, 1, 1)");
            }
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                    "SELECT stock_name, (SELECT COUNT(*) FROM stock_info) AS n FROM stock_info WHERE id = 1")) {
                assertTrue(rs.next(), "row must exist");
                assertEquals("A", rs.getString("stock_name"), "INSERT IGNORE must swallow the duplicate, not overwrite");
                assertEquals(1, rs.getInt("n"), "no extra row from the ignored insert");
            }

            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO sys_user (id, username, password, balance, debt, shark_debt) "
                        + "VALUES (1, 'u', 'p', 100000, 0, 0)");
                s.execute("INSERT INTO user_stock_state (user_id, stock_id, prev_close, open_price, high_price, "
                        + "low_price, current_price, limit_up_streak, halted, is_st, gap_pct, day_change_pct, version) "
                        + "VALUES (1, 1, 100, 100, 100, 100, 100, 0, 0, 0, 0, 0, 0)");
            }
            c.setAutoCommit(false);
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                    "SELECT id, balance FROM sys_user WHERE id = 1 FOR UPDATE")) {
                assertTrue(rs.next(), "SELECT ... FOR UPDATE must work on H2 too");
            }
            c.rollback();
            c.setAutoCommit(true);

            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                    "SELECT halted, is_st, version FROM user_stock_state WHERE user_id = 1 AND stock_id = 1")) {
                assertTrue(rs.next());
                assertFalse(rs.getBoolean("halted"), "TINYINT(1) must still read back as boolean");
                assertFalse(rs.getBoolean("is_st"));
                assertEquals(0, rs.getInt("version"));
            }
        }
    }

    /** MySQL 的索引名只在表内唯一，H2 是全库唯一：翻译必须加表名前缀，否则 idx_user_day 撞 5 次。 */
    @Test
    void indexAndConstraintNamesAreNamespacedByTable() {
        List<String> statements = MysqlSchemaForH2.statements(schemaSql());
        long inlineIndexes = statements.stream().filter(s -> s.contains("INDEX idx_") && !s.contains("ON ")).count();
        assertEquals(0, inlineIndexes, "no inline INDEX may survive the translation");
        assertTrue(statements.stream().anyMatch(s -> s.startsWith("CREATE INDEX IF NOT EXISTS user_message_idx_user_day")
                || s.contains("_idx_user_day")), "indexes must be renamed to <table>_<index>");
        assertTrue(statements.stream().anyMatch(s -> s.contains("CONSTRAINT user_position_uk_user_stock UNIQUE")),
                "UNIQUE KEY must become a per-table named CONSTRAINT");
    }

    private static List<String> schemaSqlToStatements() {
        return MysqlSchemaForH2.statements(schemaSql());
    }

    private static String schemaSql() {
        try (var in = new ClassPathResource("db/schema.sql").getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("schema.sql must be on the classpath", e);
        }
    }

    private static void exec(Connection c, List<String> statements) throws Exception {
        try (Statement s = c.createStatement()) {
            for (String sql : statements) {
                s.execute(sql);
            }
        }
    }

    private static int tableCount(Connection c) throws Exception {
        try (Statement s = c.createStatement();
             // DATABASE_TO_LOWER 会把模式名也变小写，所以比较前先 UPPER()
             ResultSet rs = s.executeQuery(
                     "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_SCHEMA) = 'PUBLIC'")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
