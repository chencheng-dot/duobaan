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

1. 前往 [https://console.qweather.com](https://console.qweather.com) 注册并获取 API Key
2. 在系统「设置 → 天气服务」页面填入 Key 和中文城市名（如"北京""上海"）即可

> 也支持通过 `application.properties` 配置：
> ```properties
> duobaan.weather.api-key=你的和风天气Key
> duobaan.weather.location=北京     # 支持中文城市名或数字LocationID，默认北京
> ```
>
> 不配置也能启动，天气接口返回占位数据。保存后顶部立即显示 7 种天气 SVG 线条图标（晴/多云/阴/雨/雪/雷/雾）+ 温度 + 体感温度。

#### 3.2 大模型（对话、拆单、推荐功能）

> 推荐直接在「设置 → 大模型」页面选择厂商并填 Key，三步完成：选厂商 → 贴 Key → 保存。

支持的预设与官方地址：

| 提供商 | 官方地址 | 推荐模型 |
|---|---|---|
| ChatGPT（OpenAI 官方） | platform.openai.com | gpt-4o-mini |
| DeepSeek | platform.deepseek.com | deepseek-chat |
| 豆包（火山引擎） | console.volces.com/ark | doubao-seed-1-6-flash |
| 千问（阿里云） | dashscope.console.aliyun.com | qwen-plus |
| 自定义 | 任何 OpenAI 兼容端点 | 自填 |

> 也支持通过 `application.properties` 配置：
> ```properties
> duobaan.llm.base-url=https://api.deepseek.com/v1
> duobaan.llm.api-key=你的APIKey
> duobaan.llm.model=deepseek-chat
> ```

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

---

## 版本更新记录

### v2.0.0 🎨

本次更新集中在 UI 美化、可视化增强与大模型接入体验优化：

**1. 新增大模型配置页面（设置页）**
- 侧边栏新增「设置」入口
- 支持 4 个预设厂商 + 自定义：ChatGPT / DeepSeek / 豆包 / 千问 / 自定义
- 点击厂商卡片自动填充 baseUrl 和默认模型
- 保存后立即可用，无需重启服务
- 新增 `/api/config/llm`、`/api/config/providers` 接口
- 新增 `system_config` 数据库表，运行时配置持久化

**2. 降低 API 接入门槛**
- 大模型配置从 application.properties 迁移到页面可视化配置
- 选厂商 → 填 Key → 保存，三步完成接入，无需改代码/配置文件
- 配置未生效时自动降级，保证平台能独立启动使用
- LlmGateway 统一从数据库读取运行时配置，优先于默认值

**3. UI 页面重构（腾讯视频风格）**
- DopaminePage 整体视觉升级：Hero 卡片、精致卡片式布局
- 用餐方式改为大图标卡片选择（外卖/堂吃/自做）
- 推荐按钮、结果卡片统一圆角 + 投影风格

**4. 口味输入改为 输入框 + 下拉选项**
- 支持自由输入任意口味词，也可从下拉列表快速选择
- 底部 6 个常用口味快捷标签，一键选择
- 预设 12 种口味：酸辣/清淡/甜口/咸鲜/重口/轻食…

**5. 可视化页面增强**
- 设置页：配置状态徽章（已配置/未配置）
- 提供商说明卡片，快速定位各平台注册地址

---

### v2.1.0 🌤️（本次更新）

**1. 大模型提示更友好**
- 未配置大模型/Key 无效时，不再抛出技术错误（如「流式调用失败 HTTP 401」），
  改为直白提示：「⚠️ 未配置大模型，请先到「设置」页面选择厂商并填写 API Key，即可使用办公助手/美食推荐功能」
- 修正设置页历史残留错字「柴GPT」和注册地址错误，统一为 ChatGPT + 正确官方网址

**2. 天气服务升级：支持中文城市名，无需查 Location ID**
- 后端新增「和风天气 GeoAPI 城市解析」，中文城市名（北京/上海/成都…）→ 自动解析为 LocationID
- 设置页「城市 Location ID」字段改为「城市名」，默认「北京」
- 新增 24h 城市解析缓存 + 10min 天气数据缓存，避免频繁调用
- 兼容数字 LocationID（老用户无感）
- 设置页新增天气 Tab：Key + 城市名 + 缓存时长 + 4 步使用说明
- 后端新增 `WeatherConfigDTO`、`/api/config/weather` GET/POST、`ConfigService` 持久化方法
- 配置优先级：数据库运行时配置 > application.properties

**3. 天气可视化：7 种 SVG 线条图标**
- 顶部 TopBar 不再使用 emoji，根据实时天气文字渲染轮廓风 SVG：
  ☀晴 / ⛅多云 / ☁阴 / 🌧雨 / ❄雪 / ⚡雷暴 / 🌫雾霾
- 同时展示温度和体感温度；未配置 Key 时显示「—」占位图标

**4. UI 风格统一：主配色白色 + 透明轮廓风**
- 所有页面统一为纯白色背景 + 浅灰边线 + 深灰文字（`--brand / --border / --text / --text-muted`）
- 渐变、彩色块、emoji、花哨阴影全部移除
- Logo、侧边栏、导航、设置、天气图标一律改为无填充 SVG 线条轮廓
- TopBar 重构为 Logo + 导航 + 天气 + 时间 + 设置按钮的极简行
- WorkPage 改为两栏分隔线布局，去除卡片间距的花哨留白
- DopaminePage / ChatPanel / FlowTable 统一白底细边框

**5. 其它修复**
- 修正设置页中「dev.qweather.com/docs/api/geo/city-lookup/」404 链接，改为直接使用中文城市名 + 前端可视化输入
- 前端构建产物 123.95 kB（gzip 46.44 kB），后端 44 源文件全部编译通过

---
