package com.chatbi.controller;

import com.chatbi.entity.DataSourceConfig;
import com.chatbi.service.DynamicDataSourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源配置管理 Controller
 * 提供数据源的增删改查、切换、测试连接等 API
 */
@Slf4j
@RestController
@RequestMapping("/datasource")
@RequiredArgsConstructor
public class DataSourceConfigController {

    private final DynamicDataSourceService dynamicDataSourceService;

    /**
     * 获取所有数据源
     */
    @GetMapping
    public ResponseEntity<List<DataSourceConfig>> listDataSources() {
        List<DataSourceConfig> dataSources = dynamicDataSourceService.getAllDataSources();
        return ResponseEntity.ok(dataSources);
    }

    /**
     * 根据 ID 获取数据源
     */
    @GetMapping("/{id}")
    public ResponseEntity<DataSourceConfig> getDataSource(@PathVariable Long id) {
        return dynamicDataSourceService.getDataSourceConfig(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 获取当前激活的数据源
     */
    @GetMapping("/active")
    public ResponseEntity<DataSourceConfig> getActiveDataSource() {
        return dynamicDataSourceService.getActiveDataSourceConfig()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    /**
     * 添加数据源
     */
    @PostMapping
    public ResponseEntity<?> addDataSource(@RequestBody DataSourceConfig config) {
        try {
            // 设置默认值
            if (config.getPort() == null) {
                config.setPort(3306);
            }
            if (config.getIsActive() == null) {
                config.setIsActive(false);
            }

            DataSourceConfig saved = dynamicDataSourceService.addDataSource(config);
            log.info("添加数据源成功: id={}, name={}", saved.getId(), saved.getName());
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } catch (IllegalArgumentException e) {
            log.warn("添加数据源参数无效: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("添加数据源失败: name={}", config.getName(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "添加数据源失败: " + e.getMessage()));
        }
    }

    /**
     * 更新数据源
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateDataSource(
            @PathVariable Long id,
            @RequestBody DataSourceConfig config) {
        try {
            DataSourceConfig updated = dynamicDataSourceService.updateDataSource(id, config);
            log.info("更新数据源成功: id={}, name={}", id, updated.getName());
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            log.warn("更新数据源参数无效: id={}, error={}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("更新数据源失败: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "更新数据源失败: " + e.getMessage()));
        }
    }

    /**
     * 删除数据源
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteDataSource(@PathVariable Long id) {
        try {
            dynamicDataSourceService.deleteDataSource(id);
            log.info("删除数据源成功: id={}", id);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException e) {
            log.warn("删除数据源失败(业务校验): id={}, error={}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("删除数据源失败: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "删除数据源失败: " + e.getMessage()));
        }
    }

    /**
     * 激活指定数据源
     */
    @PutMapping("/{id}/active")
    public ResponseEntity<?> setActiveDataSource(@PathVariable Long id) {
        try {
            dynamicDataSourceService.activateDataSource(id);
            log.info("激活数据源成功: id={}", id);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            log.warn("激活数据源参数无效: id={}, error={}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("激活数据源失败: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "激活数据源失败: " + e.getMessage()));
        }
    }

    /**
     * 测试数据源连接
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testConnection(@RequestBody DataSourceConfig config) {
        Map<String, Object> result = new HashMap<>();

        // 设置默认值
        if (config.getPort() == null) {
            config.setPort(3306);
        }

        boolean success = dynamicDataSourceService.testConnection(config);
        result.put("success", success);
        result.put("message", success ? "连接成功" : "连接失败");

        return ResponseEntity.ok(result);
    }

    /**
     * 获取数据源统计信息
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = new HashMap<>();

        List<DataSourceConfig> allDataSources = dynamicDataSourceService.getAllDataSources();
        long activeCount = allDataSources.stream().filter(DataSourceConfig::getIsActive).count();

        stats.put("total", allDataSources.size());
        stats.put("active", activeCount);
        stats.put("inactive", allDataSources.size() - activeCount);

        return ResponseEntity.ok(stats);
    }
}
