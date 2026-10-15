# 你持有一百年看看

A 股模拟炒股游戏：一局 72 个交易日（3 年 × 24 天），约 1 分 40 秒一个交易日、**整局约 2 小时**，11 个历史纪元剧本；
开每一局时可选**快速模式**（36 个交易日、约 30 分钟、涨跌停 ±20%）——模式一局一选，注册不绑定，
世界种子可复现，支持保证金交易与强平，另有**高利贷**（净资产 5 倍额度、日息按账龄递增、到期强制平仓还债，借满就是 6 倍仓位），双结局（爆仓天台 / 通关成绩单）。单机多用户。

- 后端：Spring Boot 4.1.1 / Java 17 / MySQL / MyBatis-Plus / WebSocket(STOMP)，**不使用 Redis 与 MQ**
- 前端：Vue 3 + Vite + Element Plus + ECharts + Pinia
- 视觉：散户营业部·漫画贴纸风（米纸底 + 墨色描边 + 硬阴影）、红涨绿跌、**全站禁 emoji**
- 版本：**v0.0.1**（`pom.xml`、`frontend/package.json` 与 git tag 三处同号）

## 目录结构

```text
src/main/java/com/holdhundredyear/
  world/     世界种子与排期（WorldRandom 是唯一随机入口、WorldSeeder、EraScheduler、ScriptDayScheduler）
  market/    行情状态机与推进（MarketStateService 开盘管线、WorldTickService 单跳、SettlementService 结算、
             FastForwardService 快进、MarketQueryService 查询聚合、QuoteBoardRegistry 世界锁）
    engine/  引擎四件套（VolatilityEngine、SealMachine、ShockQueue、IndexCalculator）
    quote/   内存行情板（Quote 分时缓冲 90 点、QuoteBoard）
  trade/     交易与特色机制（TradeService、MarginService、SharkService 高利贷、IpoService、
             CorporateActionService、MeQueryService）
  event/     事件与内容（EventFactory、MessageService、DanmuService、NpcService、MainScriptService、
             HaltService、ProgressService）
  ws/        实时推送四通道 /user/queue/{quotes,messages,notifications,danmu}
  web/       REST 控制器（Auth/Stock/Trade/Loan/Ipo/Market/Message/Me/Game）
  game/      constants（引擎标定 GameConstants、静态档案 StockDefs/EraDefs/NpcDefs/Titles、事件骨架结构 EventSkeletons）
             content（全部游戏文案的读取入口：ContentCatalog + DanmuTexts/StockPersonas/SectorFlavor/
             NpcLines/EventCopy/EraNarratives/SharkLines 七个薄壳）
             model/dto（接口出参）、model/enums（状态机、纪元、一局模式 GameMode 等枚举）
  market/entity/ + market/mapper/  MyBatis-Plus 实体与 Mapper（16 张表）
  config/ + common/  安全与 JWT、WebSocket、统一响应 R / PageResult / BizException

src/main/resources/db/
  schema.sql                        表结构的唯一权威：新库执行它；改表就重建库（本项目不维护增量迁移脚本）

src/main/resources/content/
  danmu.txt / stock-personas.txt / sector-flavor.txt / npc-lines.txt / event-copy.txt /
  era-narratives.txt / shark-lines.txt
  全部游戏文案（弹幕、24 股人设、板块与大盘消息、NPC 台词、92 条事件骨架的标题正文与追报、11 条纪元叙事、高利贷台词）
  —— 改文案只改这七个 txt，不动 Java；格式与校验见 docs/implementation-notes.md §7

frontend/src/
  views/        8 个页面（Login/Market/StockDetail/Assets/Messages/Leaderboard/DailySummary/GameResult）
  components/   19 个组件（GameLayout 竖向菜单、StockChart 分时、KlineChart 日K、QuoteTable 等）
  api/          axios 接口层 + ws.js（STOMP 四通道，握手带 JWT）
  stores/       Pinia（user/market/quotes/messages）
  utils/        通用格式化（涨跌配色与金额/百分比展示，跨页面共用）
  constants/    messageMeta.js（消息来源/三态/呈现通道的展示元数据）——股票档案与节奏都不在前端镜像
  styles/       tokens.css（全部设计令牌）+ theme.js（ECharts 令牌镜像，唯一允许出现色值的文件）
  assets/       icons.svg（贴纸风图标 sprite）+ img/*.png（吉祥物与 NPC 头像）

docs/
  spec.md                  设计规格（最高权威：架构/数值/API/视觉）
  implementation-notes.md  实现备忘（冻结决策、机制速查、运维手册、验收基线）
  design/                  style-tile.html 视觉基准、mascot-*.jpg 吉祥物定稿、shots/ 验收截图
```

## 快速开始

前置：PowerShell 7、JDK 17、Node.js、MySQL 8（开发库 `hold_year`、测试库 `hold_year_test`，账号见
`src/main/resources/application.properties`）。

```powershell
# 1. 首次或换机：建库并建表（两个库都要）
mysql -uroot -p123456 -e "CREATE DATABASE IF NOT EXISTS hold_year DEFAULT CHARACTER SET utf8mb4; CREATE DATABASE IF NOT EXISTS hold_year_test DEFAULT CHARACTER SET utf8mb4;"
mysql -uroot -p123456 hold_year      -e "source 仓库绝对路径/src/main/resources/db/schema.sql"
mysql -uroot -p123456 hold_year_test -e "source 仓库绝对路径/src/main/resources/db/schema.sql"

# 2. 后端（8080）
.\mvnw.cmd spring-boot:run

# 3. 前端（5173，/api 与 /ws 代理到 8080）
cd frontend; npm install; npm run dev

# 4. 全量后端测试（需 MySQL 的 hold_year_test 库）
.\mvnw.cmd test

# 5. 前端生产构建
cd frontend; npm run build
```

账号无需预置，页面注册即用。

## 给朋友玩（没有云服务器）

**结论：这游戏就跑在你现在这台机器上，不需要服务器。** 打包成一个 jar，前端页面也塞在同一个
jar 里，双击 `game.cmd` 就起来；朋友用浏览器访问你电脑的局域网地址即可，他们什么都不用装。
MySQL 仍然用你本机那个 `MySQL80` 服务（存档就在里面）。

### 一次性打包

```powershell
.\game.cmd build      # 前端构建 -> 拷进后端资源目录 -> 打成 target\Hold-Hundred-Year-0.0.1.jar
```

### 每次开一局之前

```powershell
.\game.cmd            # 启动；你自己开 http://localhost:8080
.\game.cmd lan        # 打印要发给朋友的地址（另开一个窗口看）
```

朋友在**同一个 WiFi/局域网**里，浏览器打开 `http://192.168.x.x:8080`（`game.cmd lan` 给的就是
它），页面里自己注册账号，各玩各的世界，互不干扰。

**你电脑关机或关掉窗口，朋友就进不来了** —— 这是没有服务器的代价，也正是"账户隔离的单机存档
游戏"的定位：每个账号一个独立世界，谁也不影响谁。

### 朋友连不上，按顺序查这三条

1. 你的游戏窗口还开着吗？你自己 `http://localhost:8080` 能打开吗。
2. 你们在同一个网络吗？公司/校园网常做设备隔离（互相连不通），这种网络换手机热点最省事。
3. Windows 防火墙拦了入站。用**管理员身份**的命令提示符放行一次：

   ```powershell
   netsh advfirewall firewall add rule name="Hold Hundred Year" dir=in action=allow protocol=TCP localport=8080
   ```

### 想直接发个压缩包（对方电脑零依赖）

上面那种方式要求**你的电脑开着**。如果只想把游戏丢给朋友自己玩，打一个绿色包：

```powershell
.\pack-portable.cmd        # 产物 dist\hold-hundred-year-portable-0.0.1.zip（约 83 MB）
```

把 zip 发给朋友：解压 → 双击 `hold-hundred-year.exe` → 弹出一个**独立游戏窗口**（没有地址栏的
应用窗口，不碰他自己的书签和登录态）→ 点"注册"就能玩。对方不需要装 Java、Node、MySQL
（JRE 已经捆在包里）。退出：关掉游戏窗口，或者双击包里的 `停止游戏.bat`。

> 关于"关窗即退出"：默认用系统 Edge/Chrome，而 Edge 的"启动加速"会把新窗口交给它后台已有的
> 进程——我们启动的那个进程 0 秒就退出，于是关窗口只能靠玩家自己，服务还在跑（`停止游戏.bat`
> 一定能停）。要让"关窗即退出"真正成立，就带一个自带浏览器打包：
> `$env:BUNDLE_BROWSER='1'; .\pack-portable.cmd`（包体积约 +90MB，进程归我们自己管，行为最干净）。

第一次运行 Windows 可能提示"已保护你的电脑"（因为我们没买代码签名证书），点「更多信息 → 仍然要
运行」。窗口行为可用环境变量调：`HOLD_QUIT_WITH_WINDOW=false` 让关窗后服务继续跑，
`HOLD_BROWSER_PATH` 指定浏览器位置（装在非标准路径时用）。

> 便携包**不随代码改动自动更新**：只有"升版本号 + 让我打包"两件事都明确说了，才会重新生成
> `dist\` 里的包（见 AGENTS.md 落地约定）。

区别只有一处：绿色包用内嵌的 H2 文件库（存档是同一个目录下的 `data\hold_year.mv.db`，
复制它=备份，删掉它=重开一局），你自己这台机器仍然跑真 MySQL。表结构还是只有
`src/main/resources/db/schema.sql` 一份权威，H2 那边由 `MysqlSchemaForH2` 在启动时翻译，
不存在第二份建表脚本。

### 朋友不在同一个网络

没有服务器就借一条免费隧道（Cloudflare Quick Tunnel：装个 `cloudflared` 就行，不用注册、
不用公网 IP）：

```powershell
cloudflared tunnel --url http://localhost:8080
```

它会吐一个 `https://随便-几个-词.trycloudflare.com` 的地址，发给朋友就能玩，HTTPS/WSS 它顺手
做掉了。三个代价要知道：**每次重启网址会变**；拿到网址的人都能进（没有邀请码）；隧道断了页面
会卡住（本机数据不受影响）。想固定域名、长期在线，就得先有服务器——那时候再谈 Docker、证书、
进程守护、自动备份这一整套（`docs/spec.md` §15 现在把它们列为明确不做）。

### 存档与备份

存档全在 MySQL 的 `hold_year` 库里。

```powershell
$env:HOLD_DB_PASSWORD = '你的MySQL密码'                  # 只在备份/还原时用
.\game.cmd backup                                    # 导出到 backups\hold_year-日期时间.sql
.\game.cmd restore backups\hold_year-xxx.sql         # 还原（先停游戏）
```

改了表结构不用写迁移脚本：按 `src/main/resources/db/schema.sql` 重建库即可（见下面"已知环境坑"
第 3 条）。

### 给别人玩之前，两件建议顺手做的事

1. **换掉 JWT 密钥**（默认值就写在 `application.properties` 里，等于公开的）：

   ```powershell
   $env:HOLD_JWT_SECRET = '随便敲一大串，越长越好'    # 再启动游戏；换密钥会让已登录的人重新登录
   ```

2. **别把 MySQL 暴露出去**：`3306` 只监听本机就好，对外只开 8080。数据库地址/账号/密码/端口
   都能用 `HOLD_DB_URL` / `HOLD_DB_USER` / `HOLD_DB_PASSWORD` / `HOLD_PORT` 覆盖，不用改代码。

## 已知环境坑

1. **`mvnw.cmd` 在某些机器上会直接失败**（报 `Cannot index into a null array` / `Cannot start maven from wrapper`）：
   它内部固定调用 Windows PowerShell 5.1 解析 `~/.m2`。绕法＝直接用 wrapper 已下载好的 Maven：
   `& "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-<ver>\*\apache-maven-<ver>\bin\mvn.cmd" test`（需先设 `$env:JAVA_HOME` 指向 JDK 17）。
2. **测试没有 H2 兜底**：`hold_year_test` 不存在会直接大面积 `Unknown database` 报错，按上面第 1 步建库即可。
3. **表结构只有一份权威：`src/main/resources/db/schema.sql`**。本项目是单机本地游戏、没有线上库，所以不维护增量迁移脚本——改表后直接重建两个库：`DROP DATABASE hold_year; CREATE DATABASE hold_year ...` 再 `source schema.sql`（`hold_year_test` 同理）。存档可以不要，但绝不会出现"库里少一列"这种错。
4. 前端页面依赖后端 8080，**先起后端再起前端**。后端没跑时前端会统一弹「连不上营业部系统」（8 秒内只弹一次）——
   但如果标签页是后端重启前就开着的，它里面的 WS 与 store 都是旧的，按钮点了没反应，**先 Ctrl+F5 硬刷新**。
5. **在 IDEA 里改过 Java 包名（或 `git mv` 挪过源码目录）之后，必须先 Maven 面板 → Reload All Maven Projects，
   再 Build → Rebuild Project**。IDEA 的 Rebuild 会先清空输出目录、再按它自己的增量缓存编译；缓存还认旧包路径时
   就会留下一个空的 `target/classes`，此时 Run 报 `ClassNotFoundException: 主类`（不是代码问题，是 IDE 缓存）。
   实在不清爽就 File → Invalidate Caches… 勾 Clear file system cache and Local History 重启。
6. 判断服务在不在，**别用 `Get-NetTCPConnection -LocalPort 8080`**（沙箱/权限环境下会假报"无监听"），
   直接发一次 HTTP：`Invoke-WebRequest http://localhost:8080/api/game/modes`（未登录返回 401 就说明活着）。
7. **别让 IDEA 的自动构建和命令行 `mvnw` 同时写 `target/classes`**：两边都会清空/覆写输出目录，撞上就会出现
   莫名其妙的 `NoSuchBeanDefinitionException`（某个 `@Service` 的 class 恰好被删掉）或"主类找不到"。
   命令行跑测试前，要么关掉 IDEA 的 Build project automatically，要么只用一种方式构建。
   遇到这种"上一秒还全绿"的失败，先 `mvnw clean test` 重跑一次再怀疑代码。
8. **`game.cmd` 必须存成 GBK（代码页 936），不能改成 UTF-8**：cmd 读带中文的 UTF-8 批处理会按字节错位，
   表现是某行中文注释被当成命令执行（`"xxx 不是内部或外部命令"`）甚至跳行。IDEA 里改这个文件时
   右下角编码要选 GBK；README/源码仍是 UTF-8，只有这一个 `.cmd` 例外。

## 权威文档与优先级

规则冲突时：**设计规格 > 实现计划 > 其他**。

| 文档 | 路径 |
| --- | --- |
| 设计规格 | `docs/spec.md` |
| 实现备忘 | `docs/implementation-notes.md` |
| 视觉基准 | `docs/design/style-tile.html` |
| 表结构 | `src/main/resources/db/schema.sql`（16 张表；改表就重建库，无增量迁移） |
| 游戏文案 | `src/main/resources/content/`（7 个 .txt，改文案不用改代码） |
| 验收截图 | `docs/design/shots/` |

## 改动前必读的硬约束

完整清单见 `docs/implementation-notes.md`（§1 痛点结论 P1-P15、§2 硬约束、§3 机制速查）。要点：

- 引擎/结算路径禁用 `new Random()` / `Math.random()`，一律 `WorldRandom.of(seed, salt)`；同 seed 必同结果，快进与正常推进等价。
- 并发：`QuoteBoardRegistry.worldLock(userId)` 覆盖调度/快进/交易；`sys_user`、`user_position` 悲观锁；`user_stock_state.version` 乐观锁。
- 开盘管线在所有价格变动完成后**必须统一重算开盘指数**（`e6fbc19` 修复，不得回退）。
- 交易规则：T+1、涨停只可卖不可买、涨跌停按本局模式（标准 ±10%/ST ±5%，快速 ±20%/ST ±10%）、佣金万 3 最低 5 元、印花税千 1（卖出）、追保 140% / 强平 120%、年化 8%；高利贷不进担保比例但进净资产与爆仓判定。
- 视觉：色值只走 `tokens.css` 令牌（仅 `styles/theme.js` 例外）、全站禁 emoji、图标走 SVG sprite。
- 任何触及开盘管线/结算/引擎的改动，必跑 `VolatilityEngineTest`、`FastForwardServiceTest`、`TradeConcurrencyTest`、`WorldTickServiceTest`。
