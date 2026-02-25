package com.chatbi.controller;

import com.chatbi.dto.SchemaResponse;
import com.chatbi.dto.TableMetadata;
import com.chatbi.service.ReadSchemaStructureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Schema API 控制器
 */
@Slf4j
@RestController
@RequestMapping("/schema")
@RequiredArgsConstructor
public class SchemaController {

    private final ReadSchemaStructureService schemaService;

    /**
     * 获取数据库所有表的 Schema
     * GET /api/schema/database
     */
    @GetMapping("/database")
    public ResponseEntity<SchemaResponse> getDatabaseSchema() {
        log.info("获取数据库Schema");
        SchemaResponse schema = schemaService.getDatabaseSchema();
        int tableCount = schema.getTables() != null ? schema.getTables().size() : 0;
        log.info("获取数据库Schema完成: tableCount={}", tableCount);
        return ResponseEntity.ok(schema);
    }

    /**
     * 获取指定表的 Schema
     * GET /api/schema/table/{tableName}
     */
    @GetMapping("/table/{tableName}")
    public ResponseEntity<TableMetadata> getTableSchema(@PathVariable String tableName) {
        log.info("获取表Schema: tableName={}", tableName);
        TableMetadata table = schemaService.getTableSchema(tableName);
        if (table == null) {
            log.warn("表不存在: tableName={}", tableName);
            return ResponseEntity.notFound().build();
        }
        log.info("获取表Schema完成: tableName={}", tableName);
        return ResponseEntity.ok(table);
    }

    /**
     * 获取 AI 格式化的 Schema 文本
     * GET /api/schema/ai-format
     */
    @GetMapping("/ai-format")
    public ResponseEntity<String> getAIFormattedSchema() {
        log.info("获取AI格式化Schema");
        SchemaResponse schema = schemaService.getDatabaseSchema();
        String formatted = schema.getFormattedForAI();
        log.info("获取AI格式化Schema完成: length={}", formatted != null ? formatted.length() : 0);
        return ResponseEntity.ok(formatted);
    }
}
