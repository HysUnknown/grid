# 网格（Grid）选择器语法子集规范 selector-spec

## 1. 范围

本规范定义"网格（Grid）"项目所支持的 GKD 选择器语法子集及其规则字段语义，是引擎实现的唯一依据：

- **Kotlin 引擎**（Android 模块 `:engine`，包名 `cn.hys159x.grid.engine`）与未来 **TS 引擎**（HarmonyOS 计划）的实现必须完全一致。
- **标准用例集（§7）是两实现一致性的判定标准**：同一用例在两个引擎上必须得到相同的命中结果与目标节点。
- 本规范的内容来源为 2026-09-20 对 gkd.li 官方文档的抓取校准结论（见 `docs/plans/2026-09-20-android-mvp/00-overview.md` "GKD 语法校准结论"一节）。凡本规范未列出的语法/字段，均不在本子集内，按 §4 的忽略/不支持策略处理。
- 本规范只约束选择器与规则语义；订阅拉取、无障碍服务装配、UI 等宿主侧行为不在本规范范围内。

## 2. 选择器语法

### 2.1 目标节点与通配单元

- 目标节点由 **`@` 标记的属性选择器**指定；**无 `@` 时取最后一个属性选择器**作为目标。
- 匹配以目标为起点：先在节点树中定位满足目标选择器的节点，再以它为起点向链两侧验证关系（详见 §6）。
- **`*` 通配单元**：单元位置上的孤立 `*` 匹配**任意节点**（等价于无条件约束的属性选择器），用于在链中占位以表达"中间隔若干层/若干兄弟"。`*` 与其他单元一样受关系符约束，如 `@View[clickable=true] <3 * <2 * < FrameLayout[id="..."]`（@View 与 FrameLayout 之间隔固定的 3+2+1 层，中间两层节点不作任何属性要求）。

```
@[vid="skip"] <<2 [vid="ad"]
└─ 目标 = @[vid="skip"]（带 @ 标记；skip 是 ad 的第 2 层子孙）

[vid="ad"] [text="跳过"]
└─ 目标 = [text="跳过"]（无 @，取最后一个）
```

### 2.2 关系符

关系符写在两个属性选择器之间，描述**左侧节点相对右侧节点的位置**：

| 关系符 | 语义 | 示例 |
|---|---|---|
| `A B`（空格） | A 是 B 的祖先（任意层） | `LinearLayout TextView`（等价 `LinearLayout > TextView`） |
| `A >n B` | A 是 B 的第 n 层祖先（`>` 无数字 = 任意层） | `@[vid="ad"] >2 [vid="skip"]` |
| `A <n B` | A 是 B 的第 n 层子节点（`<` 无数字 = 1 层直接子） | `TextView < [vid="ad"]` |
| `A <<n B` | A 是 B 的子孙（`<<` 无数字 = 任意层子孙） | `[text="跳过"] <<2 [vid="root"]` |
| `A +n B` | A 是 B 的前置（左）兄弟，A.index = B.index − n（`+` 无数字 = 1） | `[vid="x"] + [vid="y"]` |
| `A -n B` | A 是 B 的后置（右）兄弟（`-` 无数字 = 1） | 同上反向 |

**示例解读**（关系符描述左侧节点相对右侧节点的位置；目标 = `@` 标记者，无 `@` 取最后一个属性选择器）：

- `@[vid="ad"] >2 [vid="skip"]`：目标是 **`vid="ad"` 的节点**（@ 标记）；skip 在 ad 内部第 2 层——ad 是 skip 的**第 2 层祖先**（skip 父节点的父节点即 ad）。命中后对 ad 节点执行动作。
- `TextView < [vid="ad"]`：无 `@`，目标 = 最后一个属性选择器 = **`vid="ad"` 的节点**；TextView 是 ad 的**直接子节点**（`<` 无数字等价 `<1`）。
- `[text="跳过"] <<2 [vid="root"]`：无 `@`，目标 = **`vid="root"` 的节点**；`text="跳过"` 的节点是 root 的**第 2 层子孙**（位于 root 内部第 2 层）。
- `[vid="x"] + [vid="y"]`：无 `@`，目标 = **`vid="y"` 的节点**；x 是 y 的**紧邻左兄弟**（x.index = y.index − 1）。
- `[vid="y"] - [vid="x"]`：无 `@`，目标 = **`vid="x"` 的节点**；y 是 x 的**紧邻右兄弟**。

**数字形式：** 关系符的数字支持三种形式："无数字"、"单一正整数 `n`"（如 `>2`、`<3`、`<<2`、`+2`、`-2`）与"多值 `(a1,a2)`"（如 `>(1,2)`、`+(1,3)`）。单一数字的语义为上表中的"第 n 层/偏移 n"；多值为 **OR 语义**——实际层级差（或兄弟偏移量）等于任一列出值即匹配，如 `>(1,2)` 表示 A 是 B 的第 1 层或第 2 层祖先；无数字时语义为上表括号内注明值。

**字面 `n`（任意）形式（v1.3）：** 关系符后可写字面字母 `n`，表示"任意层/任意偏移"：

| 写法 | 语义 |
|---|---|
| `A >n B` | A 是 B 的祖先（任意层，同 `>` 无数字） |
| `A <n B` | A 是 B 的子孙（任意层 ≥1，不含自身；同 `<<` 无数字） |
| `A <<n B` | 同上，任意层子孙 |
| `A +n B` | A 是 B 的前置兄弟（同父、任意偏移） |
| `A -n B` | A 是 B 的后置兄弟（同父、任意偏移） |

官方订阅大量使用（如 `[id$="tt_splash_skip_btn"] <<n [vid="rlAdView"]`、`TextView[text*="青少年模式"] +n TextView[text="知道了"]`）。字面 `n` 后紧跟标识符字符（字母/数字/下划线）时按**节点名前缀**解析而非字面 n，如 `A >nameView` = "A 是名为 nameView 的单元的任意层祖先"；多值列表内混写 n（如 `>(1,n)`）不在子集内。

### 2.3 属性清单

| 类别 | 属性 |
|---|---|
| 标识 | `id`、`vid`、`name` |
| 文本 | `text`、`desc` |
| 布尔状态 | `clickable`、`longClickable`、`focusable`、`checkable`、`checked`、`editable`、`visibleToUser` |
| 几何 | `left`、`top`、`right`、`bottom`、`width`、`height` |
| 结构 | `childCount`、`index`、`depth` |
| 属性方法 | `text.length`、`desc.length` |

### 2.4 操作符

| 操作符 | 适用类型 | 语义 |
|---|---|---|
| `=`、`!=` | 全类型 | 相等 / 不等 |
| `>`、`>=`、`<`、`<=` | int | 数值比较 |
| `^=`、`$=`、`*=`、`~=` | string | 前缀 / 后缀 / 包含 / 正则 |
| `!^=`、`!$=`、`!*=`、`!~=` | string | 上述四者的否定形式 |

- `~=` 右侧为正则表达式，引擎侧用 Kotlin Regex（TS 侧用 RegExp）求值；正则非法时该条件按不匹配处理（求值为 false），不视为错误。
- **属性为 null 时，除 `=`/`!=` 外表达式一律为 false——包括否定操作符 `!^=`、`!$=`、`!*=`、`!~=`**（即 null 只参与等/不等判断：属性值为 null 时 `!=xxx` 成立、`=xxx` 不成立。`[text!*="关闭"]` 在 text 为 null 的节点上同样不成立，而非因"不包含"而成立）。
- **`null` 字面量值（v1.3）**：`=`/`!=` 右侧可写 `null` 字面量：`[id=null]` 在属性值为 null 时成立、`[text!=null]` 在属性值非 null 时成立；null 字面量与其他操作符组合（如 `[text*=null]`）可解析但求值恒为 false。布尔与整型属性永不为 null：其 `=null` 恒为 false、`!=null` 恒为 true。null 字面量不参与值类型校验。
- 值类型必须匹配属性类别，与操作符无关：字符串属性（`id`/`vid`/`name`/`text`/`desc`）要求字符串值（`.length` 除外，其要求整数值）；布尔属性要求布尔值；整型属性要求整数值。解析期校验，不匹配抛语法异常。
- 单选择器内多个属性条件并列为 **AND** 关系：`[text*="跳过"][text.length<10][visibleToUser=true]` 表示三个条件同时满足。
- **`[...]` 内空格不敏感（v1.3）**：属性块内部（属性名/操作符/值/逻辑符两侧）允许空格，如 `[clickable = true]`、`[text $="s" && text.length=2]`（官方订阅 com.dianxinai.mobile 实例）。属性块外的空格仍是单元分隔（关系符语义），`[...]` 之外不适用本条。

### 2.5 name 前缀与字符串值

- **name 前缀**：属性选择器前可写节点名前缀（由字母、数字、下划线组成的标识符），等价于在该单元追加一条 `NAME=名称` 等值条件：`TextView[text*="跳过"]` 等价于 `[name="TextView"][text*="跳过"]`。前缀可与多个属性条件及 `@` 标记组合（如 `@TextView[vid="skip"]`）；仅写前缀不带属性条件也合法（`TextView` 等价 `[name="TextView"]`），也可作为空格关系符的右侧单元（`LinearLayout TextView` = TextView 是 LinearLayout 的任意层后代）。
- **字符串值转义**：字符串值以双引号 `"` 包围，反斜杠为转义前缀：`\"` 表示引号本身、`\\` 表示反斜杠本身、`\n` 表示换行、`\t` 表示制表符。如 `[text="a\"b"]` 匹配文本 `a"b`。
- **反引号字符串（v1.3）**：字符串值也可用反引号 `` ` `` 包围，**内部无任何转义**，读到下一个反引号为止：`` [id=`tv.danmaku.bili:id/ad_tint_frame`] ``（官方订阅用于含 `:` 的完整 id 值等场景）。反引号未闭合为语法错误。

### 2.6 属性块内布尔表达式（v1.3）

单个 `[...]` 内可写多条属性条件，用 `||`（OR）、`&&`（AND）与**括号分组**组合：

```
[desc="跳过"||desc="GdtCountDownView"]
[childCount=4||childCount=3]
[(text.length<10&&(text*="跳过"||text*="跳过^")) || id$="tt_splash_skip_btn" || (vid*="count" && vid*="down" && vid!*="download")]
```

- **优先级：`&&` 高于 `||`**（`a&&b||c` 解析为 `(a&&b)||c`）；括号可改变分组，支持嵌套。
- 求值语义：AND=全部成立、OR=任一成立，单条件即其本身；各条件内部仍按 §2.4 的操作符与 null 语义求值。
- 括号仅在 `[...]` 内作为分组符；`[` 之外关系符后的 `(a,b)` 仍是多值数字（§2.2），两者互不影响。
- 多个 `[...]` 并列（如 `[childCount=0][visibleToUser=true][表达式块]`）之间仍为 **AND**，与 name 前缀条件（NAME=）同样按 AND 参与。
- 实例：全局开屏兜底规则（官方订阅 globalGroups）即依赖本语法，v1.2 子集因不支持而整条跳过，导致开屏广告不被拦截（真机验证发现，v1.3 修复）。

## 3. 规则字段

规则字段语义对应 GKD 的 RawRuleProps / RawCommonProps（官方确认）：

| 字段 | 语义 | 默认 |
|---|---|---|
| `matches` | 多个选择器**全部命中才执行动作（AND）**，目标 = 最后一个选择器的目标节点 | — |
| `anyMatches` | 任一命中即匹配（OR）；与 matches 同用时目标仍取 matches 最后一项 | — |
| `excludeMatches` | 任一命中即整条规则本轮不执行（OR 排除） | — |
| `excludeAllMatches` | 全部命中才排除（AND 排除） | — |
| `action` | 动作类型（见 §5）：`click`(默认，clickable→clickNode 否则 clickCenter) / `clickNode` / `clickCenter` / `back` / `longClick` / `longClickNode` / `longClickCenter` / `none`；`swipe` 见 §4 忽略清单 | `click` |
| `preKeys` | 顺序依赖：所引 key 的规则刚执行过，本规则才触发 | — |
| `actionCd` | 执行动作的最小间隔（ms） | `1000` |
| `actionDelay` | 查到节点后延迟执行 | — |
| `actionMaximum` | 动作最大执行次数，达到后规则休眠 | — |
| `resetMatch` | 计数重置时机：`activity`(默认) / `match` / `app` | `activity` |
| `matchTime` | 参与匹配的时间窗（ms），超时休眠（开屏类） | — |
| `matchRoot` | 从根节点而非事件节点开始匹配（网格实现：全树匹配，字段保留兼容，见 §6.1） | `false` |
| `order` | 匹配顺序，小者先 | `0` |
| `activityIds` / `excludeActivityIds` | 限定 / 排除 Activity | — |

**外层结构字段（同样在本子集内支持）：**

| 层级 | 支持字段 |
|---|---|
| group | `key`、`name`、`enable` |
| app | `id`、`name`、`groups` |
| 订阅 | `id`、`name`、`version`、`author`、`apps` |

**订阅外层结构补充（Task 8 实测修正，展开见 `docs/plans/2026-09-20-android-mvp/00-overview.md` "订阅外层结构补充"节）：**

- group（含 `globalGroups[]`）除上表字段外还携带组级 RawCommonProps 字段（`actionCd`、`actionDelay`、`actionMaximum`、`resetMatch`、`matchTime`、`matchRoot`、`order`），语义是**组内规则的默认值**——取值链为"规则级 > 组级 > GKD 默认"三级，规则级同名字段覆盖组级。
- `globalGroups[].apps` 是**排除名单**：全局组默认对所有 App 生效（编译为 `"*"`），`apps` 中 `enable=false` 的 App 被该组排除（gkd `getGlobalGroupInnerDisabled` 语义）；排除关系经 `CompiledRuleSet.globalGroupExcludedApps` 下发，应用侧应用 `"*"` 规则时按包名过滤。
- `matches` / `preKeys` / `activityIds` / `anyMatches` / `excludeMatches` / `rules` 在官方数据中存在 string | string[] | object 多形态，解析时统一归一为列表（多态 serializer）。

## 4. 忽略与不支持清单

### 4.1 忽略字段（可解析不报错，不参与执行）

解析到以下字段时正常解析、不报错，规则**其余部分照常执行**：

- `fastQuery`（官方 dist 实际字段名为 `quickFind`，两者等价）、`forcedTime`、`priorityTime`、`priorityActionMaximum`
- `actionCdKey`、`actionMaximumKey`、`scopeKeys`
- `version*` 系列
- `swipeArg` / `position`——**含 `swipe` 动作本身**：action 为 `swipe` 的规则解析为不支持的规则，**整条跳过并计数**
- `snapshotUrls` 等快照辅助字段

### 4.2 不支持语法（解析到即整条规则标记 skip 并计数）

- `->` 查询顺序符
- `(` 开头的复杂表达式（属性块 `[...]` 之外的括号；`[...]` 之内的括号分组自 v1.3 起支持，见 §2.6）
- context 属性（`prev.` / `current.`）
- `_` 开头快照属性
- 关系符多值列表内混写字面 `n`（如 `>(1,n)`；单写 `>n` 等已支持，见 §2.2）

> v1.2 曾列出的"属性块内 `||`/`&&`、`*` 通配单元、字面 `n` 关系符、`null` 字面量、反引号字符串"共 5 类语法（Task 8 实测合计 skip ×26，含全局开屏兜底规则）已于 v1.3 全部纳入子集（§2.1/§2.2/§2.4/§2.5/§2.6），官方订阅 v186 回归 skipped 降为 0。

含上述任一语法的规则**不参与匹配与执行**，且计入"跳过规则数"统计（便于观察订阅中子集外规则的占比）。

## 5. 动作语义

| 动作 | 语义 |
|---|---|
| `click`（默认） | 目标节点 clickable 时用节点点击（clickNode），否则用坐标中心点击（clickCenter） |
| `clickNode` | 节点 `ACTION_CLICK`（屏幕外也可） |
| `clickCenter` | 计算 bounds 中心发手势（出屏 = 未匹配，不执行） |
| `back` | `GLOBAL_ACTION_BACK` |
| `longClick` | 目标节点 longClickable 时用节点长按，否则坐标中心长按 |
| `longClickNode` | 节点 `ACTION_LONG_CLICK`（屏幕外也可） |
| `longClickCenter` | 计算 bounds 中心发长按手势（出屏 = 未匹配，不执行） |
| `none` | 匹配成功但不执行任何动作（仅记录，用于占位或配合 `preKeys` 链） |

说明：

- "屏幕外也可"指节点中心不在可视区域内时动作依然派发（系统自行处理）；而 `*Center` 系坐标手势要求中心点在屏幕内，否则该目标视为未匹配。
- `swipe` 不在本子集动作内，见 §4.1。

## 6. 匹配算法

引擎核心为 **ChainMatcher**，输入为一条选择器链（经解析器切分为"属性选择器 + 关系符"序列）与节点树，输出命中的目标节点或 null：

1. **目标候选枚举**：从 root 出发对全树做深度优先遍历，收集所有满足**目标单元**（`@` 标记的单元，无 `@` 时为最后一个单元）属性条件的节点作为候选。候选顺序即深度优先序。匹配范围：网格实现总是从树根开始全树深度优先枚举（它是『自事件节点向上回溯至根的路径上全部子树』的超集）。matchRoot 与事件节点字段保留解析兼容，但不影响匹配范围（仅 GKD 原版用其缩小候选首序，网格 MVP 不实现，差异仅体现在极端情况下的首个命中选择顺序）。
2. **以目标为起点向两侧回溯分配单元**：对每个候选节点，先把它固定分配给目标单元；随后反复选取与已分配单元相邻的未分配单元，依据相邻关系符从已分配邻居反推该单元的候选节点集合（例如 `A >2 B`：已知 B 求 A 时沿 B 的父链向上取第 2 层，已知 A 求 B 时在 A 子树内取深度差为 2 的节点），逐一尝试分配并**验证每个相邻单元对的两个节点满足其间关系符**——关系符描述左单元节点相对右单元节点的位置，多值形式为 OR 语义、命中任一值即通过；分配冲突时回溯尝试下一候选节点。全部单元分配完毕且所有相邻对验证通过，该候选即通过。`*` 通配单元按普通单元参与（属性恒匹配，候选仅由关系符约束）；字面 `n` 的任意偏移兄弟（`+n`/`-n`）在候选生成时枚举同父的全部前置/后置兄弟、验证时按同父且偏移方向判定。
3. **命中判定**：任一候选节点全链验证通过即命中；返回**首个通过者（深度优先序）**，其余候选不再考察。所有候选均失败则本次匹配返回 null。
4. **规则级组合**（选择器链之上）：按 §3 先算 `matches`（全部 AND）与 `anyMatches`（任一 OR，目标仍取 matches 最后一项），再算 `excludeMatches`/`excludeAllMatches` 排除；排除命中则本轮整条规则不执行。

## 7. 标准用例集格式

标准用例集为 **JSON 数组**，每条用例结构：

```json
{
  "name": "用例名（唯一，便于失败定位）",
  "selector": "待验证的选择器字符串",
  "tree": { "节点树": "见下" },
  "expectTargetId": "期望命中的目标节点 id；null 表示应无命中"
}
```

`tree` 节点结构（字段集为 `vid/text/desc/clickable/children`；所有属性字段均可省略，省略即视为 null/未设置；`children` 省略视为无子节点）：

```json
{
  "vid": "节点 vid（可选）",
  "text": "节点文本（可选）",
  "desc": "节点内容描述（可选）",
  "clickable": "是否可点击（可选）",
  "children": [ /* 子节点数组，顺序即 index 顺序 */ ]
}
```

完整用例示例（`@` 标记目标 skip，且 skip 是 ad 的第 2 层子孙）：

```json
[
  {
    "name": "ancestor-2-skip-button",
    "selector": "@[vid=\"skip\"] <<2 [vid=\"ad\"]",
    "tree": {
      "vid": "root",
      "children": [
        {
          "vid": "ad",
          "children": [
            {
              "vid": "ad-inner",
              "children": [
                { "vid": "skip", "text": "跳过 5", "clickable": true }
              ]
            }
          ]
        },
        { "vid": "content", "text": "正文" }
      ]
    },
    "expectTargetId": "skip"
  }
]
```

约定：

- MVP 阶段用例集以 Kotlin 测试类承载（Task 4-7 各测试即用例）；TS 引擎移植时将其导出为 JSON fixture 复用（鸿蒙计划的首个任务）。
- `expectTargetId` 通过节点上的 `vid` 标识期望目标；`null` 表示该选择器在此树上必须无命中。
- 用例必须同时覆盖：每个关系符（带/不带数字）、`@` 与默认目标、null 属性的操作符语义、排除字段组合、不支持语法的 skip 行为；v1.3 起另须覆盖属性块布尔表达式（嵌套/优先级）、字面 `n` 关系符、`*` 通配单元、`null` 字面量、反引号字符串与属性块内空格容忍。

## 8. 版本

- **v1.3**（2026-09-20）：真机验证实用扩展（官方订阅 v186 全量回归 skipped 26 → 0，全局开屏兜底规则恢复拦截）——①§2.6 新增属性块内布尔表达式（`||`/`&&`/括号分组，`&&` 优先级高于 `||`，多 `[...]` 并列仍 AND）；②§2.2 新增字面 `n` 关系符（`<n`/`<<n` 任意层子孙、`>n` 任意层祖先、`+n`/`-n` 任意偏移兄弟；n 后紧跟标识符字符按节点名前缀解析）；③§2.1 新增 `*` 通配单元；④§2.4 新增 `null` 字面量值（仅 `=`/`!=` 有意义，其他操作符恒 false）与属性块内空格容忍；⑤§2.5 新增反引号字符串（无转义）与裸 name 单元作空格关系右侧（`LinearLayout TextView`）；⑥§4.2 不支持清单相应缩减（多值列表混写 n 仍不支持）；⑦§6 补充通配单元与任意偏移兄弟的候选生成/验证说明。

- **v1.2**（2026-09-20）：订阅外层语义修正（Task 8 官方订阅实测）——①§3 补充组级 RawCommonProps 默认值链（规则级 > 组级 > GKD 默认）；②§3 明确 `globalGroups[].apps` 为排除名单（enable=false 的 App 被排除，组默认对所有 App 生效）；③`matches`/`preKeys`/`activityIds`/`anyMatches`/`excludeMatches`/`rules` 多形态（string | string[] | object）统一归一为列表；④§4.1 `fastQuery` 括注官方 dist 实际字段名 `quickFind`（两者等价）；⑤§4.2 补 Task 8 实测 skip 语法（括号内 `||`/`&&`、`*` 通配单元、字面 `n` 步数关系符、`null` 字面量、反引号字符串）；⑥默认订阅分发地址改为 jsdelivr（`https://fastly.jsdelivr.net/npm/@gkd-kit/subscription`，内容为 JSON5，见 00-overview "默认订阅源"）。
- **v1.1**（2026-09-20）：勘误与澄清——①关系符方向示例勘误：`A >n B` 中 A 是外层祖先、B 在 A 内部 n 层，§2.1/§2.2/§6/§7 示例及解读按此修正；②目标节点解读统一按"`@` 标记、无 `@` 取最后一个属性选择器"改写；③§2.2 补充关系符多值 `(a1,a2)`（OR 语义）；④新增 §2.5 name 前缀语法与字符串转义规则；⑤§2.4 明确否定操作符遇 null 为 false；⑥§6 匹配算法改为与实现一致（从 root 全树深度优先枚举目标候选，以目标为起点向两侧回溯分配单元并验证相邻关系对）；⑦§7 用例树节点字段集定为 `vid/text/desc/clickable/children`。
- **v1.0**（2026-09-20）：初稿定稿。内容来源为 2026-09-20 对 gkd.li 官方文档的抓取校准记录（详见 `docs/plans/2026-09-20-android-mvp/00-overview.md` "GKD 语法校准结论"）。
- 修订规则：语义变更须升版本号并在本节记录变更点；两引擎实现与本规范不一致时，以本规范 + 标准用例集为准修正实现。
