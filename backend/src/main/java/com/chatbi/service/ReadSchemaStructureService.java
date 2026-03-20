package com.chatbi.service;

import com.chatbi.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 读取数据库 Schema 结构的服务
 * 使用动态数据源，读取当前激活数据源的 Schema
 */
@Slf4j
@Service
public class ReadSchemaStructureService {

    private final DynamicJdbcTemplateProvider jdbcTemplateProvider;

    // 按数据源 ID 缓存 Schema，切换数据源时自动 miss
    private final ConcurrentHashMap<Long, SchemaResponse> schemaCache = new ConcurrentHashMap<>();

    public ReadSchemaStructureService(DynamicJdbcTemplateProvider jdbcTemplateProvider) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
    }

    /**
     * 获取当前激活数据源的 JdbcTemplate
     */
    private JdbcTemplate getJdbcTemplate() {
        return jdbcTemplateProvider.getJdbcTemplate();
    }

    /**
     * 获取数据库所有表的元数据（带缓存）
     */
    public SchemaResponse getDatabaseSchema() {
        Long activeId = jdbcTemplateProvider.getActiveDataSourceId();
        return schemaCache.computeIfAbsent(activeId, id -> {
            log.info("Schema 缓存未命中，从数据库读取, dataSourceId={}", id);
            String databaseName = getJdbcTemplate().execute((ConnectionCallback<String>) connection -> {
                try {
                    return connection.getCatalog();
                } catch (SQLException e) {
                    log.error("获取数据库名失败", e);
                    return "unknown";
                }
            });

            List<TableMetadata> tables = getAllTables();
            String formattedForAI = formatSchemaForAI(databaseName, tables);
            return new SchemaResponse(databaseName, tables, formattedForAI);
        });
    }

    /**
     * 清除指定数据源的 Schema 缓存
     */
    public void invalidateCache(Long dataSourceId) {
        schemaCache.remove(dataSourceId);
        log.info("清除数据源 {} 的 Schema 缓存", dataSourceId);
    }

    /**
     * 清除所有 Schema 缓存
     */
    public void invalidateAllCache() {
        schemaCache.clear();
        log.info("清除所有 Schema 缓存");
    }

    /**
     * 获取指定表的元数据
     */
    public TableMetadata getTableSchema(String tableName) {
        return getJdbcTemplate().execute((ConnectionCallback<TableMetadata>) connection -> {
            try {
                DatabaseMetaData metaData = connection.getMetaData();

                TableMetadata table = new TableMetadata();
                table.setTableName(tableName);
                table.setTableComment(getTableComment(metaData, tableName, connection.getCatalog()));
                table.setColumns(getColumns(metaData, tableName, connection.getCatalog()));
                table.setForeignKeys(getForeignKeys(metaData, tableName, connection.getCatalog()));

                return table;
            } catch (SQLException e) {
                log.error("获取表 {} 的 Schema 失败", tableName, e);
                return null;
            }
        });
    }

    /**
     * 获取所有表
     */
    private List<TableMetadata> getAllTables() {
        return getJdbcTemplate().execute((ConnectionCallback<List<TableMetadata>>) connection -> {
            try {
                DatabaseMetaData metaData = connection.getMetaData();
                String catalog = connection.getCatalog();
                List<TableMetadata> tables = new ArrayList<>();

                try (ResultSet rs = metaData.getTables(
                        catalog,
                        null,
                        "%",
                        new String[]{"TABLE"}
                )) {
                    while (rs.next()) {
                        String tableName = rs.getString("TABLE_NAME");
                        String tableComment = rs.getString("REMARKS");

                        TableMetadata table = new TableMetadata();
                        table.setTableName(tableName);
                        table.setTableComment(tableComment);
                        table.setColumns(getColumns(metaData, tableName, catalog));
                        table.setForeignKeys(getForeignKeys(metaData, tableName, catalog));

                        tables.add(table);
                    }
                }

                return tables;
            } catch (SQLException e) {
                log.error("获取所有表失败", e);
                return new ArrayList<>();
            }
        });
    }

    /**
     * 获取表的所有列
     */
    private List<ColumnMetadata> getColumns(DatabaseMetaData metaData, String tableName, String catalog)
            throws SQLException {

        Set<String> primaryKeys = getPrimaryKeys(metaData, tableName, catalog);
        List<ColumnMetadata> columns = new ArrayList<>();

        try (ResultSet rs = metaData.getColumns(catalog, null, tableName, "%")) {
            while (rs.next()) {
                ColumnMetadata column = new ColumnMetadata();
                column.setColumnName(rs.getString("COLUMN_NAME"));
                column.setDataType(rs.getString("TYPE_NAME"));
                column.setColumnSize(rs.getInt("COLUMN_SIZE"));
                column.setNullable("YES".equals(rs.getString("IS_NULLABLE")));
                column.setColumnDefault(rs.getString("COLUMN_DEF"));
                column.setColumnComment(rs.getString("REMARKS"));
                column.setIsPrimaryKey(primaryKeys.contains(rs.getString("COLUMN_NAME")));

                columns.add(column);
            }
        }

        return columns;
    }

    /**
     * 获取主键列
     */
    private Set<String> getPrimaryKeys(DatabaseMetaData metaData, String tableName, String catalog)
            throws SQLException {

        Set<String> primaryKeys = new HashSet<>();

        try (ResultSet rs = metaData.getPrimaryKeys(catalog, null, tableName)) {
            while (rs.next()) {
                primaryKeys.add(rs.getString("COLUMN_NAME"));
            }
        }

        return primaryKeys;
    }

    /**
     * 获取外键关系
     */
    private List<ForeignKeyMetadata> getForeignKeys(DatabaseMetaData metaData, String tableName, String catalog)
            throws SQLException {

        List<ForeignKeyMetadata> foreignKeys = new ArrayList<>();

        try (ResultSet rs = metaData.getImportedKeys(catalog, null, tableName)) {
            while (rs.next()) {
                ForeignKeyMetadata fk = new ForeignKeyMetadata();
                fk.setColumnName(rs.getString("FKCOLUMN_NAME"));
                fk.setReferencedTableName(rs.getString("PKTABLE_NAME"));
                fk.setReferencedColumnName(rs.getString("PKCOLUMN_NAME"));
                fk.setConstraintName(rs.getString("FK_NAME"));

                foreignKeys.add(fk);
            }
        }

        return foreignKeys;
    }

    /**
     * 获取表注释
     */
    private String getTableComment(DatabaseMetaData metaData, String tableName, String catalog)
            throws SQLException {

        try (ResultSet rs = metaData.getTables(catalog, null, tableName, new String[]{"TABLE"})) {
            if (rs.next()) {
                return rs.getString("REMARKS");
            }
        }
        return null;
    }

    /**
     * 将 Schema 格式化为 AI 可理解的文本
     */
    private String formatSchemaForAI(String databaseName, List<TableMetadata> tables) {
        StringBuilder sb = new StringBuilder();
        sb.append("数据库结构 (MySQL):\n");
        sb.append("数据库: ").append(databaseName).append("\n\n");

        for (TableMetadata table : tables) {
            sb.append("表: ").append(table.getTableName());
            if (table.getTableComment() != null && !table.getTableComment().isEmpty()) {
                sb.append(" (").append(table.getTableComment()).append(")");
            }
            sb.append("\n");

            // 列信息
            for (ColumnMetadata column : table.getColumns()) {
                sb.append("  - ").append(column.getColumnName());
                sb.append(" ").append(column.getDataType());
                if (column.getColumnSize() > 0) {
                    sb.append("(").append(column.getColumnSize()).append(")");
                }
                if (column.getIsPrimaryKey()) {
                    sb.append(" [PK]");
                }
                if (!column.getNullable()) {
                    sb.append(" NOT NULL");
                }
                if (column.getColumnComment() != null && !column.getColumnComment().isEmpty()) {
                    sb.append(" -- ").append(column.getColumnComment());
                }
                sb.append("\n");
            }

            // 外键关系（关键：支持复杂查询）
            if (!table.getForeignKeys().isEmpty()) {
                sb.append("  关联关系:\n");
                for (ForeignKeyMetadata fk : table.getForeignKeys()) {
                    sb.append("    ").append(table.getTableName())
                      .append(".").append(fk.getColumnName())
                      .append(" -> ").append(fk.getReferencedTableName())
                      .append(".").append(fk.getReferencedColumnName())
                      .append("\n");
                }
            }

            sb.append("\n");
        }

        return sb.toString();
    }
}
