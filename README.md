# 多巴胺平台（duobaan）

> 一个融合办公协作与生活助手的"多巴胺释放平台"

## 项目目的

解决日常两大痛点：

1. **办公场景** — 每天上班/下班规划今日/明日安排，通过流程表完成和上交任务，接入大模型后可直接在平台内办公
2. **生活场景** — 根据心情、天气、口味、用餐类型（外卖/堂吃/自做），由大模型推荐适合的餐食，解决"没时间/不知道想吃什么"的困扰

### 核心功能

| 模块 | 说明 |
|---|---|
| 实时天气 | 和风天气 API，展示当前天气与体感温度 |
| 实时时间 | 展示当前时间、星期、时段 |
| 大模型对话 | SSE 流式输出，支持办公/多巴胺双模式 |
| 智能拆单 | 自然语言 → 结构化任务列表，一键写入流程表 |
| 流程表 | 今日/明日分组，状态流转（待办/进行中/完成/已上交） |
| 美食推荐 | 结合天气、心情、口味、用餐方式，大模型推荐 |
| 双页模式 | 办公模式 / 多巴胺模式，左侧一键切换 |

## 技术栈

```
┌─────────────────────────────────────────────────┐
│  前端        Vue 3.5 + Vite 6 + Vue Router 4    │
│  后端        Spring Boot 4.1.1 (Java 17)        │
│  数据库      MySQL 8.0 (HikariCP 连接池)        │
│  ORM        Spring Data JPA + Hibernate 7       │
│  大模型     OpenAI 兼容协议（可换任意厂商）       │
│  天气服务   和风天气 API                          │
└─────────────────────────────────────────────────┘
```

## 项目结构

```
duobaan/
├── src/main/java/org/example/duobaan/
│   ├── config/              # 配置类（异步线程池、RestClient、属性）
│   ├── controller/          # REST API 控制层
│   ├── model/               # 实体 + DTO
│   ├── repository/          # Spring Data JPA 仓库
│   └── service/             # 业务逻辑层
├── src/main/resources/
│   ├── application.properties   # 应用配置
│   └── schema.sql               # 建表脚本（启动自动执行）
├── frontend/
│   ├── src/
│   │   ├── api/index.js         # API 调用封装
│   │   ├── components/         # 组件（ChatPanel/FlowTable 等）
│   │   ├── views/               # 页面（WorkPage/DopaminePage）
│   │   └── router/index.js     # 路由
│   ├── vite.config.js
│   └── package.json
└── pom.xml
```

---

## 快速开始

### 环境要求

| 依赖 | 版本要求 | 说明 |
|---|---|---|
| JDK | ≥ 17 | 推荐 JDK 17 |
| Maven | ≥ 3.8 | 项目自带 Maven Wrapper，无需单独安装 |
| Node.js | ≥ 18 | 用于前端开发与构建 |
| npm | ≥ 9 | 同上 |
| MySQL | ≥ 8.0 | 数据库 |

### 1. 克隆项目

```bash
git clone https://github.com/chencheng-dot/duobaan.git
cd duobaan
```

### 2. 数据库配置

#### 2.1 创建数据库

```bash
# 登录 MySQL
mysql -uroot -p

# 创建数据库（字符集 utf8mb4）
CREATE DATABASE IF NOT EXISTS duobaan
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

exit
```

> **重要**：`createDatabaseIfNotExist=true` 在部分 HikariCP 场景下不会自动建库，
> 必须手动执行 `CREATE DATABASE`。建库后，表由 `schema.sql` 在启动时自动创建。

#### 2.2 修改数据库连接

编辑 `src/main/resources/application.properties`：

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/duobaan?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
spring.datasource.username=root
spring.datasource.password=你的密码
```

如果不在本机或端口不是 3306，修改 `localhost:3306` 为实际地址。

### 3. 配置第三方 API（可选）

#### 3.1 和风天气（天气功能）

1. 前往 [https://dev.qweather.com](https://dev.qweather.com) 注册并获取 API Key
2. 修改 `application.properties`：

```properties
duobaan.weather.api-key=你的和风天气Key
duobaan.weather.location=101010100   # 默认北京朝阳区，改为你的城市 ID
```

> 不配置也能启动，天气接口返回占位数据。

#### 3.2 大模型（对话、拆单、推荐功能）

修改 `application.properties`：

```properties
duobaan.llm.base-url=https://api.deepseek.com/v1   # 改为你用的厂商
duobaan.llm.api-key=你的APIKey
duobaan.llm.model=deepseek-chat                    # 改为你用的模型名
```

> 支持任何 OpenAI 兼容协议的厂商（DeepSeek、阿里云百炼、火山引擎等）。
> 不配置也能启动，对话接口返回降级提示。

### 4. 启动后端

#### 方式一：Maven Wrapper（推荐）

```bash
# Windows PowerShell
$env:JAVA_HOME = "你的JDK17路径"
.\mvnw.cmd spring-boot:run

# macOS / Linux
./mvnw spring-boot:run
```

#### 方式二：IDEA

1. 用 IDEA 打开项目根目录
2. 等待 Maven 自动导入依赖
3. 运行 `org.example.duobaan.DuobaanApplication` 主类

#### 验证启动成功

```bash
curl http://localhost:8080/api/time/now
# 预期返回：{"time":"17:30:00","weekday":"星期一","period":"下午"}
```

### 5. 启动前端（开发模式）

```bash
cd frontend
npm install
npm run dev
```

访问 [http://localhost:5173](http://localhost:5173)，Vite 自动代理 `/api` 到后端 8080。

### 6. 前端生产构建

```bash
cd frontend
npm install          # 首次或依赖变更后
npm run build         # 产物输出到 src/main/resources/static/
```

构建后直接访问 [http://localhost:8080](http://localhost:8080)，由 Spring Boot 托管前端页面，无需单独启动前端服务。

---

## 数据库说明

### 表结构

| 表名 | 说明 | 关键字段 |
|---|---|---|
| `task` | 流程表任务 | title, category, task_group(TODAY/TOMORROW), task_status, source |
| `chat_message` | 对话记录 | mode(DOPAMINE/WORK), chat_role, content |

### 建表策略

- **启动执行**：`spring.sql.init.mode=always`，每次启动都运行 `schema.sql`
- **幂等**：`CREATE TABLE IF NOT EXISTS`，表已存在则跳过
- **不自动改表**：`ddl-auto=none`，Hibernate 不干预表结构
- **修改表结构**：手动 `ALTER TABLE` 或修改 `schema.sql` 后手动 DROP 旧表

### 常见数据库问题排查

| 问题 | 解决方案 |
|---|---|
| `Unknown database 'duobaan'` | 手动执行 `CREATE DATABASE duobaan` |
| `Access denied for user` | 检查 `username` / `password` 是否正确 |
| `Port 3306 already in use` | 确认 MySQL 服务已启动 |
| `Table 'xxx' doesn't exist` | 检查表名拼写，或手动执行 `schema.sql` 建表 |
| 端口 8080 被占用 | `netstat -ano \| findstr :8080` 找到占用进程并 `taskkill /PID /F` |

---

## API 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/time/now` | 获取当前时间 |
| GET | `/api/weather/now` | 获取当前天气 |
| POST | `/api/llm/chat/stream` | SSE 流式对话 |
| POST | `/api/llm/parse-tasks` | 自然语言拆单为任务列表 |
| GET | `/api/tasks` | 查询任务列表 |
| POST | `/api/tasks` | 创建任务 |
| PATCH | `/api/tasks/{id}` | 更新任务（状态/分组等） |
| DELETE | `/api/tasks/{id}` | 删除任务 |
| POST | `/api/tasks/bulk` | 批量创建任务（拆单写入） |
| POST | `/api/recommend/meal` | 美食推荐 |

---

## 双模式说明

- **办公模式**（默认）：侧边栏"办公"图标，流程表 + 办公对话
- **多巴胺模式**：侧边栏"多巴胺"图标，美食推荐 + 生活助手

点击左侧图标即可切换，对话历史按模式隔离存储。

---

## License

MIT
