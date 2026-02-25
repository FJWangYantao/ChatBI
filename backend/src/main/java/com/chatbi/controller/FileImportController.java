package com.chatbi.controller;

import com.chatbi.dto.FileImportRequest;
import com.chatbi.dto.FileImportResponse;
import com.chatbi.service.FileImportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件导入控制器
 * 处理 CSV 和 Excel 文件的导入请求
 * 如果目标数据库不存在，会自动创建
 */
@Slf4j
@RestController
@RequestMapping("/file-import")
public class FileImportController {

    private final FileImportService fileImportService;

    public FileImportController(FileImportService fileImportService) {
        this.fileImportService = fileImportService;
    }

    /**
     * 导入文件到指定数据源
     *
     * @param file 上传的文件（CSV 或 Excel）
     * @param dataSourceId 目标数据源 ID
     * @param tableName 目标表名
     * @param skipIfExists 是否跳过已存在的表（默认 true）
     * @param firstRowAsHeader 第一行是否为标题行（默认 true）
     * @return 导入结果
     */
    @PostMapping
    public ResponseEntity<FileImportResponse> importFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("dataSourceId") Long dataSourceId,
            @RequestParam("tableName") String tableName,
            @RequestParam(value = "skipIfExists", defaultValue = "true") boolean skipIfExists,
            @RequestParam(value = "firstRowAsHeader", defaultValue = "true") boolean firstRowAsHeader) {

        // 验证文件
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    FileImportResponse.builder()
                            .success(false)
                            .message("文件不能为空")
                            .build()
            );
        }

        // 验证文件大小（限制 100MB）
        long maxSize = 100 * 1024 * 1024;
        if (file.getSize() > maxSize) {
            return ResponseEntity.badRequest().body(
                    FileImportResponse.builder()
                            .success(false)
                            .message("文件大小超过限制（最大 100MB）")
                            .build()
            );
        }

        // 构建请求
        FileImportRequest request = new FileImportRequest();
        request.setDataSourceId(dataSourceId);
        request.setTableName(tableName);
        request.setSkipIfExists(skipIfExists);
        request.setFirstRowAsHeader(firstRowAsHeader);

        log.info("开始导入文件: fileName={}, size={}, dataSourceId={}, tableName={}, autoCreateDatabase=true",
                file.getOriginalFilename(), file.getSize(), dataSourceId, tableName);

        FileImportResponse response = fileImportService.importFile(file, request);

        if (response.isSuccess()) {
            log.info("文件导入成功: {}", response.getMessage());
            return ResponseEntity.ok(response);
        } else {
            log.warn("文件导入失败: {}", response.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
}
