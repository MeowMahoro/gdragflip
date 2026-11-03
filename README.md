# Golden Dragon Flip

Hypixel SkyBlock 客户端 Fabric mod：分析拍卖行里 **100 级以上的 Golden Dragon**，按「等级线性均价」找出被低估、适合买下养到 200 级再卖的挂单。

数据源：`sky.coflnet.com`（Cofl SkyApi，免 Key）。

## 功能

游戏内执行：

```
/gdrag            # 显示低于理论价的前 10 条
/gdrag 20         # 显示前 20 条（1~50）
/gdragflip        # 同 /gdrag
```

输出示例：

```
[金龙] 扫描 74 条挂单，其中 60 条 Lvl100+（Lvl200 32 条）
[金龙] 基准：Lvl100 最低 669.0M ｜ Lvl200 最低 900.0M ｜ 每级 2.31M
[金龙] 低于理论价 9 条（按利润降序）：
 #1  Lvl 131  705.0M  理论 740.6M  利润 +35.6M (+5.1%)  剩 3d2h  [拍卖] [Cofl]
```

- `[拍卖]`：点击执行 `/viewauction <uuid>`，直接在游戏里打开该拍卖（Hypixel 的查看拍卖命令为 `/viewauction`，不是 `/auction`）。
- `[Cofl]`：点击打开 `https://sky.coflnet.com/auction/<uuid>`。
- 鼠标悬停可看卖家、精确价格与拍卖 UUID。
- 如果该金龙使用过 pet candy，行尾会标注 `(N/10)`；未使用过的不标注。详情请求合并为一次 curl 调用（复用连接），单次最多 10 条，结果按 UUID 缓存。

## 算法

1. 拉取全部在挂 `PET_GOLDEN_DRAGON`，只保留 **LEGENDARY + BIN + Lvl ≥ 100**。
2. 基准 A = 其中价格最低的一条（记录等级 `L0` 与价格 `P0`）。
3. 基准 B = 200 级中价格最低的一条（`P200`）。
4. 每级均价 `avg = (P200 - P0) / (200 - L0)`。
5. 任意挂单理论价 `expected = P0 + (level - L0) × avg`。
6. 利润 `profit = expected - price`，正数即「低于平均价」；在同一线性假设下它也等于「买下养到 200 再按 P200 卖出」的毛利。
7. 按 `profit` 降序排名；没有正利润时展示利润最高的 3 条作参考。

Lvl 1~99 是 `Golden Dragon Egg`（未破壳、不能装备宠物物品），因此只统计 100 级以上。

## 数据源说明

使用 `GET /api/auctions/active/filter?tags=PET_GOLDEN_DRAGON&pageSize=100&page=N`。

没有用 `/api/auctions/tag/{tag}/active/bin`，因为它只返回最低的 10 条；金龙低价挂单基本都是 Lvl 1~9 的蛋，会完全盖住 100 级以上的挂单。

- 免费接口有速率限制（约 80 次/分钟），mod 每次查询只发 1~N 个分页请求，并在 15 秒内复用上次结果。
- 查询在后台线程执行，不阻塞游戏。
- **Cloudflare 兼容**：Cloudflare 会按 TLS 指纹拦截 Java HTTP 客户端（HTTP/1.1、HTTP/2、HttpsURLConnection 实测均返回 403），因此 Java 请求失败后会自动改用系统 `curl`（Windows 自带 `C:\Windows\System32\curl.exe`，macOS/Linux 为 `curl`），并在本次游戏会话内一直使用 curl。
- 域名按 `sky.coflnet.com` → `sky-commands.coflnet.com` → `sky-mod.coflnet.com` 的顺序回退（后两个是官方 mod 使用的备用域名）。

## 构建

需要 JDK 25+：

```
gradlew build
```

产物：`build/libs/gdragflip-<版本>-<MC版本>.jar`，放入 `mods/`，依赖 Fabric Loader 与 Fabric API。

分支对应关系：`main` 面向 MC 26.3，`26.2` 面向 MC 26.2（只需切换 `gradle.properties` 中的 `minecraft_version` / `fabric_api_version` 与 `fabric.mod.json` 的 `minecraft` 依赖）。

## 局限

- 价格随挂单实时变动，报告只是请求时刻的快照。
- 每级均价是**线性近似**；宠物实际升级经验曲线并非线性，真实成本请结合游戏内经验/喂食方案判断。
- 未扣除拍卖手续费，也未识别宠物物品（pet item）、`candyUsed` 等溢价因素；这些属性会让同等级挂单价格出现偏差。
- 若市场上没有 200 级挂单，或 200 级最低价低于 100+ 最低价（价格倒挂），则无法计算每级均价，只显示当前最低价。
