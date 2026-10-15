package com.holdhundredyear.config;

import com.holdhundredyear.common.MysqlSchemaForH2;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * "解压即玩"档的数据源：内嵌 H2 文件库（MySQL 兼容模式），朋友电脑上不用装 MySQL。
 *
 * <p>为什么在 DataSource 的工厂方法里建表：这样表结构一定在任何 Mapper 拿到这个 DataSource
 * 之前就绪（Spring 保证 bean 的构造顺序），比再挂一个初始化监听器可靠。文件库第二次启动会重跑
 * 建表语句，所以 {@link MysqlSchemaForH2} 产出的都是 `IF NOT EXISTS` 形式。
 *
 * <p>存档就是 `./data/hold_year.mv.db` 这一个文件——备份=复制文件，正好适合"发给朋友各玩各的"。
 * 开发与局域网那套仍然用真 MySQL（本项目的学习目标就是它的行锁与事务）：两套只差一个 profile，
 * SQL、实体、引擎代码一行都不分叉。
 */
@Configuration(proxyBeanMethods = false)
@Profile("portable")
public class PortableDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(PortableDataSourceConfig.class);

    @Bean
    public DataSource dataSource(@Value("${spring.datasource.url}") String url,
                                 @Value("${spring.datasource.username}") String username,
                                 @Value("${spring.datasource.password:}") String password,
                                 @Value("${spring.datasource.driver-class-name}") String driverClassName,
                                 @Value("${portable.schema-location:db/schema.sql}") String schemaLocation)
            throws SQLException, IOException {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setDriverClassName(driverClassName);
        dataSource.setPoolName("hold-year-portable-pool");
        // 单人本地玩也至少留几条：结算与交易会在同一请求里嵌套取连接，池子压成 1 会自己等死
        dataSource.setMaximumPoolSize(8);
        initializeSchema(dataSource, schemaLocation);
        log.info("portable storage ready (h2 file db in MySQL mode), schema={}", schemaLocation);
        return dataSource;
    }

    /** 读权威 schema.sql，翻译成 H2 语句后逐条执行（幂等，可重复启动）。 */
    private void initializeSchema(HikariDataSource dataSource, String schemaLocation)
            throws IOException, SQLException {
        String ddl;
        try (InputStream in = new ClassPathResource(schemaLocation).getInputStream()) {
            ddl = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        }
        List<String> statements = MysqlSchemaForH2.statements(ddl);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }
}
