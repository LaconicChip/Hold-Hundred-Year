# 你持有一百年看看

一款单机 A 股模拟炒股游戏：从 10 万本金开始，经历 11 个历史纪元、72 个交易日（快速模式 36 天），在涨停、跌停、停牌、除权、融资与高利贷之间活下来。

游戏没有账号和登录。入口就是一张**存档架**：新建一份存档即开一局，旧存档不会被覆盖，也可以逐份删除。局域网里的朋友打开同一个地址，会看到同一张存档架。

## 功能

- **单机多存档**：每份存档一个独立世界，独立进度、行情、事件与主力剧本。
- **两种节奏，一局一选**：标准模式 72 个交易日约 2 小时；快速模式 36 个交易日约 30 分钟。每天都是 90 跳，11 个纪元与全部玩法机制不减，快速模式只压缩时间轴、放大单日振幅。
- **24 只虚构股票 + 3 只 ETF**：股票覆盖白酒、半导体、新能源、医药、银行、券商、军工、传媒等 21 个板块方向，名字与代码全部虚构；另有 3 只跟指数的 ETF（百年大盘 / 科创百年 / 新能源），给"不想选股就想买大盘"的一条退路。
- **有主线的行情**：市场因子、板块因子、个股 β 三层驱动，叠加 GARCH-lite 波动聚集、肥尾跳跃、日内 U 型与涨跌停磁吸；每个纪元有明确的市场基调与领涨/领跌板块。
- **历史名场面**：千股涨停、千股跌停、救市、黑 7 月崩盘、妖股三幕等剧本日保证发生，具体时点每局不同。
- **完整交易规则**：T+1、涨停只可卖不可买、ST 5%/10% 涨跌停、佣金与印花税、停牌核查、除权除息、打新。
- **杠杆与风险**：融资年化 8%，担保比例低于 140% 追保、低于 120% 强平；另有高利贷，额度为净资产 5 倍，到期强制卖出还债。
- **实时推送与离线暂停**：WebSocket 推送行情、消息、通知与股吧弹幕；没有客户端在线时，世界不推进。
- **双结局**：资不抵债进入爆仓结局；坚持到收官日获得通关成绩单。

## 技术栈

- 后端：Spring Boot 4.1.1、Java 17、MyBatis-Plus、MySQL 8、Spring WebSocket（STOMP）、Spring Security + JWT
- 前端：Vue 3、Vite、Pinia、Vue Router、Element Plus（按需引入）、ECharts、@stomp/stompjs
- 便携版：jpackage app-image、内嵌 H2（MySQL 兼容模式）、Chrome for Testing
- 不使用 Redis 与消息队列；状态以内存为权威，MySQL 做 Checkpoint

## 快速开始

前置：PowerShell 7、JDK 17、Node.js 20+、MySQL 8。

```powershell
# 1. 建库并建表（开发库与测试库都要）
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS hold_year DEFAULT CHARACTER SET utf8mb4; CREATE DATABASE IF NOT EXISTS hold_year_test DEFAULT CHARACTER SET utf8mb4;"
mysql -uroot -p hold_year      -e "source src/main/resources/db/schema.sql"
mysql -uroot -p hold_year_test -e "source src/main/resources/db/schema.sql"

# 2. 后端（8080）
.\mvnw.cmd spring-boot:run

# 3. 前端（5173，/api 与 /ws 代理到 8080）
cd frontend
npm ci
npm run dev
```

打开 `http://localhost:5173` 或打包后的 `http://localhost:8080`，点「新建存档」即可开局。

默认数据库账号写在 `src/main/resources/application.properties`（本机开发默认 `root` / `123456`），可以用环境变量覆盖：

```powershell
$env:HOLD_DB_URL = 'jdbc:mysql://localhost:3306/hold_year?...'
$env:HOLD_DB_USER = 'root'
$env:HOLD_DB_PASSWORD = '你的密码'
```

## 存档与局域网

- 存档架的接口是 `/api/saves`：列出、新建、进入、删除。`sys_user` 一行就是一份存档，没有账号与密码。
- 进入某份存档时签发 JWT；令牌只是这份存档的会话票据，默认有效期一年。
- 删除存档会连同该存档的持仓、成交、消息、日 K、快照等全部数据一起清掉，不可恢复。
- 标准模式使用本机 MySQL；便携版使用内嵌 H2，存档是程序目录下的 `data\hold_year.mv.db` 一个文件。

局域网共享：

```powershell
.\game.cmd          # 启动
.\game.cmd lan      # 打印局域网地址
```

朋友在同一个局域网里打开 `http://192.168.x.x:8080` 就能进入同一张存档架；存档架页会自动列出这台电脑的内网地址，点一下即可复制，手机上直接输入就能玩，不用自己猜 IP。存档架没有密码，谁都能进入或删除任意存档，这是熟人环境的设计取舍。朋友连不上时，先确认你在同一网络，再检查 Windows 防火墙是否放行了 8080 端口。

## 便携版

GitHub Releases 提供自带 Java 与浏览器的便携包：

```text
https://github.com/LaconicChip/Hold-Hundred-Year/releases
```

使用方式：解压 → 双击 `hold-hundred-year.exe` → 在独立游戏窗口中游玩。关掉窗口即退出；`停止游戏.bat` 只是窗口关不掉时的兜底。便携包约 281 MB，不需要安装 Java、Node、MySQL 或浏览器。

便携版的存档全部在 `data\hold_year.mv.db`：复制这个文件等于备份全部存档，删除它等于清空全部存档。想自己打包：

```powershell
.\pack-portable.cmd
```

产物在 `dist\hold-hundred-year-portable-<版本号>.zip`，脚本会启动包内 exe 做一次冒烟测试，失败则不产出压缩包。

## 配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `HOLD_DB_URL` | 本机 `hold_year` | 数据库连接串 |
| `HOLD_DB_USER` / `HOLD_DB_PASSWORD` | `root` / `123456` | 数据库账号 |
| `HOLD_PORT` | `8080` | 服务端口 |
| `HOLD_JWT_SECRET` | 开发默认值 | JWT 签名密钥；公开部署务必替换 |
| `HOLD_JWT_TTL_HOURS` | `8760` | 存档会话令牌有效期 |
| `HOLD_H2_PATH` | `./data/hold_year` | 便携版 H2 存档路径 |
| `HOLD_LOG_FILE` | `./data/hold-year.log` | 便携版日志路径 |
| `HOLD_BROWSER_PATH` | 空 | 便携版指定浏览器路径 |
| `HOLD_QUIT_WITH_WINDOW` | `true` | 便携版关窗是否退出服务 |

## 目录结构

```text
src/main/java/com/holdhundredyear/
  world/     世界种子、纪元与剧本日排期、存档服务
  market/    市场状态机、逐跳推进、结算、快进、查询、行情板与世界锁
    engine/  波动引擎、封板状态机、价格冲击队列、指数计算
    quote/   内存行情板与分时缓冲
  trade/     交易、融资、高利贷、打新、除权除息
  event/     事件工厂、消息、股吧弹幕、NPC、主力剧本
  ws/        WebSocket 四通道推送
  web/       REST 控制器
  game/      常量、游戏文案入口、DTO 与枚举
  config/    安全、WebSocket、静态资源、便携版数据源与窗口
  common/    统一响应、异常、JWT、H2 方言翻译

src/main/resources/
  db/schema.sql              表结构的唯一权威；改表就重建库，不维护增量迁移脚本
  content/*.txt              全部游戏文案（弹幕、个股人设、板块与大盘消息、NPC 台词、事件、纪元叙事）

frontend/src/
  views/        存档架、大盘、个股、资产、消息、排行榜、收盘总结、终局
  components/   布局、行情表、分时与日 K、弹幕层、模式选择器等
  api/          REST 与 WebSocket 接口层
  stores/       Pinia 状态
  styles/       设计令牌与主题
  assets/       图标 sprite 与吉祥物、NPC 头像

scripts/        便携包说明文件生成脚本
```

## 测试与构建

```powershell
# 后端全量测试（需要 hold_year_test 库）
.\mvnw.cmd test

# 前端生产构建
cd frontend
npm run build
```

当前基线：后端 470 个测试全部通过；前端生产构建通过。

## 常见问题

- `mvnw.cmd` 在部分机器上报 `Cannot index into a null array`：改用 Maven Wrapper 已下载的 Maven，先设置 `JAVA_HOME` 指向 JDK 17，再执行 `& "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-<版本>\*\apache-maven-<版本>\bin\mvn.cmd" test`。
- 测试报 `Unknown database 'hold_year_test'`：按快速开始第一步建库、执行 `schema.sql`。
- 前端页面打不开或按钮无反应：先确认后端 8080 已启动，再按 `Ctrl+F5` 硬刷新。
- 改了表结构：直接按 `schema.sql` 重建 `hold_year` 与 `hold_year_test`，本项目不提供增量迁移脚本。

## 授权与声明

- 代码以 MIT 协议开源，见 [LICENSE](LICENSE)。
- 界面中的吉祥物与 NPC 头像由 AI 图像生成工具产出，仅用于本项目。
- 标题字体站酷快乐体随包内置（`frontend/src/assets/fonts/`，附 OFL 授权），正文使用系统字体，不依赖任何 CDN。
- 24 只股票的名称、代码、发行价与人设全部虚构，只玩梗，不使用真实股票代码。
- 本游戏为模拟游戏，行情、事件与消息均由程序生成，不构成任何投资建议。
