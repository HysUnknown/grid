# 网格（Grid）Android MVP 实施计划 — 总览

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付可安装到真机使用的 Android 广告拦截 App：拉取 GKD 官方订阅、解析规则、经无障碍服务自动点击"跳过/关闭"按钮并记录拦截日志。

**Architecture:** 双模块 Gradle 工程——`engine`（纯 Kotlin JVM，选择器解析/节点匹配/规则执行，零 Android 依赖可单测）+ `app`（无障碍服务装配、订阅下载缓存、Compose UI、Room 日志）。引擎通过平台无关的 `TreeNode` 接口解耦，Android 侧用适配器包装 `AccessibilityNodeInfo`。

**Tech Stack:** Kotlin 2.0.21、AGP 8.7.x、Compose BOM、kotlinx.serialization、OkHttp、Room（KSP）、JUnit5（引擎）、minSdk 26 / targetSdk 35。

**分册索引（按顺序执行）：**

| 分册 | 任务 | 内容 |
|---|---|---|
| [01-skeleton.md](01-skeleton.md) | Task 1-2 | Gradle 骨架、selector-spec.md 规范 |
| [02-engine-core.md](02-engine-core.md) | Task 3-6 | TreeNode、选择器解析器、属性求值、链匹配 |
| [03-engine-rule.md](03-engine-rule.md) | Task 7-8 | 规则执行器、GKD 订阅解析+官方回归 |
| [04-android.md](04-android.md) | Task 9-12 | 节点适配器+状态桥、无障碍服务、断连提醒、订阅仓库 |
| [05-ui-release.md](05-ui-release.md) | Task 13-15 | Compose UI、Room 日志、CI 发布 |
| [06-verify.md](06-verify.md) | Task 16 | 真机验证清单 |

---

## GKD 语法校准结论（2026-09-20 抓取 gkd.li 官方文档确认）

引擎实现以本结论为准，写引擎任务前必读：

### 选择器语法

- **目标节点：`@` 标记的属性选择器；无 `@` 时取最后一个属性选择器。** 匹配以目标单元为起点、向链两侧回溯验证关系（GKD 官方性能提示“从右往左”指先定位末尾锚点再向左验证，两者等价）。
- 关系符（写在两个属性选择器之间，描述**左侧节点相对右侧节点的位置**）：

| 关系符 | 语义 | 示例 |
|---|---|---|
| `A B`（空格） | A 是 B 的祖先（任意层） | `LinearLayout >n TextView` 的简写链 |
| `A >n B` | A 是 B 的第 n 层祖先（`>` 无数字=任意层） | `@[vid="ad"] >2 [vid="skip"]` |
| `A <n B` | A 是 B 的第 n 层子节点（`<` 无数字=1 层直接子） | `TextView < [vid="ad"]` |
| `A <<n B` | A 是 B 的子孙（`<<` 无数字=任意层子孙） | `[text="跳过"] <<2 [vid="root"]` |
| `A +n B` | A 是 B 的前置（左）兄弟，A.index = B.index - n（`+` 无数字=1） | `[vid="x"] + [vid="y"]` |
| `A -n B` | A 是 B 的后置（右）兄弟（`-` 无数字=1） | 同上反向 |

注意方向：`A >n B` 表示 A 是外层容器、B 在 A 内部 n 层；`@` 标记目标节点，无 `@` 时目标取最后一个属性选择器。

- 属性：`id`、`vid`、`name`、`text`、`desc`、`clickable`、`longClickable`、`focusable`、`checkable`、`checked`、`editable`、`visibleToUser`、`left/top/right/bottom`、`width/height`、`childCount`、`index`、`depth`；属性方法 `text.length`、`desc.length`。
- 操作符：`=`、`!=`（全类型）；`>` `>=` `<` `<=`（int）；`^=` `$=` `*=` `~=` 及否定形式 `!^=` `!$=` `!*=` `!~=`（string）。**属性为 null 时，除 `=`/`!=` 外表达式为 false。** `~=` 右侧为正则（引擎侧用 Kotlin Regex）。
- 单选择器内多个属性条件并列（AND）：`[text*="跳过"][text.length<10][visibleToUser=true]`。
- **v1.3 扩展（真机验证实用扩展，官方订阅 v186 全量语法）**：①属性块内布尔表达式 `[a="x"||b="y"]`、`&&`、括号分组（`&&` 优先级高于 `||`，多个 `[...]` 并列仍 AND）——全局开屏兜底规则依赖此语法；②字面 `n` 关系符：`<n`/`<<n` 任意层子孙、`>n` 任意层祖先、`+n`/`-n` 任意偏移兄弟；③`*` 通配单元（任意节点占位，如 `@View[clickable=true] <3 * <2 * < FrameLayout[...]`）；④`null` 字面量值（`[id=null]`/`[text!=null]`，仅 `=`/`!=` 有意义）；⑤反引号字符串（`` [id=`pkg:id/name`] ``，无转义）；⑥属性块内空格容忍（`[clickable = true]`）与裸 name 单元作空格关系右侧（`FrameLayout TextView[...]`）。详见 selector-spec v1.3 §2.1/§2.2/§2.4/§2.5/§2.6。

### 规则字段语义（RawRuleProps / RawCommonProps，官方确认）

| 字段 | 语义 | 默认 |
|---|---|---|
| `matches` | 多个选择器**全部命中才执行动作（AND）**，目标=最后一个选择器的目标节点 | — |
| `anyMatches` | 任一命中即匹配（OR）；与 matches 同用时目标仍取 matches 最后一项 | — |
| `excludeMatches` | 任一命中即整条规则本轮不执行（OR 排除） | — |
| `excludeAllMatches` | 全部命中才排除（AND 排除） | — |
| `action` | `click`(默认，clickable→clickNode 否则 clickCenter) / `clickNode` / `clickCenter` / `back` / `longClick*` / `swipe` / `none` | click |
| `preKeys` | 顺序依赖：所引 key 的规则刚执行过，本规则才触发 | — |
| `actionCd` | 执行动作的最小间隔（ms） | 1000 |
| `actionDelay` | 查到节点后延迟执行 | — |
| `actionMaximum` | 动作最大执行次数，达到后规则休眠 | — |
| `resetMatch` | 计数重置时机：`activity`(默认)/`match`/`app` | activity |
| `matchTime` | 参与匹配的时间窗（ms），超时休眠（开屏类） | — |
| `matchRoot` | 从根节点而非事件节点开始匹配 | false |
| `order` | 匹配顺序，小者先 | 0 |
| `activityIds` / `excludeActivityIds` | 限定/排除 Activity | — |

### 订阅外层结构补充（Task 8 实测修正）

Task 8 以官方订阅实测后修正的外层结构语义（引擎已按此实现，见 `RawSubscription.kt` / `SubscriptionCompiler.kt`）：

- **组级 RawCommonProps 是组内规则的默认值**：组（`apps[].groups[]` 与 `globalGroups[]`）上的 `actionCd` / `actionDelay` / `actionMaximum` / `resetMatch` / `matchTime` / `matchRoot` / `order` 作为组内各规则同名字段的默认值，规则级覆盖组级，组级未设再落 GKD 默认——三级默认链：**规则级 > 组级 > GKD 默认**。官方开屏组大量在组级设置 `matchTime` / `actionMaximum` 防护，丢弃组级默认会导致规则无限触发。
- **`globalGroups[].apps` 是排除名单**（不是生效白名单）：全局组默认对**所有 App** 生效（规则编译到 `"*"`）；`apps` 项中 `enable=false` 的 App 被该组排除（gkd 源码 `getGlobalGroupInnerDisabled` 语义），`enable=true` 或纯字符串项仅为开启引用。排除关系经 `CompiledRuleSet.globalGroupExcludedApps`（groupKey → 排除 appId 集合）下发，应用侧应用 `"*"` 规则时按当前包名过滤。
- **字段多形态**：`matches` / `preKeys` / `activityIds` / `anyMatches` / `excludeMatches` / `rules` 在官方 dist 中均存在 string | string[] | object 多种形态（如 `preKeys: 0` 单值、`rules: '[...]'` 单串简写、`apps: ["pkg", { "id": ..., "enable": false }]` 混合数组），解析侧用多态 serializer 统一归一为列表。
- **quickFind**：官方 dist 实际字段名为 `quickFind`（“子集裁剪”忽略清单所记 `fastQuery` 为 API 文档名，两者等价），网格均忽略、不参与执行。

### 子集裁剪（spec §4）

**支持：** 上表全部（含 v1.3 扩展的布尔表达式/字面 n/`*` 通配/null 字面量/反引号字符串/空格容忍）+ group 的 `key/name/enable`、app 的 `id/name/groups`、订阅的 `id/name/version/author/apps`。
**忽略（可解析不报错，不参与执行）：** `fastQuery`、`forcedTime`、`priorityTime`、`priorityActionMaximum`、`actionCdKey`、`actionMaximumKey`、`scopeKeys`、`version*` 系列、`swipeArg`/`position`（含 swipe 动作，解析为不支持的规则跳过并计数）、`snapshotUrls` 等。
**不支持（解析到即整条规则标记 skip 并计数）：** `->` 查询顺序符、`(` 开头的复杂表达式（`[...]` 之外的括号）、context 属性（`prev.`/`current.`）、`_` 开头快照属性、多值列表混写字面 n（`>(1,n)`）。
> v1.2 曾不支持、v1.3 已纳入的 5 类语法（属性块 `||`/`&&`、`*` 通配、字面 `n`、`null` 字面量、反引号）：官方订阅 v186 实测合计 skip ×26（含全局开屏兜底规则，真机上表现为开屏广告不被拦截），扩展后回归 skipped=0。

### 默认订阅源

官方订阅经 npm 分发的编译产物（JSON5：裸键 + 单引号字符串，非标准 JSON，解析需先经 `SubscriptionJson.normalize` 重写引号）：
`https://fastly.jsdelivr.net/npm/@gkd-kit/subscription`
（备选：`https://registry.npmmirror.com/@gkd-kit/subscription/latest/files/dist/gkd.json5`。若均失效：以 github.com/gkd-kit/subscription 仓库 README 公布的最新分发地址为准替换 `SubscriptionRepository` 中的常量。）

## 统一约定

- 根目录：`D:\dev\app`，Android 工程位于 `android/`（模块 `android/settings.gradle.kts` 管理 `:app` 与 `:engine`）。
- applicationId / 包名：App 用 `cn.hys159x.grid.app`，引擎模块 `cn.hys159x.grid.engine`。
- Windows 环境命令一律用 `gradlew.bat`（在 `android/` 目录执行）；测试命令给出预期结果。
- 每个任务以独立 commit 结束，信息格式 `feat|test|docs|chore: <内容>`。
- 引擎所有单测跑在 JVM（`:engine:test`）；Android 装配层以真机验证为准（Task 16）。
- 标准用例集在 MVP 中以 Kotlin 测试类承载（Task 4-7 各测试即用例）；TS 引擎移植时将其导出为 JSON fixture 复用（鸿蒙计划的首个任务）。
- MVP 边界：仅内置官方订阅；订阅管理 UI / 自定义规则 / 快照审查 / 防跳转白名单 UI 不在本计划（spec §5 全量能力拆入后续 Android 完善计划）。
