# ChatBI - 智能数据分析平台

基于 Spring AI + Next.js 的智能商业智能（BI）对话系统，通过自然语言与数据库交互，实现智能数据查询和分析。

## 📋 项目简介

ChatBI 是一个现代化的智能数据分析平台，允许用户通过自然语言对话的方式查询和分析数据。系统集成了 AI 大模型、意图识别、SQL 生成、代码执行等功能，提供流畅的数据分析体验。

### 核心功能

- 🤖 **自然语言交互** - 使用日常语言提问，无需学习 SQL
- 🧠 **智能意图识别** - 基于深度学习的意图分类和实体识别
- 📊 **数据可视化** - 自动生成图表展示查询结果
- 💻 **代码沙箱** - 安全的 Python 代码执行环境
- 📚 **知识库管理** - MCP 协议集成的知识图谱系统
- 🔄 **实时响应** - 流式输出，即时反馈

## 🏗️ 技术架构

### 后端技术栈

- **Spring Boot 3.3.0** - 核心框架
- **Spring AI 1.0.0-M4** - AI 集成框架
- **Java 17** - 开发语言
- **MySQL** - 数据存储
- **Python 3.x** - 意图识别和代码执行服务

### 前端技术栈

- **Next.js 14** - React 框架（App Router）
- **React 18** - UI 库
- **TypeScript** - 类型安全
- **Tailwind CSS** - 样式框架
- **Recharts** - 数据可视化

## 🚀 快速开始

### 前置要求

确保你的开发环境已安装以下工具：

- **Node.js** 18.x 或 20.x
- **Java** 17 或更高版本
- **Maven** 3.6+
- **Python** 3.8+
- **MySQL** 8.0+
- **Git**

### 1. 克隆项目

```bash
git clone https://github.com/FJWangYantao/ChatBI.git
cd ChatBI
```

### 2. 配置数据库

```bash
# 创建数据库
mysql -u root -p
CREATE DATABASE chatbi DEFAULT CHARACTER SET utf8mb4;

# 运行初始化脚本（如果有）
# mysql -u root -p chatbi < init-db.sql
```

### 3. 启动后端服务

#### 3.1 启动主服务（Spring Boot）

```bash
cd backend

# 配置数据库连接
# 编辑 src/main/resources/application.properties
# spring.datasource.url=jdbc:mysql://localhost:3306/chatbi
# spring.datasource.username=your_username
# spring.datasource.password=your_password

# 安装依赖并启动
mvn clean install
mvn spring-boot:run
```

服务将在 `http://localhost:8080` 启动

#### 3.2 启动意图识别服务（Python）

```bash
cd backend/intent-service

# 创建虚拟环境
python -m venv venv

# 激活虚拟环境
# Windows:
venv\Scripts\activate
# Linux/Mac:
# source venv/bin/activate

# 安装依赖
pip install -r requirements.txt

# 启动服务
python intent_api.py
```

服务将在 `http://localhost:5000` 启动

#### 3.3 启动代码沙箱服务（Python）

```bash
cd backend/sandbox-service

# 创建虚拟环境
python -m venv venv
venv\Scripts\activate  # Windows
# source venv/bin/activate  # Linux/Mac

# 安装依赖
pip install -r requirements.txt

# 启动服务
python main.py
```

服务将在 `http://localhost:5001` 启动

### 4. 启动前端

```bash
cd frontend

# 安装依赖
npm install

# 启动开发服务器
npm run dev
```

访问 `http://localhost:3000` 查看应用

## 📁 项目结构

```
ChatBI/
├── backend/                    # 后端服务
│   ├── src/                    # Spring Boot 主服务
│   │   └── main/
│   │       ├── java/com/chatbi/
│   │       │   ├── controller/ # REST API 控制器
│   │       │   ├── service/    # 业务逻辑
│   │       │   ├── model/      # 数据模型
│   │       │   └── config/     # 配置类
│   │       └── resources/
│   │           └── application.properties
│   ├── intent-service/         # 意图识别服务（Python）
│   │   ├── intent_api.py       # 意图分类 API
│   │   ├── ner_api.py          # 命名实体识别 API
│   │   └── requirements.txt
│   ├── sandbox-service/        # 代码沙箱服务（Python）
│   │   ├── main.py             # 主服务
│   │   ├── executor.py         # 代码执行器
│   │   ├── validator.py        # 代码验证器
│   │   └── requirements.txt
│   ├── mcp-knowledge-server/   # 知识库服务
│   └── pom.xml                 # Maven 配置
├── frontend/                   # 前端应用
│   ├── app/                    # Next.js 页面
│   │   ├── layout.tsx          # 根布局
│   │   ├── page.tsx            # 主页（聊天界面）
│   │   └── globals.css         # 全局样式
│   ├── components/             # React 组件
│   │   └── Chat/               # 聊天相关组件
│   ├── lib/                    # 工具函数
│   ├── types/                  # TypeScript 类型定义
│   └── package.json            # 依赖配置
├── docs/                       # 文档
├── tests/                      # 测试文件
├── scripts/                    # 脚本工具
└── README.md                   # 项目说明
```

## 🔧 开发指南

### 环境变量配置

创建 `.env` 文件（不要提交到 Git）：

```bash
# 后端配置
SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/chatbi
SPRING_DATASOURCE_USERNAME=your_username
SPRING_DATASOURCE_PASSWORD=your_password

# AI 配置
OPENAI_API_KEY=your_openai_api_key
OPENAI_API_BASE=https://api.openai.com/v1

# 服务端口
INTENT_SERVICE_PORT=5000
SANDBOX_SERVICE_PORT=5001
```

### 开发流程

1. **创建新分支**
   ```bash
   git checkout -b feature/your-feature-name
   ```

2. **开发和测试**
   - 后端：修改代码后运行 `mvn test`
   - 前端：修改代码后自动热重载

3. **提交代码**
   ```bash
   git add .
   git commit -m "feat: add your feature description"
   git push origin feature/your-feature-name
   ```

4. **创建 Pull Request**
   - 访问 GitHub 仓库
   - 创建 PR 并等待审核

### 代码规范

- **Java**: 遵循 Google Java Style Guide
- **TypeScript/React**: 使用 ESLint 和 Prettier
- **Python**: 遵循 PEP 8 规范
- **提交信息**: 使用 Conventional Commits 格式

## 📦 构建和部署

### 构建生产版本

```bash
# 后端
cd backend
mvn clean package
# 生成的 JAR 文件在 target/ 目录

# 前端
cd frontend
npm run build
# 生成的静态文件在 .next/ 目录
```

### Docker 部署（待完善）

```bash
# 构建镜像
docker-compose build

# 启动服务
docker-compose up -d
```

## 🧪 测试

```bash
# 后端单元测试
cd backend
mvn test

# 前端测试
cd frontend
npm test

# 集成测试
npm run test:integration
```

## 📝 API 文档

启动后端服务后，访问：
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- API Docs: `http://localhost:8080/v3/api-docs`

## 🤝 贡献指南

欢迎贡献代码！请遵循以下步骤：

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 创建 Pull Request

## 📄 许可证

本项目采用 MIT 许可证 - 详见 [LICENSE](LICENSE) 文件

## 👥 团队

- **项目负责人**: [FJWangYantao](https://github.com/FJWangYantao)

## 📮 联系方式

如有问题或建议，请通过以下方式联系：

- 提交 [Issue](https://github.com/FJWangYantao/ChatBI/issues)
- 发送邮件至: your-email@example.com

## 🙏 致谢

感谢所有为本项目做出贡献的开发者！

---

**注意**: 本项目仍在积极开发中，部分功能可能尚未完善。
