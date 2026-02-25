import {
    KnowledgeGraphConfig,
    ValidationResult,
    UpdateConfigResponse,
    BackupResponse
} from '@/types/knowledge-graph';

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL || 'http://localhost:8080/api';

/**
 * 知识图谱 API 客户端
 */
export const knowledgeGraphApi = {
    /**
     * 获取当前知识图谱配置
     */
    async getConfig(): Promise<KnowledgeGraphConfig> {
        const response = await fetch(`${API_BASE_URL}/knowledge-graph/config`);
        if (!response.ok) {
            throw new Error('Failed to fetch knowledge graph config');
        }
        return response.json();
    },

    /**
     * 更新知识图谱配置
     */
    async updateConfig(config: KnowledgeGraphConfig): Promise<UpdateConfigResponse> {
        const response = await fetch(`${API_BASE_URL}/knowledge-graph/config`, {
            method: 'PUT',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(config),
        });
        if (!response.ok) {
            throw new Error('Failed to update knowledge graph config');
        }
        return response.json();
    },

    /**
     * 验证配置有效性（不保存）
     */
    async validateConfig(config: KnowledgeGraphConfig): Promise<ValidationResult> {
        const response = await fetch(`${API_BASE_URL}/knowledge-graph/validate`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(config),
        });
        if (!response.ok) {
            throw new Error('Failed to validate knowledge graph config');
        }
        return response.json();
    },

    /**
     * 重新加载知识图谱配置到内存
     */
    async refreshGraph(): Promise<{ success: boolean; message: string }> {
        const response = await fetch(`${API_BASE_URL}/knowledge-graph/refresh`, {
            method: 'POST',
        });
        if (!response.ok) {
            throw new Error('Failed to refresh knowledge graph');
        }
        return response.json();
    },

    /**
     * 创建配置备份
     */
    async createBackup(): Promise<BackupResponse> {
        const response = await fetch(`${API_BASE_URL}/knowledge-graph/backup`, {
            method: 'POST',
        });
        if (!response.ok) {
            throw new Error('Failed to create backup');
        }
        return response.json();
    },
};
