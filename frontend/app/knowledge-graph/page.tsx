'use client';

import { useRouter } from 'next/navigation';
import { useEffect } from 'react';

/**
 * 知识图谱页面 - 已弃用
 *
 * 此页面已被弃用，知识管理功能已整合到 MCP 服务器。
 * 请使用新的 MCP 术语库管理页面。
 */
export default function KnowledgeGraphDeprecatedPage() {
    const router = useRouter();

    useEffect(() => {
        // 3秒后自动跳转到新页面
        const timer = setTimeout(() => {
            router.push('/mcp-terms');
        }, 3000);

        return () => clearTimeout(timer);
    }, [router]);

    return (
        <div className="flex items-center justify-center min-h-screen bg-gradient-to-br from-background to-muted/20">
            <div className="max-w-2xl mx-auto p-8 text-center">
                <div className="mb-6">
                    <svg
                        className="w-20 h-20 mx-auto text-yellow-500"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                    >
                        <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2}
                            d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"
                        />
                    </svg>
                </div>

                <h1 className="text-3xl font-bold mb-4">页面已迁移</h1>

                <div className="bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 rounded-lg p-6 mb-6">
                    <p className="text-lg mb-4">
                        知识图谱管理功能已整合到 <strong>MCP 知识库服务器</strong>
                    </p>
                    <p className="text-sm text-muted-foreground mb-4">
                        为了提供更好的知识管理体验，我们已将所有知识管理功能统一到 MCP 服务器。
                        新的管理界面提供了更强大的功能和更好的用户体验。
                    </p>
                </div>

                <div className="space-y-4">
                    <p className="text-sm text-muted-foreground">
                        正在自动跳转到新的管理页面...
                    </p>

                    <button
                        onClick={() => router.push('/mcp-terms')}
                        className="inline-flex items-center gap-2 px-6 py-3 bg-primary text-primary-foreground rounded-lg hover:bg-primary/90 transition-colors"
                    >
                        <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7l5 5m0 0l-5 5m5-5H6" />
                        </svg>
                        立即前往 MCP 知识库管理
                    </button>

                    <div className="pt-4">
                        <button
                            onClick={() => router.push('/')}
                            className="text-sm text-muted-foreground hover:text-foreground transition-colors"
                        >
                            返回首页
                        </button>
                    </div>
                </div>

                <div className="mt-8 pt-6 border-t border-border">
                    <h3 className="text-sm font-semibold mb-3">主要变化：</h3>
                    <ul className="text-sm text-muted-foreground space-y-2 text-left max-w-md mx-auto">
                        <li className="flex items-start gap-2">
                            <span className="text-green-500 mt-0.5">✓</span>
                            <span>统一的知识管理入口</span>
                        </li>
                        <li className="flex items-start gap-2">
                            <span className="text-green-500 mt-0.5">✓</span>
                            <span>更强大的实体验证功能</span>
                        </li>
                        <li className="flex items-start gap-2">
                            <span className="text-green-500 mt-0.5">✓</span>
                            <span>产品系列展开支持</span>
                        </li>
                        <li className="flex items-start gap-2">
                            <span className="text-green-500 mt-0.5">✓</span>
                            <span>符合 MCP 协议标准</span>
                        </li>
                    </ul>
                </div>
            </div>
        </div>
    );
}
