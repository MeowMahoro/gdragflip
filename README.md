# Golden Dragon Flip

Hypixel SkyBlock 客户端 Fabric mod：分析拍卖行里 **100 级以上的 Golden Dragon**，
用**累计经验**（等级 + 溢出进度）作为定价坐标，找出被低估、适合买下养到 200 级再卖的挂单。

数据源：`sky.coflnet.com`（Cofl SkyApi，免 Key）。

## 命令

| 命令 | 说明 |
|---|---|
| `/gdrag [条数]` | 扫描并显示前 N 条（默认 10，最多 50） |
| `/gdrag calc <等级> <价格>` | 评估假设挂单，如 `/gdrag calc 125 660m` |
| `/gdrag refresh` | 跳过 15 秒缓存强制重扫 |
| `/gdrag auto` | 查看自动扫描状态 |
| `/gdrag auto on\|off` | 开关自动扫描 |
| `/gdrag auto interval <秒>` | 设置扫描间隔（默认 60，最低 5；低于 20 秒需 `confirm` 二次确认） |
| `/gdrag cache clear\|info` | 清空 / 查看经验缓存 |
| `/gdrag reload` | 重新读取 `config/gdragflip.json` |
| `/gdrag help` | 命令帮助 |

`/gdrag 125 660m` 这种旧写法仍然可用（≤50 视为条数，否则视为等级）。

价格支持 `660m`、`1.2b`、`660,000,000` 等写法。

输出示例：

```
[金龙] 扫描 81 条挂单，其中 68 条 Lvl100+（Lvl200 33 条）
[金龙] 基准：A Lvl100 +0% 668.9M（exp 25.4M） ｜ B Lvl200 900.0M ｜ 每 1M XP ≈ 1.22M
[金龙] 低于理论价 9 条（按利润降序）：
 #1  Lvl 123 +47%  705.0M  理论 740.6M  +35.6M (+5.1%)  [拍卖] [Cofl]
```

- `Lvl 123 +47%`：等级 + 当前等级内的经验进度（溢出经验已经计入定价）。
- 悬停可看累计经验、本级溢出、理论价、卖家、剩余时间、pet candy、UUID。
- `[拍卖]` 点击执行 `/viewauction <uuid>`；`[Cofl]` 打开对应网页。

## 算法

1. 拉取全部在挂 `PET_GOLDEN_DRAGON`，只保留 **LEGENDARY + BIN + Lvl ≥ 100**。
2. 每条挂单查到真实累计经验 `exp`（详情接口的 `flatNbt.exp`）。
3. **A** = 所有挂单中价格最低的一条（记录其经验 `expA`）。
4. **B** = 200 级中价格最低的一条，**按"刚好 200 级"处理**：经验取满级 214,023,230，
   200 级之后的溢出经验不计入收益。
5. 每经验单价 `pricePerExp = (B价 − A价) / (214,023,230 − expA)`。
6. 任意挂单理论价 `expected = A价 + (该单经验 − expA) × pricePerExp`；
   200 级以上的经验在计算时封顶。
7. 利润 `profit = expected − 价格`，按利润降序排名；没有正利润时展示最接近的 3 条。
8. `/gdrag calc <等级> <价格>` 按该等级**起点经验**计算（不提供溢出经验输入）。

Lvl 1~99 是 `Golden Dragon Egg`（未破壳、不能装备宠物物品），因此只统计 100 级以上。

### 经验曲线

`src/main/resources/gdragflip/pet_xp.csv`（1~200 级，来源 Hypixel SkyBlock Wiki `Golden_Dragon_Pet` 页）：

- 1~100 级：传奇宠物标准曲线，累计 25,353,230 XP
- 101~200 级：每级固定 1,886,700 XP，满级累计 214,023,230 XP

等级只是展示用的换算结果，内部一律用累计经验。

## 数据源与限流

- 列表：`GET /api/auctions/active/filter?tags=PET_GOLDEN_DRAGON&pageSize=100&page=N`
  （不用 `/tag/{tag}/active/bin`，它只返回最低 10 条，且低价基本都是 1~9 级的蛋）。
- 经验：列表接口**不返回 NBT**，必须逐条 `GET /api/auction/{uuid}` 取 `flatNbt.exp`
  （同时拿到 `candyUsed`）。
- **经验永久缓存**：`config/gdragflip-exp-cache.json`（UUID → 经验/糖果）。同一条拍卖的经验不会变化，
  所以只有新上架的挂单需要查详情；缓存建立后每次扫描通常只需刷新列表。
- 首次扫描要对全部 100+ 挂单各发一次详情请求（当前市场约 68 条），受额度限制约 **25~40 秒**。
- Cofl 免费额度：**30 请求/10 秒、100 请求/分钟**；本地限速留了余量（28/10s、92/60s）。
  官方说明累计 500 次 429 会封 IP，**不要**把间隔调得过低。
- **Cloudflare 兼容**：Cloudflare 会按 TLS 指纹拦截 Java HTTP 客户端（HTTP/1.1、HTTP/2、
  HttpsURLConnection 实测均返回 403），因此 Java 请求失败后会自动改用系统 `curl`
  （Windows 自带 `C:\Windows\System32\curl.exe`，macOS/Linux 为 `curl`），并在本次游戏会话内一直使用 curl。
- 域名按 `sky.coflnet.com` → `sky-commands.coflnet.com` → `sky-mod.coflnet.com` 的顺序回退。

## 自动扫描

配置文件 `config/gdragflip.json`：

```json
{
  "autoScan": false,
  "intervalSeconds": 60,
  "minProfit": 5000000,
  "minRoiPercent": 5.0
}
```

- `intervalSeconds`：扫描间隔（秒）。低于 20 秒时命令需要 `confirm` 二次确认，避免打满 API 额度。
- 命中条件：利润 ≥ `minProfit` **且** ROI ≥ `minRoiPercent`。
- 同一条拍卖只提醒一次；每轮最多提醒 3 条以避免刷屏。
- 只在游戏内（有玩家实体）时运行。

## 构建

需要 JDK 25+：

```
gradlew build
```

产物：`build/libs/gdragflip-<版本>-<MC版本>.jar`，放入 `mods/`，依赖 Fabric Loader 与 Fabric API。

分支对应关系：`main` 面向 MC 26.3，`26.2` 面向 MC 26.2（只需切换 `gradle.properties` 中的
`minecraft_version` / `fabric_api_version` 与 `fabric.mod.json` 的 `minecraft` 依赖）。

## 局限

- 价格随挂单实时变动，报告只是请求时刻的快照。
- 用最低价 100+ 与最低价 200 级作为两个锚点，属于**单点基准**：一个异常挂单会影响整条曲线
  （已按需求保留该口径，不做离群过滤）。
- 未扣除拍卖手续费，未考虑 pet item、皮肤等溢价因素。
- 若市场上没有 200 级挂单，或 200 级最低价低于 100+ 最低价（价格倒挂），则无法计算每经验单价。
- 详情查询失败时该挂单会退化为「按等级起点经验」计算，并在悬停里标注「估算」。
