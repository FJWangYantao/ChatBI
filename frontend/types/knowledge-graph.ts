// 知识图谱配置类型定义

export interface FiscalYearRule {
    startMonth?: number;
    startDay?: number;
    description?: string;
}

export interface KnowledgeGraphConfig {
    organizations?: string[];
    locations?: string[];
    tableAliases?: Record<string, string>;
    columnAliases?: Record<string, string>;
    columnDomainValues?: Record<string, string[]>;
    productSeries?: Record<string, string[]>;
    businessTerms?: Record<string, string>;
    fiscalYearRule?: FiscalYearRule;
    fiscalYearMappings?: Record<string, string>;
}

export interface ValidationResult {
    valid: boolean;
    errors: string[];
    warnings: string[];
}

export interface UpdateConfigResponse {
    success: boolean;
    message: string;
    validation?: ValidationResult;
    backupPath?: string;
}

export interface BackupResponse {
    success: boolean;
    message: string;
    backupPath?: string;
}
