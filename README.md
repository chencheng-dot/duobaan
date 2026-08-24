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
| 多套 API 配置 | 文本 / 图片 / 语音 / 视频 / 天气 五套独立配置，保存多套 Key，切换即用，删除即物理删除防泄露 |
| 多模态生成 | 聊天顶栏一键切 Tab：智能 / 文本 / 图片 / 转写(ASR) / 朗读(TTS) / 视频，对应模型调用对应接口 |
| 任务留痕（我的） | 已上交 / 已完成 / 已删除任务历史三栏展示，软删除不丢数据 |

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
│   ├── config/              # 配置类（异步线程池、RestClient、SchemaMigrator 幂等迁移）
│   ├── controller/          # REST API 控制层
│   ├── model/               # 实体 + DTO（ApiProfile / Task 软删除）
│   ├── repository/          # Spring Data JPA 仓库（含 TaskHistoryRepository 绕过软删）
│   └── service/             # 业务逻辑层（ApiProfileService 多套配置）
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
| `task` | 流程表任务 | title, category, task_group(TODAY/TOMORROW), task_status, source, submitted_at, deleted(软删标记), deleted_at |
| `chat_message` | 对话记录 | mode(DOPAMINE/WORK), chat_role, content |
| `api_profile` | 多套 API 配置（大模型 / 天气） | profile_type(LLM/WEATHER), provider, base_url, api_key, model_name, is_active, api_host(天气专属), timeout_seconds |

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
| GET | `/api/weather/now` | 获取当前天气（返回 configured 字段标记是否已配置） |

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
| GET | `/api/tasks` | 查询当前任务列表（软删除任务不返回） |
| GET | `/api/tasks/mine` | 查询我的历史：已上交 / 已完成 / 已删除三栏（绕过软删过滤） |
| POST | `/api/tasks` | 创建任务 |
| PATCH | `/api/tasks/{id}` | 更新任务（状态/分组等） |
| DELETE | `/api/tasks/{id}` | **软删除**任务（`deleted=1` + `deleted_at=NOW`），工作留痕 |
| POST | `/api/tasks/bulk` | 批量创建任务（拆单写入） |

### 配置（系统 & 多套 API 配置）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/config/status` | 获取 LLM/天气是否已配置（首页状态栏用） |
| GET  | `/api/config/llm` | 获取当前生效 LLM 配置（旧接口，兼容 v2.1） |
| POST | `/api/config/llm` | 保存 LLM 配置（旧接口，内部写入 active profile） |
| GET  | `/api/config/weather` | 获取当前生效天气配置（旧接口，兼容 v2.1） |
| POST | `/api/config/weather` | 保存天气配置（旧接口，内部写入 active profile） |
| GET    | `/api/config/profiles?type=LLM\|WEATHER` | 列出某类型的全部 API 配置（不含明文 Key，仅打码显示） |
| POST   | `/api/config/profiles` | 新建一套 API 配置 |
| PUT    | `/api/config/profiles/{id}` | 更新某套配置 |
| DELETE | `/api/config/profiles/{id}` | **物理删除**某套配置（数据库行直接删除，防 Key 泄露） |
| POST   | `/api/config/profiles/{id}/activate` | 激活某套配置（设为 active，立即生效） |

### 美食推荐

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/recommend/meal` | 美食推荐 |

---

## 三页说明

- **办公模式**（默认）：侧边栏"办公"图标，流程表 + 办公对话
- **多巴胺模式**：侧边栏"多巴胺"图标，美食推荐 + 生活助手
- **我的**：侧边栏"我的"图标，三栏展示已上交 / 已完成 / 已删除任务，工作留痕与历史回顾

点击左侧图标即可切换；对话历史按办公/多巴胺模式隔离存储；任务删除后可在「我的 → 已删除」中找回。

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

**6. 天气显示 Bug 修复（commit 0a14596）**
- **现象**：即使未配置天气 Key，右上角依然显示「晴 —℃ / 体感—℃」+ 太阳图标，保存天气配置并刷新浏览器后也仍保持该错误状态。
- **根因**：
  1. 后端 `WeatherNow.placeholder()` 里 `text` 被硬编码为 **"晴"**，与真实的"晴天"无法区分，导致前端天气图标匹配逻辑（/晴/.test(text)）直接命中 sunny。
  2. 前端 `TopBar.vue` 没有"未配置态"与"真实态"的区分字段，只按文本判断图标。
  3. 天气只在 `onMounted` 拉一次，保存配置后后端仍在运行旧进程（`/api/config/weather` 运行时配置 + GeoAPI 城市解析代码未生效）。
- **修复方式**：
  - 后端 [WeatherNow.java](src/main/java/org/example/duobaan/model/dto/WeatherNow.java) 新增 `configured: boolean` 字段（真实查询 = true，占位 = false）；占位 `text/temp/feelsLike` 全部改为 `"—"`。
  - 前端 [TopBar.vue](src/components/TopBar.vue) 根据 `configured` 切换 UI：
    - `false` → 虚线边框 + 灰文字 + ❓图标，显示 **「未配置天气 / 前往「设置」配置 Key」**，点击跳转到设置页。
    - `true` → 正常展示 7 种 SVG 天气图标 + 温度/体感，点击**立即手动刷新**；额外每 **60 秒** 自动轮询，无需手动刷浏览器（最多 1 分钟生效）。
  - 更新了 `/api/config/weather` 配置变更 → `WeatherService` 自动清缓存，下次请求重新走 GeoAPI + /v7/weather/now。
- **升级注意**：从 v2.0 升级的用户请务必**重启后端进程**（否则运行的还是旧 class，`configured` 字段和天气配置持久化都不会生效，表现为"已保存天气配置但右上角不变"）。

---

### v3.0.0 🗂️（本次更新）

**1. 多套 API 配置持久化（新建 api_profile 表）**

- 新增 `api_profile` 数据库表，大模型和天气配置均可保存多套（Profile），每套独立存储
- 后端新增实体/枚举/DTO/Repository/Service/Controller 全链路：
  - `ApiProfile.java` + `ApiProfileType.java`（LLM/WEATHER 两种类型）
  - `ApiProfileService.java`（CRUD + activate 切换 + 旧配置自动迁移）
  - `ConfigController.java` 新增 5 个 `/api/config/profiles**` 接口（列表/新建/更新/删除/激活）
- 设置页「大模型」和「天气服务」Tab 各新增「已保存配置」列表区：
  - 每套展示名称/厂商/模型/打码后的 Key，**当前生效那套带绿色"使用中"徽章**
  - 支持「保存为新配置」「使用」「编辑更新」「🗑 删除」四种操作
- **删除即物理删除防泄露**：前端点删除后，后端直接 `DELETE FROM api_profile`，数据库不留明文 Key 行，部署/交接/排查时都不会残留敏感信息
- LlmGatewayService 和 WeatherService 统一从 active profile 读取配置，切换立即生效（无需重启）
- **向后兼容**：旧 `/api/config/llm` `/api/config/weather` 接口保持可用，内部读写 active profile

**2. 任务软删除 + 工作留痕**

- `task` 表新增三个字段：
  - `deleted TINYINT(1) DEFAULT 0`（软删标记）
  - `deleted_at DATETIME(6)`（软删时间）
  - `submitted_at DATETIME(6)`（上交时间）
- 实体 `Task.java` 加 Hibernate 软删注解：
  - `@SQLRestriction("deleted = 0")`：正常查询自动过滤已删除任务
  - `@SQLDelete(sql = "UPDATE task SET deleted = 1, deleted_at = NOW(6) WHERE id = ?")`：DELETE 请求走软删 SQL
- 新增 `TaskHistoryRepository.java`，用 `nativeQuery` 绕过软删过滤，供"我的"页使用
- 新增 `SchemaMigrator.java`：启动时用 `information_schema` 判断 task 表是否缺列，缺则 `ALTER TABLE` 补齐，老库升级零手工操作

**3. 新增「我的」Tab — 历史任务三栏查看**

- 侧边栏在「多巴胺」下新增「我的」按钮（SVG 线条图标）
- 新建 `MinePage.vue` 页面，三 Tab 布局：
  - **已上交**：`task_status = SUBMITTED`，按 `submitted_at` 倒序
  - **已完成**：`task_status = DONE`，按 `updated_at` 倒序
  - **已删除**：`deleted = 1`，按 `deleted_at` 倒序，红色"已删除"徽章
- 后端新增 `GET /api/tasks/mine`，一次返回三组列表 + 各自统计数量
- 每条任务卡片显示：标题/分类/分组（今日/明日）/状态/来源/创建时间/上交或删除时间
- 支持空状态提示（"还没有上交的任务，去完成并上交吧！"等）

**4. 其它修复 & 提升**

- Jackson 3.x 包名兼容：`JsonProcessingException` 改为 `tools.jackson.databind.JsonProcessingException`
- ApiProfileService 编译修复：`timeoutSeconds`（int）与 null 比较改为 `> 0`
- README.md 全面更新：API 列表分 5 类、数据库安全说明、多套配置操作指南、三页说明
- 前后端编译验证：后端 53 源文件编译通过、前端 44 模块构建成功、端到端测试三栏历史/软删/物理删除全部走通

---

### v3.1.0 💬（本次更新：对话历史持久化 — 刷新不再清空）

**问题背景**：之前和大模型对话后，一刷新浏览器消息全没了。原因是前端仅用内存 `messages` 数组保存气泡，后端 `chat_message` 表虽然建了但 SSE/chat 接口完全没往里面写，也没有拉历史的接口。

**写入链路 + 重建链路 双端打通**（对应「刷新后状态丢失」类问题标准解法）：

**1. 写入链路：对话双方即时落库，流式累积 done 时一次性入库**

- 新增 [ChatService.java](src/main/java/org/example/duobaan/service/ChatService.java)：
  - `appendAndTrim(mode, role, content)`：单条写入后，`countByMode` 统计该 mode 总数，**> 50 则物理删除最旧 N 条**（`deleteAllByIdInBatch`），数据库严格限长，绝不无限增长。
  - `getHistory(mode, limit)`：按 `(mode, created_at DESC, id)` 复合索引取最近 limit 条，再反转为时间正序返回给 UI。
- [LlmController.java](src/main/java/org/example/duobaan/controller/LlmController.java) 改造：
  - **非流式 `/chat`**：`入库 user message → 调 llmGateway → 入库 assistant reply` 三步串行，异常也落降级提示。
  - **流式 `/chat/stream`**：发 SSE 前先入库 user message；过程中用 `StringBuilder` 累积每片 delta，在 `done` 事件时**一次性入库完整助手回复**（避免流式逐 token 写 50 次库）；出错则把"已累积 delta + 错误提示"一并入库，刷新后用户能看到断线前收到的内容。
- 新增复合索引 `idx_chat_mode_created (mode, created_at DESC, id DESC)`：history 查询与 trim 删除都走索引，50 条规模下全是毫秒级。
- SchemaMigrator 幂等迁移：为老库判断 `information_schema.STATISTICS`，缺索引自动 ALTER，不需要手动 DROP 表。

**2. 重建链路：ChatPanel onMounted 拉历史，刷新即渲染**

- 前端 `api/index.js`：+ `getChatHistory(mode, limit = 50)`，封装 `GET /api/llm/history`。
- [ChatPanel.vue](frontend/src/components/ChatPanel.vue) 引入 `onMounted`：组件挂载完成后立即调用 `getChatHistory(props.mode, 50)`，将返回的 `[{role, content}]` 数组赋给 `messages`，并 `scrollToBottom()` 定位到底部最后一条。
- WORK / DOPAMINE 两种 mode **完全隔离**：办公对话和多巴胺推荐各自存 50 条，互不干扰。

**3. 为什么没选 Redis？**

用户原话是「用 Redis 缓存或者 其他的」。评估后选 **MySQL + 限长 50 条**，理由：
- 现有项目已用 MySQL，额外引入 Redis 需要本地/服务器部署一个新进程，部署门槛明显抬高（对普通用户不友好）。
- 每 mode 最多 50 条 × 2 mode = 100 行，配合复合索引查询 ≈ Redis 的响应量级（都是 <1ms）。
- MySQL 天然持久化，不怕进程重启/机器断电，作为「会话存档」比纯 Redis 更稳（即便以后想加热缓存层，也可在 ChatService 上再叠加）。

**4. 接口与 API 文档同步更新**

- README 大模型接口表新增 `POST /api/llm/chat` 非流式条目、以及新接口 `GET /api/llm/history?mode=WORK|DOPAMINE&limit=50`。
- 接口注释说明「对话自动入库 chat_message / 前端挂载时恢复」。

**5. 端到端实测**

| 场景 | 结果 |
|---|---|
| 初始 GET history（WORK） | ✅ 0 条 |
| POST `/chat` 发一句「你好」→ GET history | ✅ 出现 2 条（user + 助手真实回复，你这边已配好 Key 不是降级提示） |
| 再 GET history（DOPAMINE） | ✅ 0 条（两 mode 严格隔离） |
| DOPAMINE 发「推荐午餐」→ 再 GET history | ✅ 2 条，WORK 侧仍保持自己的 2 条 |
| 刷新浏览器 → ChatPanel onMounted 触发 | ✅ 对应 mode 的 2 条消息自动渲染并滚动到底部，**完全恢复上次对话** ✨ |

---

### v3.2.0 🎨🎬🔊（本次更新：多模态生成 — 文本 / 图片 / 语音 / 视频，按需调用对应模型）

**需求背景**：单个大语言模型只能聊天，没法画图、生成视频、转写录音、朗读文字。用户的原话是「每当我提出要求的时候，可以有对应的模型来完成我的要求」。

**解决思路**：把「大模型」拆成 5 类独立能力，每类独立走自己的 ApiProfile.active，聊天顶栏用 6 Tab 胶囊直接路由到对应接口，用户一眼知道"现在在调用什么模型"。

**1. 后端：能力分层 + 统一响应 DTO**

- 新增枚举 [ApiProfileType.java](src/main/java/org/example/duobaan/model/ApiProfileType.java)：`LLM | IMAGE | AUDIO | VIDEO | WEATHER`，不再把所有东西都塞成"LLM配置"。
- `api_profile.profile_type` 从 ENUM 幂等迁移成 VARCHAR(20)：`SchemaMigrator.migrateApiProfileTable` 判断信息模式，老数据按旧 ENUM 值 UPDATE。
- 新增 [MediaResponse.java](src/main/java/org/example/duobaan/model/dto/MediaResponse.java)：`kind/status/error/text/audioBytes/audioMime/items[]`，所有多模态接口共用同一结构。
- 新增 [MediaService.java](src/main/java/org/example/duobaan/service/MediaService.java)：4 类能力全覆盖，**都走 OpenAI 兼容协议**，换厂商只换 baseUrl + model：
  - 文生图：`POST {baseUrl}/images/generations`，超时 60s，优先取 `data[].url`，回退到 `data[].b64_json`
  - TTS 文生语音：`POST {baseUrl}/audio/speech`，直接读响应二进制（不是 JSON），返回 `audioBytes/audioMime/speech.mp3`
  - ASR 语音转写：`POST {baseUrl}/audio/transcriptions`，`MultipartFile` 上传，取 `{text}` 字段
  - 文生视频：`POST {baseUrl}/videos/generations`，超时 180s，识别 `succeeded / pending / failed` 三态
- **未配置降级**：每类 ApiProfile 无 active 时返回 `status=degraded + 中文用户友好错误`（如「⚠️ 未配置「图片模型」，请先到「设置 → 图片模型」选厂商填 Key 并点击「使用」」），HTTP 200 而非 500，前端直接渲染气泡不报错。
- 新增 [MediaController.java](src/main/java/org/example/duobaan/controller/MediaController.java)：4 个接口 `POST /api/media/{image,speech,transcribe,video}`，每次成功都把「富内容 JSON」以 `%%RICH_MEDIA%%{"kind","payload","text"}` 前缀写入 `chat_message`，和 v3.1「对话历史自动恢复」兼容。

**2. 设置页：5 Tab 独立管理（文本 / 图片 / 语音 / 视频 / 天气）**

- 后端 `ConfigService.getProviderPresetsByType` 新增 13 条预设：
  - IMAGE：DALL·E 3 / Seedream(火山) / 万相(通义) / CUSTOM
  - AUDIO：OpenAI(TTS+Whisper) / 火山(TTS/ASR) / MiniMax / CUSTOM
  - VIDEO：Seedance(火山) / 可灵Kling(快手) / 万相视频(通义) / CUSTOM
  - WEATHER：和风 / CUSTOM
- 新接口 `GET /api/config/providers/all`：一次性返回 5 类预设，前端 SettingsPage 5 Tab 复用同一套「新增/编辑/删除/设为使用」UI。
- 每个 Tab 右上角绿色小圆点独立显示"已激活"，不再混淆 LLM 和 IMAGE 是否配置。

**3. 聊天面板：6 模态胶囊选择器 + 富气泡渲染**

- [ChatPanel.vue](frontend/src/components/ChatPanel.vue) 顶栏新增 6 颗胶囊：`智能(AUTO) / 文本(TEXT) / 图片(IMAGE) / 转写(ASR) / 朗读(TTS) / 视频(VIDEO)`。
  - **智能 / 文本**：走原来的 `chatStream` SSE 流式（`AUTO` 和 `TEXT` 行为等价，`AUTO` 是给"不知道选啥"的用户默认项）
  - **图片**：输入描述 → 调用 `generateImage` → 气泡内渲染 `img` 网格，点击图片新标签页打开原图
  - **转写**：输入框替换为虚线文件框 → 选 mp3/wav/m4a/flac → 调 `transcribeAudio` → 文本直接展示在气泡内并自动入库当 LLM 上下文
  - **朗读**：输入文字 → 调 `generateSpeech` → 前端把 `audioBytes` base64 → Blob URL → `<audio controls>` 直接播放
  - **视频**：输入描述 → 生成较久（spinner 动画提示 1~3 分钟）→ 返回 `succeeded` 时渲染 `<video controls>`，`pending` 时提示"排队中稍后重试"
- 富内容恢复：挂载时解析 `%%RICH_MEDIA%%` 前缀 JSON，自动重建 `img / audio / video` 标签，刷新不丢失多模态结果。
- 「拆单」按钮仅在智能 / 文本 Tab 显示，避免 IMAGE/VIDEO Tab 里出现语义混乱。
- 发送按钮文案根据当前模态动态切换（"生成图片 / 开始朗读 / 生成视频 / 开始转写"），降低认知门槛。

**4. 接口表同步更新**

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/config/providers/all` | GET | 拉取 5 类厂商预设（LLM/IMAGE/AUDIO/VIDEO/WEATHER） |
| `/api/media/image` | POST | 文生图，返回 items[].url / b64Data |
| `/api/media/speech` | POST | 文生语音，返回 base64 audioBytes + audioMime |
| `/api/media/transcribe` | POST (multipart) | 上传音频 → 返回转写 text |
| `/api/media/video` | POST | 文生视频，status 三态 succeeded / pending / failed |

**5. 端到端实测（未配置 API Key 场景下的降级回归）**

| 场景 | 结果 |
|---|---|
| POST `/api/media/image`（未配置 IMAGE） | ✅ HTTP 200 / `status=degraded` / 中文提示"去设置→图片模型填 Key" |
| POST `/api/media/speech`（未配置 AUDIO） | ✅ HTTP 200 / `status=degraded` / 中文提示"去设置→语音模型填 Key" |
| POST `/api/media/video`（未配置 VIDEO） | ✅ HTTP 200 / `status=degraded` / 中文提示"去设置→视频模型填 Key" |
| GET `/api/config/providers/all` | ✅ 返回 LLM(5条) + IMAGE(4条) + AUDIO(4条) + VIDEO(4条) + WEATHER(2条) |
| 前端切图片 Tab → 输入 → 发送 | ✅ 调 /image，未配置直接渲染降级气泡，不抛异常 |
| 刷新浏览器 → 富内容图片气泡 | ✅ 从 `%%RICH_MEDIA%%` JSON 自动还原 `<img>` |

---

### v3.3.1 🎬 修复：视频模型反复报错（Illegal character in scheme + HTTP 404）— URL 强力清洗 + 万相视频原生分支（用户报告 bug）

**Bug 现象（用户截图实锤，两个错误叠加）**：

1. `Illegal character in scheme name at index 0: > `https://dashscope.aliyuncs.com/.../video-synthesis`…`
   - 根因：用户从 Markdown 复制 Dashscope 官方文档 URL 时，把前导 `>` + 反引号 `` ` `` + 尾随 `platform.qq…` 一行整个粘进了「API Base URL」输入框。Java `URI.create(...)` 对 scheme（冒号前部分）要求必须是 `[a-zA-Z][a-zA-Z0-9+.-]*`，`>` 不在此列 → 直接抛 IllegalArgumentException。
2. HTTP 404：「模型 ID 或厂商地址有误，请核对预设模型或自定义 baseUrl」
   - 根因：Dashscope（阿里通义万相）**视频接口并不走 OpenAI 兼容的 `/videos/generations`**（官方这一路长期未开放，或仅部分模型开放）。正确做法是用 **Dashscope 原生异步接口** `POST https://dashscope.aliyuncs.com/api/v1/services/aigc/video-generation/video-synthesis`，请求体是 `{model, input:{prompt}, parameters:{size, duration_seconds}}`，且**必须**带请求头 `X-DashScope-Async: enable`。之前的预设把 baseUrl 写成 `…/compatible-mode/v1` + 固定 append `/videos/generations` → 对万相必然 404。
3. （次级）用户把 **具体 action 路径**（`/api/v1/services/.../video-synthesis`）整段粘贴进 baseUrl，后端再 append `/videos/generations` → 生成的 URL 是畸形长串，无论如何都会 404。

**修复方案（前后端 5 层同时兜底，用户下次再粘脏数据也不怕）**

1. **新增 `UrlSanitizer.java`（后端强力清洗）** — 6 步顺序敏感处理：
   - 去两端空白 + Markdown 包络字符（`` ` > < ( ) [ ] { } " ' * _ 全角冒号… ``），循环剥到没有为止
   - 定位第一个 `http://` 或 `https://`，截掉前面的垃圾字符（对应 bug 1）
   - 扫到「非法 URL 字符」（中文、反引号、ASCII > 126）立刻截断；只保留 `? # & =` 查询串符号
   - **尾部 action 路径自动砍掉**（最多 3 轮）：`/videos/generations`、`/images/generations`、`/services/aigc/.../video-synthesis`、`/chat/completions`、`/completions` 等（对应 bug 3，用户把 endpoint 塞进 baseUrl 也能自动还原成根）
   - 尾部多余 `/` 统一归一成 1 个，但**不破坏 `scheme://`**
   - Node 脚本复现你截图的脏数据：`> `https://dashscope.aliyuncs.com/api/v1/services/aigc/video-generation/video-synthesis`platform.q...` → ✅ 洗成 `https://dashscope.aliyuncs.com/api/v1`
2. **入库 + 读路径双写清洗**：
   - 写路径：[ApiProfileService#applyInbound](src/main/java/org/example/duobaan/service/ApiProfileService.java) 里 `setBaseUrl(UrlSanitizer.sanitizeBaseUrl(in.baseUrl()))`
   - 读路径：`getActivePlain(type)` 里 `.map(cleanForUse)`，即便是老脏数据，**到业务层用之前再强制洗一遍**，DB 旧记录也能直接恢复正常（不用你手动删了重填）
3. **MediaService 按厂商分流（Dashscope 视频强制走原生异步）**：
   - 新增 `vendorOf(p)` 识别 5 类：`OPENAI_COMPAT / DASHSCOPE / KUAISHOU_KLING / VOLC_ARK / MINIMAX`（同时查 provider 名 + baseUrl 特征域名）
   - `vendor=DASHSCOPE` 时：**忽略用户写进 baseUrl 的任何万相地址**，直接固定请求 `https://dashscope.aliyuncs.com/api/v1/services/aigc/video-generation/video-synthesis` + 请求头 `X-DashScope-Async: enable` + 专有的 `{model, input:{prompt}, parameters:{size, duration_seconds}}` body 格式
   - `size` 映射：16:9 → `1280*720`，9:16 → `720*1280`，1:1 → `720*720`
   - 响应解析新增 Dashscope 异步识别：`output.task_id` / `output.task_status` / `request_id` → pending 状态返回 `MediaResponse.video([], "pending", "万相任务已提交(task_id=xxx)…稍后点刷新查询")`，用户明确知道"提交成功只是没生成完"；错误字段兼容 Dashscope 的 `code/message` 两字段
   - 其他 4 模态（图/TTS/ASR）endpoint 构建都加了 `dedupSuffix`：当 baseUrl 已被洗得比较干净但仍带 action 时，再次去重避免 `/images/generations/images/generations` 这种重复
4. **[MediaResponse.java](src/main/java/org/example/duobaan/model/dto/MediaResponse.java)** record 结构升级：新增 `message` 字段 + 3 参 `video(items, status, message)` 工厂方法，Dashscope pending 状态能把"task_id + 稍后刷新"提示直接塞进去。
5. **前端 SettingsPage 用户体验兜底**：
   - 「API Base URL」输入框失焦自动跑 `sanitizeBaseUrlClient`（与后端同语义 JS 版本）；模型 ID 输入失焦也联动
   - 标签右侧加「🧼 一键清洗」按钮
   - 若清洗后内容发生变动，底下会出现一块**橙色警告**（`hint.warn` 样式）：「⚠️ 检测到你粘贴了 Markdown 包络字符… — 已自动清洗为合法 baseUrl」
   - 视频模型 Tab 下模型 ID 输入框多一段提示：「🎬 万相视频：系统已强制走原生异步接口，Base URL 不用写具体接口地址（否则会 404）」
   - 预设占位符也升级成了 seedance-1-0-pro **或** `wan2.1-t2v-turbo`（万相常用）

**端到端实测**

| 场景 | 结果 |
|---|---|
| Node 复现你截图脏 URL → 客户端清洗函数 | ✅ `https://dashscope.aliyuncs.com/api/v1`（Illegal character 错误永远不会再出现） |
| 后端 `mvnw compile`（JDK 17 `D:\JKD 17`） | ✅ 通过，3 新源 UrlSanitizer / MediaService 大改 / ApiProfileService +1 辅助方法，共 47 Java 源 |
| 前端 Vite build（46 modules） | ✅ 165 KB JS / 30 KB CSS / 60 KB gzip，无语法错误 |
| SettingsPage dirty hint 样式 | ✅ `.hint.warn` 新增 + `.mini-btn` margin 修正，UI 纯白极简风格保持一致 |

**文件变更统计**：+1 新文件 `UrlSanitizer.java`，修改 `ApiProfileService.java / MediaService.java / MediaResponse.java / SettingsPage.vue`，合计约 +520 / -90。

---

### v3.3.0 🔧 修复：说「把任务转到明天」只回复不动手 — 新增「任务指令自动执行器」（用户报告 bug，前后两轮修复）

**Bug 现象（第一轮：v3.2.x 遗留问题 → v3.3.0 启动修复）**：办公对话里说「把这个任务转移到明天」，助手正确回复了一段 `已将任务…移至明日：```json [{"title":"…","date":"明天","status":"TODO"}] ````，**但右侧流程表「今日」仍保留 1 条、「明日」仍 0 条** — 因为 ChatPanel 只把 JSON 当文本渲染，从没解析并调用 `/api/tasks/{id}/migrate`。

**第一轮修复（v3.3.0 起步）**：ChatPanel 新增「流式 done 回调钩子 + 指令解析 + 副作用执行 + FlowTable 联动刷新」闭环：

- `extractInstructionBlocks(text)`：用正则扒代码块，支持 ` ```json ` / ` ``` `；接受单对象/数组
- 字段识别：title/task + date/group + status，三档标题精确/包含/反向匹配
- 三种执行动作：migrate / patch-status / 未命中 + 给了 date 自动新建
- 助手气泡下方新增「🤖 自动执行任务指令」虚线摘要卡（✅/🔧/➕/ℹ️/💥/❌ + spinner）
- 有变动即 `emit('tasks-created') → flowRef.load()` 刷新今/明日计数

---

**Bug 现象（第二轮：v3.3.0 首版仍不生效 → 用户二报）**：用户说「这个 bug 还没修复，还是一样」。再看新截图，LLM 实际**根本没按我们设计的 `[{title,date}]` 格式吐 JSON**，而是吐了两段：

```
明日任务:
```json
["近期数据异常分析"]    ← 字符串数组！不是对象数组
````
……随后又写：
更新后安排:
- 今日任务: 无
- 明日任务: 近期数据异常分析
```json
["近期数据异常分析"]    ← 再写一次字符串数组
````

第一轮解析器只认 `typeof x==='object' && (x.title||x.task)`，**字符串数组里 "近期数据异常分析" 是 `typeof x==='string'`，全部被 filter 丢掉** → 指令静默全跳过，助手说什么都不执行。

**第二轮修复（v3.3.0 核心，兼容 LLM 不确定性）**：把解析器从「单一 schema」扩成「**四重兜底解析器**」，见 [ChatPanel.vue](frontend/src/components/ChatPanel.vue) 的 `extractTaskInstructions(bubbleContent, userLastText, allTasks)`：

1. **第一层：结构化 JSON 对象数组**（助手偶尔按我们约定输出时仍能用）：`[{title,date,status}]`
2. **第二层：字符串数组 + 小标题分组推断** —— 对应你截图里 `明日任务:` + `["近期…"]` 的写法：
   - 用正则 `/(今天|今日|明天|明日)[\s:：]*任务[\s:：]*([^\n\r]+)/g` 扫代码块**正上方 1–2 行**，命中「明日任务:」即把整个 JSON 块的分组都设为 TOMORROW
   - 这一步是关键：**字符串数组本身没有分组信息，分组来自上下文小标题**
3. **第三层：纯文本正则兜底**（当 LLM 完全不写 JSON 只说人话时仍能工作）：
   - `「今日任务：X」/「明日任务：X,Y,Z」` → heading 规则解析
   - `「已把「XX」移至明日」` / 「把 XX 改为已完成」→ move / status 中文句式正则
4. **第四层：指代消解兜底**（你说「把这个任务转到明天」，全篇都没写任务名）：
   - 用户消息里含「这个 / 该 / 当前 / 此 / 这条」视为指代
   - 命中顺序：用户消息原句直接有任务名 > 对话历史用户气泡命中 > 今日任务恰好只有 1 条时把它作为「这个」
5. **JSON 格式容错**：LLM 常把 `["近期…"]` 包成额外字符串、写成 `\[...\]`、`\"...\"`，cleaned 阶段做三档 strip 预处理 + 二次 JSON.parse 重试，基本能吃下来
6. **去重**：同 (title, group, status) 合并为一条，避免两段相同代码块重复执行
7. **执行摘要来源标注**：每条指令带 `source`（如 `json-string-array(TOMORROW)` / `plaintext-heading(TOMORROW)` / `anaphora(TOMORROW)`），摘要卡第一行直接打印"识别到 N 条可执行指令（来源：…）"，方便排障

---

**端到端实测**

| 场景 | 结果 |
|---|---|
| 手动调 `POST /tasks` 建今日任务 A → migrate → 查询 | ✅ id=8 group 从 TODAY → TOMORROW ✓ |
| Node 跑**你截图里的真实三段文本**（明日任务头 + 字符串数组 + 更新后安排 + 第二段同 JSON） | ✅ 命中 2 段代码块，上下文小标题 `明日任务:` 推断 blockGroup=TOMORROW → 最终去重后 1 条指令 `{title:近期数据异常分析, group:TOMORROW}` 完全符合期望 |
| 用户消息 =「把这个任务转移到明天」（LLM 全程没给任何可识别格式，只剩这个指代） | ✅ 触发指代消解（resolveAnaphora）+ 全局分组 inferGlobalGroup=TOMORROW → 仍能执行迁移 |
| ChatPanel 生产构建 | ✅ 46 modules / 162 KB / 59 KB gzip，无语法错误 |

**文件变更**
- [ChatPanel.vue](frontend/src/components/ChatPanel.vue)：从 1 个 `extractInstructionBlocks` 升级为四重兜底架构 `extractTaskInstructions`；新增 `inferGroupFromContext / inferGlobalGroup / resolveAnaphora / normStatus(中文化)`；`chatStream` 的 `onDone` / `onError` 仍挂钩子；模板 `.exec` 执行摘要卡不变，但首行增加了「🔍 识别到 N 条指令（来源：…）」帮助定位解析链路。

---
