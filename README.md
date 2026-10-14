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
| 双页模式 | 办公模式 / 多巴胺模式 / 我的，左侧一键切换 |
| 多套 API 配置 | 文本 / 图片 / 语音 / 视频 / 向量化 / 天气 六套独立配置，保存多套 Key，切换即用，删除即物理删除防泄露 |
| 多模态生成 | 聊天顶栏一键切 Tab：智能 / 文本 / 图片 / 转写(ASR) / 朗读(TTS) / 视频，对应模型调用对应接口 |
| 任务留痕（我的） | 已上交 / 已完成 / 已删除任务历史三栏展示，软删除不丢数据，已删除支持硬删除 |
| RAG 知识库 | 我的页面建库，文本/文件自动分块向量化存 Redis，对话时优先检索知识库内容注入大模型 |

## 技术栈

```
┌─────────────────────────────────────────────────┐
│  前端        Vue 3.5 + Vite 6 + Vue Router 4    │
│  后端        Spring Boot 4.1.1 (Java 17)        │
│  数据库      MySQL 8.0 (HikariCP 连接池)        │
│  缓存/向量   Redis（知识库 embedding 向量存储）  │
│  ORM        Spring Data JPA + Hibernate 7       │
│  大模型     OpenAI 兼容协议（可换任意厂商）       │
│  天气服务   和风天气 API                          │
└─────────────────────────────────────────────────┘
```

## 项目结构

```
duobaan/
├── src/main/java/org/example/duobaan/
│   ├── config/              # 配置类（异步线程池、RestClient、RedisConfig、SchemaMigrator 幂等迁移）
│   ├── controller/          # REST API 控制层（KnowledgeController 知识库）
│   ├── model/               # 实体 + DTO（ApiProfile / Task 软删除 / KnowledgeBase / KnowledgeChunk）
│   ├── repository/          # Spring Data JPA 仓库（含 TaskHistoryRepository 绕过软删）
│   └── service/             # 业务逻辑层（ApiProfileService 多套配置 / KnowledgeService 分块检索 / EmbeddingService / RedisVectorService）
├── src/main/resources/
│   ├── application.properties   # 应用配置
│   └── schema.sql               # 建表脚本（启动自动执行）
├── frontend/
│   ├── src/
│   │   ├── api/index.js         # API 调用封装
│   │   ├── components/         # 组件（ChatPanel/FlowTable/SideRail 等）
│   │   ├── views/               # 页面（WorkPage/DopaminePage/SettingsPage/MinePage）
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
| Redis | ≥ 3.2 | 知识库向量缓存（可选，未启动时检索自动降级；Windows 版需把 RDB 目录改为可写路径） |

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

> **重要变更（2026）**：和风自 2026 年起**全面停用旧公共域名**（geoapi.qweather.com / devapi.qweather.com / api.qweather.com），旧地址统一返回 **HTTP 404**。现在必须使用你账号的**个人专属 API Host**（形如 `abc123xyz.def.qweatherapi.com`，在控制台 `设置 → API Host` 页面复制）。

1. 打开 [console.qweather.com](https://console.qweather.com) 注册账号
2. **「设置 → API Host」** 复制你的个人专属 Host（形如 `abc123xyz.def.qweatherapi.com`）
3. 新建项目 → 「凭据」创建 API Key：认证方式选 **API 密钥**、启用的 API 选 **指定 API**，只勾选下表 3 个（多勾选浪费额度，少勾选会调用失败）：

| API 名称 | 用途 | 对应路径（相对 API Host） | 免费 / 收费 |
|---|---|---|---|
| **GeoAPI** | 中文城市名 → LocationID 解析（必填，路径 `/geo/v2/city/lookup`） | `/geo/v2/city/lookup` | ✅ 免费 |
| **天气预报** | 实时天气 + 体感温度（=实况天气） | `/v7/weather/now` | ✅ 免费 |
| **天气指数** | 穿衣/紫外线等生活指数（保留能力） | `/v7/indices/1d` | ✅ 免费（额度有限） |

> ❌ **不要勾选**：分钟降水、辐照、海洋、空气质量、热带气旋、时光机、天气预警、天文 — 属于**高级付费**项目，本项目暂未使用。

4. 进入系统「**设置 → 天气服务**」页面，依次填：API Host（第 2 步复制）、API Key（第 3 步生成）、**具体城市名**（填「成都」不要填省份「四川」；如「北京 / 上海 / 绵阳」等）→ 保存。

> 也支持在 `application.properties` 预配置：
> ```properties
> duobaan.weather.api-host=abc123xyz.def.qweatherapi.com
> duobaan.weather.api-key=你的和风天气Key
> duobaan.weather.location=成都      # 中文城市名或数字 LocationID
> duobaan.weather.cache-ttl-seconds=600
> ```
>
> 保存后右上角天气区三态展示：
> - **虚线边框 + ❓**：未配置（缺 Host 或 Key）
> - **红色虚线边框 + ⚠️**：已配置但调用失败（鼠标悬停会直接显示中文原因，比如「404 旧公共域名已停用」「API Key 无效」「GeoAPI 未授权」「城市[四川]未找到，请改成成都」）
> - **正常边框**：查询成功；自动每 60 秒轮询，点击立即手动刷新。

##### 3.1.1 和风天气常见问题 FAQ

配置好后，如果右上角天气显示的是红色虚线 ⚠️，**先鼠标悬停天气卡片** 即可看到中文原因，下面是常见错误与解决方案：

| 错误提示 | 原因 | 解决方案 |
|---|---|---|
| **未填写 API Host（和风 2026 年起已停用旧公共域名…）** | 只填了 Key，没填专属 Host | 打开 `console.qweather.com → 设置 → API Host`，复制形如 `abc123xyz.def.qweatherapi.com` 的字符串填回"设置 → 天气服务 → API Host" |
| **API Host 格式不正确，应为 xxx.def.qweatherapi.com** | Host 填成了路径（`/v7/weather/now`）、完整 URL（`https://.../xxx` 后面多了路径）、或填的仍是旧公共域名（geoapi/devapi/api.qweather.com） | 只填主机名部分，例如 `abc123xyz.def.qweatherapi.com`，不要 `/` 结尾，不需要加 `https://`（代码会自动补全） |
| **API Host 路径 404（旧公共域名 geoapi/devapi.qweather.com 已停用…）** | 你填的 Host 不在该账号下或已过期；或 API Host 本身是对的、但请求 `/geo/v2/city/lookup` 返回 404（比如少了 `geo/` 前缀） | 1. 确认 Host 来自「控制台-设置」且当前 Key 在该账号下；2. 不要手动改路径；3. 删除当前输入，重新复制粘贴一次 |
| **API Key 无效（401）— 请到设置页核对 Key 或检查「天气预报」授权** | Key 粘贴错/多复制了空格，或凭据里没有勾选"天气预报" | 1. 回到 `console.qweather.com → 凭据` 重新复制完整 Key 粘贴（无空白字符）；2. 凭据里「启用的 API → 指定 API」必须已勾选 **天气预报** |
| **Host/项目无权限调用天气预报（403）** | Host 和 Key 不在同一个项目，或凭据未把"天气预报"加到指定 API | 1. 确认你复制的 API Host、创建的 Key 属于同一个账号下的**同一个项目**；2. 进入凭据编辑页，按「指定 API」重新勾选天气预报/GeoAPI/天气指数后保存 |
| **城市[xxx]未找到（注意填写具体城市，不要用省名；例如把"四川"改为"成都"）** | 填的是省份名（"四川""广东""浙江"…）或县级以下无法被 GeoAPI 直接命中的地名 | 改成省级下面的地级市或区名，如"成都""广州""杭州""绵阳"；实在查不到就直接和风官方"常见城市列表"里复制数字 LocationID 填进来（纯数字会直接用，跳过 GeoAPI） |
| **API Key / Host 鉴权失败（HTTP 403）— 请核对 Key、确认 Host 归属该 Key、以及 GeoAPI 已勾选** | 典型情形：Key 是项目 A 但 Host 复制的是另一个账号/另一个项目 B；或未勾选 GeoAPI | 1. 同一个项目里拿 Host 和 Key；2. 「指定 API」里确保已勾选 **GeoAPI**（单独只勾选天气预报不会自动给 GeoAPI 权限）；3. 建议新建项目+新建凭据，保证用的是同一套 |
| **调用过于频繁（429，QPM超限）** | 免费版每分钟配额有限 | 等 1-2 分钟再试；或在设置页把"缓存时长"调大（推荐 600 秒） |
| **账号欠费（402）** | 额度用完或付费套餐过期 | `console.qweather.com → 财务` 充值或升级到对应套餐 |
| **和风天气服务器异常（code=500/501）** | 和风服务端故障 | 稍后重试；这期间天气卡片优先返回上一次 OK 的旧数据兜底 |
| 保存后**刷新页面仍没有变化**（最常见） | 改了 class，但后端进程还在用旧 class（devtools 热加载在部分改动下不会自动生效） | **重启后端服务**（在 IDE 里点"停止"再"运行 DuobaanApplication"；或命令行先 `Ctrl+C` 再 `mvn spring-boot:run`） |
| 保存配置后右上角**过了 10 分钟才变化** | 你在后端 WeatherService 的 TTL 缓存窗口里 | 直接**点击右上角天气卡片**就会立即刷新（缓存只影响被动轮询） |

> **调试小技巧**：把右上角天气卡片上显示的中文原因贴到搜索引擎或 Issue 里，基本能直接对应上表某一条并完成自愈。如果以上都没命中，可 `curl http://localhost:8080/api/weather/now` 看完整 JSON（`status` / `message` 字段里有详细说明）。

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

#### 3.3 向量化模型（RAG 知识库，可选）

RAG 知识库需要一个 embedding 模型把文本转成向量。向量化模型**独立配置**，不复用文本模型。

1. 启动本地 Redis（默认 `localhost:6379`，无密码），向量以 JSON 数组存 Redis，检索时内存算余弦相似度
2. 进入「**设置 → 🧮 向量化模型**」，选择厂商或自定义，填 Base URL / API Key / 模型 ID（如千问 `text-embedding-v3`、OpenAI `text-embedding-3-small`）
3. 保存并点击「使用」激活。Tab 右上角绿点亮起表示就绪
4. 进入「**我的 → 知识库**」新建知识库，粘贴文本或上传 `.txt/.md`，自动分块+向量化

> **常见坑**：如果上传内容时还没配向量化模型，分块文本存了 MySQL 但向量没存，检索找不到。配好 embedding 后，在知识库展开页点「**重新向量化**」即可对已有分块补做向量化。

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
| `task` | 流程表任务 | title, category, task_group(TODAY/TOMORROW), task_status(TODO/DOING/DONE/SUBMITTED), source(LLM/MANUAL), estimated_minutes, due_at, submitted_at, deleted(软删标记), deleted_at |
| `chat_message` | 对话记录 | mode(DOPAMINE/WORK), chat_role, content（每个 mode 只保留最近 50 条，超出自动裁剪） |
| `api_profile` | 多套 API 配置 | profile_type(LLM/IMAGE/AUDIO/VIDEO/EMBEDDING/WEATHER), name, provider, base_url, model, api_key, location(天气城市), cache_ttl_seconds(天气缓存秒数), timeout_seconds, is_active |
| `knowledge_base` | 知识库 | name, description, split_strategy(PARAGRAPH/SENTENCE/MARKDOWN/SIMPLE), chunk_size, chunk_overlap |
| `knowledge_chunk` | 知识库分块 | kb_id, content, chunk_index, created_at（`embedding` 列保留但恒为 NULL，向量实际存 Redis） |
| `system_config` | 旧版遗留键值配置 | cfg_key, cfg_value, updated_at（存 v2.x 的 llm.config / weather.config，启动时自动迁移为 `api_profile`） |

> **字段映射提醒**：`api_profile` 里**没有** `api_host` 列 —— 天气的 API Host 复用 `base_url` 列（`location` / `cache_ttl_seconds` 仅天气使用）；旧文档里的 `model_name` 也是错的，实际列名是 `model`。

### 建表策略

- **启动执行**：`spring.sql.init.mode=always`，每次启动都运行 `schema.sql`
- **幂等**：`CREATE TABLE IF NOT EXISTS`，表已存在则跳过
- **不自动改表**：`ddl-auto=none`，Hibernate 不干预表结构
- **修改表结构**：手动 `ALTER TABLE` 或修改 `schema.sql` 后手动 DROP 旧表
- **SchemaMigrator 幂等迁移**：v3.0 起启动时自动检测 `task` 表是否缺 `deleted/deleted_at/submitted_at` 列，缺则补加，老库升级无需手动改表

### 数据安全说明

| 场景 | 策略 |
|---|---|
| API Key 删除 | **物理删除**：前端在"设置页"删除某套配置后，后端直接 `DELETE FROM api_profile`，数据库不留明文 Key，防止泄露 |
| 任务删除 | **软删除**：前端删除任务后，仅设置 `deleted=1` 并记录 `deleted_at`，查询时默认过滤；"我的"页可查看已删除任务，满足工作留痕 |
| 任务硬删除 | **物理删除**：在「我的 → 已删除」中点「彻底删除」，后端用原生 SQL 绕过 `@SQLDelete` 直接 `DELETE FROM task`，不可恢复 |
| 任务上交 | 提交时写入 `submitted_at`，在"我的 → 已上交"中可查询 |

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

### 时间 / 天气

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/time/now` | 获取当前时间 |
| GET | `/api/weather/now` | 获取当前天气（`status`：OK / UNCONFIGURED / ERROR；ERROR 时 `message` 为中文原因；未配置时 `configured=false`） |

### 大模型

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/llm/chat/stream` | SSE 流式对话（delta/done/error 事件），对话双方自动入库 chat_message |
| POST | `/api/llm/chat` | 非流式对话（兼容），消息同样持久化 |
| POST | `/api/llm/parse-tasks` | 自然语言拆单为任务列表 |
| GET  | `/api/llm/history?mode=WORK\|DOPAMINE&limit=50` | 拉取最近 N 条对话历史（时间正序），前端 ChatPanel 挂载时调用恢复上次对话 |

### 任务

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/tasks` | 查询当前任务列表（已完成/已上交/软删除的不返回） |
| GET | `/api/tasks/mine` | 查询我的历史：已上交 / 已完成 / 已删除三栏（绕过软删过滤） |
| POST | `/api/tasks` | 创建任务 |
| PATCH | `/api/tasks/{id}` | 更新任务（状态/分组等） |
| DELETE | `/api/tasks/{id}` | **软删除**任务（`deleted=1` + `deleted_at=NOW`），工作留痕 |
| DELETE | `/api/tasks/{id}/hard` | **硬删除**任务（物理 DELETE 行），不可恢复 |
| POST | `/api/tasks/{id}/migrate` | 迁移分组（今日↔明日） |
| POST | `/api/tasks/submit` | 上交今日小结（今日 DONE → SUBMITTED） |
| POST | `/api/tasks/bulk` | 批量创建任务（拆单写入） |

### 知识库（RAG）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/knowledge/bases` | 列出所有知识库 |
| POST | `/api/knowledge/bases` | 新建知识库（name/description/splitStrategy/chunkSize/chunkOverlap），描述非空时自动分块入库 |
| DELETE | `/api/knowledge/bases/{id}` | 删除知识库（含分块和 Redis 向量） |
| POST | `/api/knowledge/bases/{id}/documents` | 粘贴文本入库（自动分块+向量化） |
| POST | `/api/knowledge/bases/{id}/upload` | 上传 .txt/.md 文件入库 |
| GET | `/api/knowledge/bases/{id}/chunks` | 查看该知识库的分块列表 |
| DELETE | `/api/knowledge/bases/{id}/chunks` | 清空该知识库分块（同时清 Redis 向量） |
| POST | `/api/knowledge/bases/{id}/revectorize` | 对已有分块重新向量化（配好 embedding 后补做向量） |
| POST | `/api/knowledge/search` | 直接用向量检索知识库（body：`{query}`），返回 top-4 命中分块，便于调试检索效果 |
| GET | `/api/knowledge/status` | 查询 RAG 状态（embeddingReady / redisOk / hint） |

### 多模态生成（图片 / 语音 / 转写 / 视频）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/media/image` | 文生图（body：prompt/size/n/mode）；Dashscope 千问/万相自动走原生异步接口，其余走 OpenAI 兼容 `/images/generations` |
| POST | `/api/media/speech` | 文生语音 TTS（body：input/voice/format/speed/mode） |
| POST | `/api/media/transcribe` | 音频转写（multipart `file` + 可选 `mode`）；转写文本以 **user** 身份入库，避免把用户音频内容当成助手发言放进上下文 |
| POST | `/api/media/video` | 文生视频（body：prompt/seconds/ratio/mode） |
| GET | `/api/media/poll-task` | 查询 Dashscope 异步任务（`taskId` + `profileId` + `kind=IMAGE\|VIDEO`）；前端每 5 秒轮询一次、最多 12 次（约 60 秒） |

> 未配置对应模型时，以上接口统一返回 HTTP 200 + `status: "degraded"` + 中文提示，不打断对话；富媒体结果以 `%%RICH_MEDIA%%` 前缀 + JSON 存入 `chat_message`，刷新页面可还原图片 / 音频 / 视频。

### 配置（系统 & 多套 API 配置）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/config/providers/all` | 一次返回 6 种类型（LLM/IMAGE/AUDIO/VIDEO/EMBEDDING/WEATHER）的厂商预设，设置页按 Tab 渲染 |
| GET    | `/api/config/profiles?type=LLM\|WEATHER` | 列出某类型的全部 API 配置（不含明文 Key，仅打码显示） |
| GET    | `/api/config/profiles/active?type=...` | 取某类型当前生效配置（无则返回 204） |
| GET    | `/api/config/profiles/{id}` | 取某套配置详情 |
| POST   | `/api/config/profiles` | 新建一套 API 配置 |
| PUT    | `/api/config/profiles/{id}` | 更新某套配置（Key 传空或打码串时保留原 Key） |
| DELETE | `/api/config/profiles/{id}` | **物理删除**某套配置（数据库行直接删除，防 Key 泄露） |
| POST   | `/api/config/profiles/{id}/activate` | 激活某套配置（设为 active，立即生效） |
| GET | `/api/config/providers` | 旧接口：仅返回 LLM 文本厂商预设（兼容 v3.0 之前前端） |
| GET  | `/api/config/llm` | 旧接口（兼容 v2.1）：取当前生效 LLM 配置，Key 打码 |
| POST | `/api/config/llm` | 旧接口：保存 LLM 配置（同名自动配置原地更新，否则新建） |
| GET  | `/api/config/weather` | 旧接口（兼容 v2.1）：取当前生效天气配置，Key 打码 |
| POST | `/api/config/weather` | 旧接口：保存天气配置（同名「系统自动保存-WEATHER」原地更新，否则新建） |

### 美食推荐

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/recommend/meal` | 美食推荐（body：心情 / 天气 / 口味 / 用餐方式） |
| POST | `/api/recommend/adopt?mealTitle=xxx` | 采纳推荐结果：写入「今日」流程表并标记为已完成（query 参数，长度 ≤200） |

---

## 三页说明

- **办公模式**（默认）：侧边栏"办公"图标，流程表 + 办公对话
- **多巴胺模式**：侧边栏"多巴胺"图标，美食推荐 + 生活助手
- **我的**：侧边栏"我的"图标，四栏展示已上交 / 已完成 / 已删除任务 + 知识库管理。任务删除后可在「已删除」中找回或硬删除；知识库支持建库、分块策略配置、文本/文件入库、重新向量化。

点击左侧图标即可切换；对话历史按办公/多巴胺模式隔离存储；任务删除后可在「我的 → 已删除」中找回或彻底删除；对话中提及知识库内容时自动优先检索注入大模型。

---

## 多套 API 配置使用说明

v3.0 起，大模型和天气配置不再只能保存一套，支持保存**多套 Profile**，随时切换"当前生效"。

### 操作步骤

1. 进入「设置 → 大模型」或「设置 → 天气服务」
2. 下方「已保存配置」区显示已有的多套 Profile，每套显示名称/厂商/模型/打码 Key，**当前生效那套会带绿色"使用中"徽章**
3. 新建一套：填完上方表单 → 「保存为新配置」→ 列表新增一条
4. 切换当前生效：点击某条右侧「使用」按钮 → 绿色徽章移到该条，下次调用立即使用该套 Key
5. 编辑某套：点击「编辑」→ 表单填入该套的内容 → 修改后「更新保存」
6. 删除某套：点击「🗑 删除」→ 后端**直接物理删除数据库行**，明文 Key 不再保存在 DB 中，防止泄露

### 升级兼容

- 旧版本（v2.0/v2.1）配置存储在内存 `system_config`，启动时自动迁移为 `api_profile` 的 active profile，老用户**无需重新填 Key**
- 旧接口 `/api/config/llm` 和 `/api/config/weather` 继续可用，内部读写当前 active profile，前后端无感

---

## License

MIT

---

## 版本更新记录

### v1.0.0 🚀（初始版本 — 首个可用版本）

**核心功能**
- 办公流程表：今日/明日分组，CRUD + 状态流转（待办/进行中/完成/已上交），上交小结，零点自动转组
- 多巴胺推荐：结合心情/天气/口味/用餐方式，大模型推荐美食
- 实时天气：和风天气 API，中文城市名自动解析，7 种 SVG 线条图标，60 秒轮询
- 实时时间：顶栏展示时间/星期/时段
- 大模型对话：SSE 流式输出，办公/多巴胺双模式，对话历史持久化（每 mode 限 50 条，刷新自动恢复）
- 智能拆单：自然语言 → 结构化任务列表，一键写入流程表
- 多模态生成：聊天顶栏 6 Tab（智能/文本/图片/转写/朗读/视频），对应模型调用对应接口；异步图片/视频任务持久化 + 自动轮询
- 任务留痕（我的）：已上交/已完成/已删除三栏历史，软删除不丢数据

**技术架构**
- 后端：Spring Boot 4.1.1 (Java 17) + Spring Data JPA + Hibernate 7
- 前端：Vue 3.5 + Vite 6 + Vue Router 4
- 数据库：MySQL 8.0 (HikariCP)，schema.sql 幂等建表，SchemaMigrator 自动补列
- 大模型：OpenAI 兼容协议，URL 强力清洗 + 厂商识别鲁棒化

---
### v2.0.0 🧠📚（本次更新：RAG 知识库系统 + 任务流优化）

**两大功能上线**

#### 一、RAG 知识库（检索增强生成）

对话时提及知识库内容，大模型优先检索并注入上下文回答，类似 FastGPT。

**1. 数据模型**

- 新增 `knowledge_base` 表：name / description / split_strategy / chunk_size / chunk_overlap
- 新增 `knowledge_chunk` 表：kb_id / content / chunk_index（向量不存 MySQL，存 Redis）
- `ApiProfileType` 枚举新增 `EMBEDDING`，向量化模型**独立配置**，不复用文本模型

**2. 向量存储：本地 Redis**

- 引入 `spring-boot-starter-data-redis` + Jedis，配置 `localhost:6379`
- 向量以 JSON 数组存 Redis：`kb:vec:{chunkId}` 存向量，`kb:chunks:{kbId}` 登记分块
- 检索时批量 MGET 全部向量，**内存算余弦相似度**（不依赖 RediSearch 向量索引，兼容性更好）
- Redis 未启动时检索自动降级跳过，不影响对话

**3. 分块策略（4 种）**

| 策略 | 说明 |
|---|---|
| PARAGRAPH（默认） | 按段落聚合，接近 chunkSize 时切分，相邻块重叠 overlap 字符 |
| SENTENCE | 按句末标点（。！？.!?）切分，再按 chunkSize 聚合 |
| MARKDOWN | 按 # 标题切分，单块过长时按段落再切 |
| SIMPLE | 固定长度硬切，带 overlap 重叠 |

**4. 检索注入**

- `KnowledgeService.search(query, topK=4)`：向量化问题 → 取所有知识库向量 → 余弦相似度 ≥ 0.35 的 top-4 分块
- `LlmGatewayService` 在办公/多巴胺对话前调用 `buildSystemContext()`，命中的分块拼进系统提示词
- 未命中或 embedding 未配置时不注入，正常对话

**5. 「重新向量化」补救**

- 如果上传时未配 embedding，分块文本存了 MySQL 但向量没存
- 配好 embedding 后，在知识库展开页点「**重新向量化**」，分批（每批 16 条）重新调 embedding 接口写入 Redis

**6. 创建知识库时描述自动入库**

- 新建知识库时若 description 非空，自动把描述当内容分块入库（避免用户把资料写在描述里却没有分块的问题）

#### 二、任务流优化

**1. 完成即隐藏，只在「我的」可见**

- `TaskService.list()` 返回今日/明日列表时过滤掉 `DONE` 和 `SUBMITTED`
- 前端 `FlowTable.toggle()` 勾选完成（TODO→DONE）后立即从本地列表移除
- 已完成任务在「我的 → 已完成」可查

**2. 已删除支持硬删除**

- 后端 `TaskRepository.hardDelete()` 用原生 SQL `DELETE FROM task` 绕过 `@SQLDelete`（软删除注解）
- 新接口 `DELETE /api/tasks/{id}/hard`
- 「我的 → 已删除」每个卡片右下角加「彻底删除」按钮，二次确认后物理移除，不可恢复

**3. 移除自动执行任务指令**

- ChatPanel 删除「自动执行任务指令」解析器（LLM 输出格式不稳定，且容易谎称已完成但实际未操作）
- 办公系统提示词新增硬约束：明确告知 AI 没有修改流程表的权限，要求用户手动点按钮操作

#### 三、设置页 UI 优化

- 「系统设置」副标题移除
- 「向量化模型」Tab 配置完成后显示绿色就绪点（`readyState` 补充 embedding 类型）

#### 四、启动兼容性修复

- `RedisConfig` 的 `timeout` 字段从 `int` 改为 `java.time.Duration`，兼容 `3000ms` 格式
- `DuobaanApplication` 排除 `JmxAutoConfiguration`，避免 JedisPool 被注册为 MBean 时 devtools 热重启冲突

**文件变更**

| 文件 | 变更 |
|------|------|
| `schema.sql` | 新增 knowledge_base / knowledge_chunk 表 |
| `KnowledgeBase.java` / `KnowledgeChunk.java` | 知识库与分块实体 |
| `KnowledgeService.java` | 分块（4 策略）/ CRUD / 余弦检索 / 重新向量化 / 创建时描述自动入库 |
| `EmbeddingService.java` | 独立 embedding 配置，调 `/embeddings` 接口 |
| `RedisVectorService.java` | Redis 向量存取 + 批量检索 |
| `RedisConfig.java` | JedisPool Bean（Duration 超时） |
| `KnowledgeController.java` | 知识库 REST 接口 |
| `TaskRepository.java` | 新增 hardDelete 原生 SQL |
| `TaskService.java` | list 过滤 DONE/SUBMITTED + hardDelete |
| `TaskController.java` | 新增 DELETE /{id}/hard |
| `DuobaanApplication.java` | 排除 JmxAutoConfiguration |
| `MinePage.vue` | 知识库管理 UI + 已删除硬删除按钮 + 知识库徽标加载修复 |
| `SettingsPage.vue` | 向量化模型独立 Tab + 就绪绿点 |
| `FlowTable.vue` | 勾选完成后从列表移除 |
| `ChatPanel.vue` | 移除自动执行任务指令 |
| `api/index.js` | 新增知识库/硬删除 API |

---
### v3.0.0 🛡🧩（稳定性加固 + 多套 Profile 统一存储 + 文档修订）

**1. 引入多套 API Profile 体系（文本 / 图片 / 语音 / 视频 / 向量化 / 天气 六类）**

- 六类各自保存多套 Profile，随时切换「当前生效」那套（此前每类只能存一套）
- 删除某套 = **物理删除数据库行**，明文 Key 不留残片；列表/详情接口只返回打码 Key
- 旧 v2.x 存在 `system_config` 的 `llm.config` / `weather.config` 在启动时自动迁移为 active profile，老用户无需重填 Key
- 旧接口 `/api/config/llm`、`/api/config/weather` 继续可用；重复保存同名自动配置时**原地更新**，不再堆积重复行
- `api_profile` **无 `api_host` 列**：天气 Host 复用 `base_url`，城市与缓存分别用 `location` / `cache_ttl_seconds`

**2. 稳定性与安全加固**

- 对话历史按 mode 只保留最近 50 条，用**单条原生 SQL** 裁剪，减少数据库往返
- 出站请求统一 URL 清洗（剥 Markdown 包络字符、去掉误粘的 action 路径）+ 白名单校验（禁 localhost / 内网 / 链路本地地址），媒体与天气请求均受保护
- 全局异常统一中文文案：参数不合法 / 上传超 25MB（413）/ 类型不符（400）、并发写入冲突（503，自动重试一次）
- 异步任务查询做 kind ↔ profileType 归属校验，防止越权读取其它 Profile 的任务；taskId 仅接受 `[0-9a-zA-Z_-]`
- 媒体接口未配置模型时返回 HTTP 200 + `status=degraded` + 中文提示，不中断对话
- `SchemaMigrator` 启动时幂等补列/补索引（task 软删列、chat_message 索引、api_profile 枚举改 VARCHAR 等），老库升级无需手动改表

**3. 文档修订**

- 修正「表结构 / 接口一览」与代码不一致处：`api_profile` 字段名（`model_name`→`model`、删除不存在的 `api_host`）、补 `system_config` 表、补多模态与知识库检索等端点

---
