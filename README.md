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
| 多套 API 配置 | 大模型 & 天气支持保存多套 Key，切换即用，删除即物理删除防泄露 |
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
| POST | `/api/llm/chat/stream` | SSE 流式对话（delta/done/error 事件） |
| POST | `/api/llm/parse-tasks` | 自然语言拆单为任务列表 |

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
