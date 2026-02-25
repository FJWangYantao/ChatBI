# ChatBI 前端项目

基于 Next.js + TypeScript + Tailwind CSS 的智能数据分析平台前端。

## 功能特性

✅ **AI 对话界面** - 自然语言交互体验
✅ **响应式设计** - 适配不同屏幕尺寸
✅ **实时消息** - 流畅的聊天体验
✅ **现代化 UI** - 简洁美观的界面设计

## 技术栈

- **Next.js 14** - React 框架（App Router）
- **TypeScript** - 类型安全
- **Tailwind CSS** - 样式框架
- **React 18** - UI 库

## 快速开始

### 1. 安装依赖

确保您已安装 [Node.js](https://nodejs.org/)（推荐 18.x 或 20.x）

```bash
cd frontend
npm install
```

### 2. 启动开发服务器

```bash
npm run dev
```

访问 [http://localhost:3000](http://localhost:3000) 查看应用

### 3. 构建生产版本

```bash
npm run build
npm start
```

## 项目结构

```
frontend/
├── app/                    # Next.js App Router
│   ├── layout.tsx         # 根布局
│   ├── page.tsx           # 主页（聊天界面）
│   └── globals.css        # 全局样式
├── components/            # React 组件
│   └── Chat/              # 聊天相关组件
│       ├── ChatWindow.tsx # 聊天窗口
│       └── InputBox.tsx   # 输入框
├── lib/                   # 工具函数（后续扩展）
├── public/               # 静态资源
└── package.json          # 依赖配置
```

## 后端对接

当前版本使用模拟数据。后续对接 SpringAI 后端时，需要：

1. 在 `app/page.tsx` 中配置后端 API 地址
2. 实现真实的 API 调用逻辑
3. 处理 AI 响应和 SQL 查询结果

## 待实现功能

- [ ] 接入 SpringAI 后端 API
- [ ] 数据可视化图表（折线图、柱状图、饼图）
- [ ] SQL 查询结果展示
- [ ] 数据源管理界面
- [ ] 数据导入功能
- [ ] 对话历史记录

## 开发说明

- 使用 **App Router**（非 Pages Router）
- 组件使用 **Client Components**（`"use client"`）
- 样式使用 **Tailwind CSS** 类名

## 许可证

MIT
