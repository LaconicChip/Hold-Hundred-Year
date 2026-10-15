package com.holdhundredyear.common;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把权威 `db/schema.sql`（MySQL 方言）翻译成 H2 能执行的语句，只服务"解压即玩"的 portable 档。
 * 表结构仍然只有 schema.sql 一份权威——这里只做方言转换，绝不另存一份 H2 建表脚本，
 * 否则迟早漂移（本项目为此专门删过一次双份脚本）。
 *
 * <p>三处必须的差异：
 * <ol>
 *   <li>`ENGINE=InnoDB DEFAULT CHARSET=utf8mb4` 尾巴：H2 不认，去掉
 *       （InnoDB / utf8mb4 本来就是 MySQL 默认值，去掉不改变语义）；</li>
 *   <li>建表语句里的行内 `INDEX xxx (cols)`：H2 不支持，拆成独立的
 *       `CREATE INDEX IF NOT EXISTS`（文件库第二次启动要能重跑）；</li>
 *   <li>索引/约束名的作用域：MySQL 是"表内唯一"，H2 是"全库唯一"——`idx_user_day` 在 5 张表里
 *       重复出现，直接建会撞名，所以一律加表名前缀。名字只是标识，不影响语义。</li>
 * </ol>
 *
 * <p>`UNIQUE KEY name (cols)` 同理换成标准的 `CONSTRAINT <表名>_<name> UNIQUE (cols)`。
 */
public final class MysqlSchemaForH2 {

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)\\s*\\((.*)\\)\\s*(ENGINE=.*?)?;\\s*$");
    private static final Pattern INLINE_INDEX = Pattern.compile(
            "(?i)^\\s*(?:INDEX|KEY)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*,?\\s*$");
    private static final Pattern INLINE_UNIQUE = Pattern.compile(
            "(?i)^\\s*UNIQUE\\s+KEY\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*,?\\s*$");

    /** 逐条可执行语句：建表在前，索引在后。 */
    public static List<String> statements(String mysqlDdl) {
        List<String> tables = new ArrayList<>();
        List<String> indexes = new ArrayList<>();
        for (String raw : mysqlDdl.split(";")) {
            String stmt = stripCommentLines(raw).trim();
            if (stmt.isEmpty()) {
                continue;
            }
            Matcher m = CREATE_TABLE.matcher(stmt + ";");
            if (!m.matches()) {
                tables.add(stmt);
                continue;
            }
            String table = m.group(1);
            List<String> columns = new ArrayList<>();
            for (String line : m.group(2).split("\\n")) {
                String t = line.trim();
                if (t.isEmpty()) {
                    continue;
                }
                Matcher uk = INLINE_UNIQUE.matcher(t);
                if (uk.matches()) {
                    columns.add("CONSTRAINT " + table + "_" + uk.group(1) + " UNIQUE (" + uk.group(2) + ")");
                    continue;
                }
                Matcher ix = INLINE_INDEX.matcher(t);
                if (ix.matches()) {
                    indexes.add("CREATE INDEX IF NOT EXISTS " + table + "_" + ix.group(1)
                            + " ON " + table + " (" + ix.group(2) + ")");
                    continue;
                }
                columns.add(t.replaceAll(",$", ""));
            }
            tables.add("CREATE TABLE IF NOT EXISTS " + table + " (" + String.join(", ", columns) + ")");
        }
        tables.addAll(indexes);
        return tables;
    }

    /**
     * 去掉行首的 `--` 注释行。注意不能整段丢弃：schema.sql 的第一行就是文件注释，
     * 而它和紧跟其后的 `CREATE TABLE sys_user` 属于同一段（同以分号结尾），整段丢弃会
     * 把第一张表直接弄没（H2 档表现为 "Table sys_user not found"）。
     */
    private static String stripCommentLines(String chunk) {
        StringBuilder kept = new StringBuilder();
        for (String line : chunk.split("\\n")) {
            if (line.trim().startsWith("--")) {
                continue;
            }
            kept.append(line).append('\n');
        }
        return kept.toString();
    }

    private MysqlSchemaForH2() {
    }
}
