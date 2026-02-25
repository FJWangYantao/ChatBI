package com.chatbi.service;

import com.chatbi.dto.FileImportRequest;
import com.chatbi.dto.FileImportResponse;
import com.chatbi.entity.DataSourceConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 文件导入服务
 * 支持 CSV 和 Excel (xls, xlsx) 文件导入
 */
@Slf4j
@Service
public class FileImportService {

    private final DynamicDataSourceService dynamicDataSourceService;

    public FileImportService(DynamicDataSourceService dynamicDataSourceService) {
        this.dynamicDataSourceService = dynamicDataSourceService;
    }

    /**
     * 导入文件到数据库
     */
    public FileImportResponse importFile(MultipartFile file, FileImportRequest request) {
        try {
            // 获取数据源配置
            Optional<DataSourceConfig> dataSourceConfigOpt = dynamicDataSourceService.getDataSourceConfig(request.getDataSourceId());
            if (dataSourceConfigOpt.isEmpty()) {
                return FileImportResponse.builder()
                        .success(false)
                        .message("数据源不存在")
                        .build();
            }
            DataSourceConfig dataSourceConfig = dataSourceConfigOpt.get();

            // 确保数据库存在，不存在则自动创建
            if (!dynamicDataSourceService.ensureDatabaseExists(dataSourceConfig)) {
                return FileImportResponse.builder()
                        .success(false)
                        .message("无法创建数据库: " + dataSourceConfig.getDbName())
                        .build();
            }

            // 获取对应的 JdbcTemplate
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dynamicDataSourceService.getDataSource(request.getDataSourceId()));

            String fileName = file.getOriginalFilename();
            if (fileName == null) {
                return FileImportResponse.builder()
                        .success(false)
                        .message("无效的文件名")
                        .build();
            }

            String fileExtension = getFileExtension(fileName).toLowerCase();

            // 根据文件类型选择不同的解析方法
            return switch (fileExtension) {
                case "csv" -> importCSV(file, request, jdbcTemplate, dataSourceConfig.getDbName());
                case "xlsx", "xls" -> importExcel(file, request, jdbcTemplate, dataSourceConfig.getDbName(), fileExtension);
                default -> FileImportResponse.builder()
                        .success(false)
                        .message("不支持的文件类型: " + fileExtension + "。支持的类型: csv, xlsx, xls")
                        .build();
            };

        } catch (Exception e) {
            log.error("文件导入失败", e);
            return FileImportResponse.builder()
                    .success(false)
                    .message("导入失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 导入 CSV 文件
     */
    private FileImportResponse importCSV(MultipartFile file, FileImportRequest request,
                                         JdbcTemplate jdbcTemplate, String dbName) throws IOException {
        try (InputStream inputStream = file.getInputStream();
             InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {

            CSVFormat format = CSVFormat.DEFAULT.withTrim();
            if (request.isFirstRowAsHeader()) {
                format = format.withFirstRecordAsHeader();
            }

            CSVParser parser = new CSVParser(reader, format);
            List<String> headers = new ArrayList<>();
            List<List<String>> data = new ArrayList<>();

            if (request.isFirstRowAsHeader()) {
                headers = new ArrayList<>(parser.getHeaderNames());
                for (CSVRecord record : parser.getRecords()) {
                    List<String> row = new ArrayList<>();
                    for (String value : record) {
                        row.add(value);
                    }
                    data.add(row);
                }
            } else {
                for (CSVRecord record : parser.getRecords()) {
                    List<String> row = new ArrayList<>();
                    for (String value : record) {
                        row.add(value);
                    }
                    data.add(row);
                }
                // 如果没有数据，返回错误
                if (data.isEmpty()) {
                    return FileImportResponse.builder()
                            .success(false)
                            .message("CSV 文件为空")
                            .build();
                }
                // 生成默认列名
                int columnCount = data.get(0).size();
                for (int i = 0; i < columnCount; i++) {
                    headers.add("column_" + (i + 1));
                }
            }

            if (data.isEmpty()) {
                return FileImportResponse.builder()
                        .success(false)
                        .message("CSV 文件为空")
                        .build();
            }

            return createTableAndImport(headers, data, request, jdbcTemplate, dbName);
        }
    }

    /**
     * 导入 Excel 文件
     */
    private FileImportResponse importExcel(MultipartFile file, FileImportRequest request,
                                          JdbcTemplate jdbcTemplate, String dbName, String fileExtension) throws IOException {
        try (InputStream inputStream = file.getInputStream();
             Workbook workbook = fileExtension.equals("xlsx") ? new XSSFWorkbook(inputStream) : new HSSFWorkbook(inputStream)) {

            Sheet sheet = workbook.getSheetAt(0);

            List<List<String>> data = new ArrayList<>();
            List<String> headers = new ArrayList<>();

            int firstRow = request.isFirstRowAsHeader() ? 1 : 0;
            int lastRow = sheet.getLastRowNum();

            // 读取数据
            for (int i = 0; i <= lastRow; i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                List<String> rowData = new ArrayList<>();
                short lastCol = row.getLastCellNum();

                for (int j = 0; j < lastCol; j++) {
                    Cell cell = row.getCell(j);
                    rowData.add(getCellValueAsString(cell));
                }

                if (i == 0 && request.isFirstRowAsHeader()) {
                    headers = rowData;
                } else {
                    data.add(rowData);
                }
            }

            // 如果没有标题行，生成默认列名
            if (!request.isFirstRowAsHeader() && headers.isEmpty() && !data.isEmpty()) {
                int colCount = data.get(0).size();
                for (int i = 0; i < colCount; i++) {
                    headers.add("column_" + (i + 1));
                }
            }

            return createTableAndImport(headers, data, request, jdbcTemplate, dbName);
        }
    }

    /**
     * 创建表并导入数据
     */
    private FileImportResponse createTableAndImport(List<String> headers, List<List<String>> data,
                                                   FileImportRequest request, JdbcTemplate jdbcTemplate, String dbName) {
        String tableName = request.getTableName();

        // 检查表是否已存在
        boolean tableExists;
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                    Integer.class,
                    dbName,
                    tableName
            );
            tableExists = count != null && count > 0;
        } catch (Exception e) {
            log.warn("检查表是否存在时出错，尝试创建表", e);
            tableExists = false;
        }

        if (tableExists) {
            if (request.isSkipIfExists()) {
                return FileImportResponse.builder()
                        .success(false)
                        .message("表 " + tableName + " 已存在，已跳过导入")
                        .build();
            } else {
                // 删除旧表
                jdbcTemplate.execute("DROP TABLE " + tableName);
            }
        }

        // 规范化列名（去除特殊字符，转小写）
        List<String> normalizedHeaders = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();

        for (String header : headers) {
            String normalized = normalizeColumnName(header);
            // 确保列名唯一
            String finalName = normalized;
            int counter = 1;
            while (usedNames.contains(finalName)) {
                finalName = normalized + "_" + counter++;
            }
            usedNames.add(finalName);
            normalizedHeaders.add(finalName);
        }

        // 创建建表 SQL
        StringBuilder createTableSQL = new StringBuilder();
        createTableSQL.append("CREATE TABLE ").append(tableName).append(" (");
        createTableSQL.append("id BIGINT AUTO_INCREMENT PRIMARY KEY, ");

        for (int i = 0; i < normalizedHeaders.size(); i++) {
            createTableSQL.append(normalizedHeaders.get(i)).append(" TEXT");
            if (i < normalizedHeaders.size() - 1) {
                createTableSQL.append(", ");
            }
        }
        createTableSQL.append(")");

        log.info("创建表 SQL: {}", createTableSQL);
        jdbcTemplate.execute(createTableSQL.toString());

        // 准备插入 SQL
        String insertSQL = "INSERT INTO " + tableName + " (" + String.join(", ", normalizedHeaders) + ") VALUES (" +
                String.join(", ", normalizedHeaders.stream().map(h -> "?").collect(Collectors.toList())) + ")";

        // 插入数据 - 使用 BatchPreparedStatementSetter
        List<Object[]> batchArgs = data.stream()
                .map(row -> row.toArray(new String[0]))
                .collect(Collectors.toList());

        int[] results = jdbcTemplate.batchUpdate(insertSQL, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                Object[] row = batchArgs.get(i);
                for (int j = 0; j < row.length; j++) {
                    ps.setString(j + 1, row[j] != null ? row[j].toString() : null);
                }
            }

            @Override
            public int getBatchSize() {
                return batchArgs.size();
            }
        });

        int totalRows = 0;
        for (int result : results) {
            totalRows += result;
        }

        return FileImportResponse.builder()
                .success(true)
                .message("成功导入 " + totalRows + " 行数据到表 " + tableName)
                .tableName(tableName)
                .rowsImported(totalRows)
                .columnsCreated(normalizedHeaders.size())
                .build();
    }

    /**
     * 获取单元格值作为字符串
     */
    private String getCellValueAsString(Cell cell) {
        if (cell == null) {
            return "";
        }

        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getDateCellValue().toString();
                } else {
                    // 避免科学计数法
                    double numValue = cell.getNumericCellValue();
                    if (numValue == (long) numValue) {
                        yield String.valueOf((long) numValue);
                    } else {
                        yield String.valueOf(numValue);
                    }
                }
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
    }

    /**
     * 规范化列名
     */
    private String normalizeColumnName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "column";
        }

        // 转小写，替换特殊字符为下划线
        String normalized = name.trim()
                .toLowerCase()
                .replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");

        // 如果结果为空或以数字开头，添加前缀
        if (normalized.isEmpty() || Character.isDigit(normalized.charAt(0))) {
            normalized = "col_" + normalized;
        }

        return normalized;
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex > 0 && lastDotIndex < fileName.length() - 1) {
            return fileName.substring(lastDotIndex + 1);
        }
        return "";
    }
}
