# 网格（Grid）—— 三端广告拦截工具设计

日期：2026-09-20
状态：已与需求方逐节评审通过

## 0. 命名规范

- 产品名：**网格**（"网"为拦截之网，"格"为格挡广告）
- 英文代号：**Grid**
- 包名/Bundle ID：`cn.hys159x.grid.app`（Android applicationId、HarmonyOS bundleName、iOS Bundle ID 统一前缀）

## 1. 背景与目标

移动 App 普遍存在四类干扰：开屏广告、弹窗广告、强制跳转其他 App、营销弹窗（更新/评价/签到/青少年模式提醒）。本项目开发一套三端拦截工具：

- **Android App**（Kotlin，无障碍服务）：四大能力全支持，可自动点击"跳过"、关闭弹窗、阻断跳转。主线开发，真机可验证。
- **HarmonyOS NEXT App**（ArkTS，AccessibilityExtensionAbility）：与 Android 同能力，代码完整交付，待装 DevEco Studio 后验证。
- **iOS App**（Swift，NEPacketTunnelProvider DNS 过滤）：iOS 沙箱禁止第三方全局自动点击，只能做 DNS 级广告域名屏蔽。代码完整交付，待有 Mac 后编译。

**非目标（本期明确不做）：**

- iOS 端的自动点击/关闭弹窗（系统无公开 API，做不了）
- YOLO 视觉检测兜底（本期仅预留 Matcher 接口，见 §6）
- 自维护规则库（直接消费 GKD 社区订阅，见 §4）
- 应用市场上架（Android 走 GitHub Releases，NEXT 走侧载，iOS 走源码分发）

## 2. 已确认需求清单

| # | 需求 | 决策 |
|---|---|---|
| 1 | 平台 | Android + HarmonyOS NEXT + iOS，全部原生 |
| 2 | Android/NEXT 拦截能力 | 开屏自动跳过、弹窗自动关闭、阻止跳转其他 App、营销弹窗自动关闭（四项全选） |
| 3 | 规则格式 | 兼容 GKD 订阅格式（JSON），直接使用 gkd-kit 社区规则库 |
| 4 | 引擎方案 | 自研简化版 GKD 兼容引擎（选择器常用子集），不 fork GPL 代码，不 100% 复刻 |
| 5 | 使用范围 | 分发给他人：正式签名、隐私声明、GitHub Releases |
| 6 | Android 测试条件 | 有真机连 Windows adb 调试 |
| 7 | NEXT 测试条件 | 暂无环境：代码写完整，后续装 DevEco Studio 验证 |
| 8 | iOS 测试条件 | 暂无 Mac：代码写完整，后续编译 |
| 9 | 存量鸿蒙（HarmonyOS 2/3/4） | 天然支持（基于 AOSP，装 APK 即可），App 内做 ROM 保活引导 |
| 10 | YOLO 视觉兜底 | 下期做，本期留接口 |
| 11 | 用户参与 | 三层规则来源 + 社区 PR 贡献路径（见 §5） |

## 3. 总体架构

```
D:\dev\app\
├── android\          # Kotlin · 无障碍服务 · 主线（真机可验证）
├── harmony\          # ArkTS · AccessibilityExtensionAbility · 代码完整待验证
├── ios\              # Swift · DNS 过滤扩展 · 代码完整待 Mac
├── docs\
│   ├── specs\        # 本文档
│   └── selector-spec.md   # 选择器语法子集规范（三端引擎共同依据）
└── README.md         # 构建/安装/隐私声明
```

三端无共享代码（技术栈互不兼容），一致性由两份 artifact 约束：

1. `selector-spec.md`：选择器语法子集规范（Android/Kotlin 引擎与 Harmony/TS 引擎行为必须一致）
2. 标准测试用例集（同规范文档内附带，三端引擎共用，结果必须一致）

## 4. 选择器语法子集规范（selector-spec.md 要点）

取 GKD 选择器高频子集，覆盖绝大多数社区规则：

| 特性 | 示例 | 支持 |
|---|---|---|
| 属性匹配（text/vid/id/desc） | `[text="跳过"]` `[vid="close"]` `[desc*="关闭"]` | ✓ |
| 属性操作符 | `=` 精确、`*` 包含、`^` 前缀、`$` 后缀、`~` 正则 | ✓ |
| 多选择器链（最后者为目标节点） | `[text*="跳过"] <<2 [vid="ad"]` | ✓ |
| 节点导航 | `<<n` 父级×n、`>>n` 子级×n、`-n`/`+n` 同级 | ✓ |
| 动作 | `click`（点击目标节点或其可点击祖先）、`clickNode`、`back` | ✓ |
| 规则字段 | `actionDelay`、`actionMaximum`、`actionCd`、`excludeMatches`、`activityIds` | ✓ |
| 冷门组合 | `ordered`/`any`/`all` 嵌套、快照特性、`preKeys` | ✗ 解析到即跳过并计数上报 |

订阅数据结构：GKD 订阅 JSON（`apps[].groups[].rules[].matches` 等）按官方 schema 解析；无法识别的字段忽略，不影响已识别字段执行。

## 5. 规则来源与叠加

三层规则，优先级从高到低：

1. **本地自定义规则**：App 内直接添加（选目标 App → 写选择器 → 保存即生效，离线可用）。提供"控件快照审查"辅助功能：导出当前界面控件树为可读结构，辅助用户编写规则。
2. **用户添加的订阅**：任意人可把规则发布为 JSON（GitHub Gist / 任意 URL），App 内添加订阅 URL 即可用。
3. **内置默认订阅**：指向 GKD 官方订阅源，零自维护成本。

高优先级可禁用低优先级的单条规则（如某规则在某 App 误点，可单独关闭不影响其他）。

**社区贡献路径**：GKD 官方订阅库接受 GitHub PR，合并后所有 GKD 兼容 App（含本项目三端）可用。App 提供"复制规则为 PR 提交格式"快捷操作。

## 6. Android 端设计

### 分层

```
UI 层（Jetpack Compose，单 Activity）
  开关 / 按应用规则管理 / 拦截日志 / 订阅管理 / 权限与保活引导
服务层（AccessibilityService）
  监听 TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOW_CONTENT_CHANGED
规则引擎（纯 Kotlin 模块，零 Android 依赖，可 JVM 单测）
  选择器解析 + 节点树匹配 + 动作执行
数据层（Room + OkHttp）
  订阅缓存、规则库、拦截日志
```

匹配器抽象（YOLO 预留）：

```
interface Matcher {
  suspend fun match(screen: ScreenSnapshot): MatchResult?
}
// NodeMatcher：选择器引擎（本期实现）
// VisionMatcher：YOLO 视觉兜底（下期，仅自绘广告 App 的启动窗口内截图检测）
```

### 工作流

```
窗口变化事件 → 取包名+Activity → 查规则缓存（无命中规则即返回，零开销）
→ 引擎在无障碍节点树执行选择器匹配 → 命中 → 执行动作 → 记日志
```

### 关键机制

- **防误点**：开屏规则仅 App 启动后约 7 秒窗口内生效（可配置）；`actionMaximum` 限制同界面执行次数；目标节点做面积/可见性校验，不可见节点不点击。
- **防跳转**：监听窗口变化，其他 App 唤起的"打开方式/跳转确认"对话框按规则自动点"取消"或 `back`；白名单内 App 的正常跳转（如浏览器打开淘宝链接）不受影响。
- **性能**：规则按包名索引缓存；单轮匹配 200ms 预算超时放弃；同窗口 300ms 节流。
- **健壮性**：单条规则异常 try-catch 隔离并计数上报日志页，不拖垮服务；订阅拉取失败用本地缓存并提示；无障碍权限被系统回收时前台服务+通知提醒重开。
- **隐私边界**（写入隐私声明）：无障碍节点数据仅内存匹配，不上传任何内容；仅用户主动添加订阅时发起网络请求。

## 7. HarmonyOS NEXT 端设计（harmony/）

```
entry/                 主 App（ArkUI 声明式 UI，对齐 Android 功能）
accessibilityExt/      AccessibilityExtensionAbility 扩展
  onAccessibilityEvent（窗口/内容变化）
  AccessibilityNodeInfo 节点查询
  performAction（CLICK / GLOBAL_ACTION_BACK）
engine/                规则引擎（纯 TS，不依赖鸿蒙 API，Node 环境 uvtest 单测）
```

- 概念与 Android 一一对应；引擎按 selector-spec 用 TS 重写，订阅数据直接复用 GKD JSON。
- 差异点：无障碍扩展需在设置中手动开启；后台存活依赖系统"应用关联启动"管理，UI 内做引导。
- 交付标准：代码结构完整、TS 引擎通过标准测试用例集；真机验证留待装 DevEco Studio。

## 8. iOS 端设计（ios/）

```
AdBlocker/             主 App（SwiftUI）
  屏蔽列表管理（内置 + 自定义域名，支持导入 EasyList/hosts 格式）
  VPN 配置激活（NEVPNManager）
  拦截统计（按域名计数）
AdBlockerFilter/       NetworkExtension（NEPacketTunnelProvider）
  虚拟网卡收包 → 识别 UDP/TCP 53 的 DNS 查询
  域名匹配引擎（屏蔽列表编译为基数树/Set，毫秒级查询）
  命中黑名单 → 回 0.0.0.0；未命中 → 转发上游 DNS 回传
  非 DNS 流量直接放行（不做代理，性能损耗≈0）
```

- 需要 entitlement：`com.apple.developer.networking.networkextension` + `packet-tunnel-provider`（个人开发者账号可申请）。
- 交付标准：代码完整、域名匹配引擎 Swift 单测通过；编译验证留待 Mac。

## 9. 测试策略

1. **选择器引擎（核心）**：JVM 单测跑标准用例集（50+ 例：属性匹配/导航/通配/排除/边界）+ 真实 App 节点树快照离线回放。
2. **Android 服务层**：真机 adb 验证清单（权限引导、5-10 个主流 App 开屏实测、防跳转白名单、误点回归核对日志）。
3. **订阅解析回归**：GKD 官方订阅真实数据（上万条规则）解析，输出覆盖率报告（成功/跳过/失败计数）。
4. **TS 引擎一致性**：Node 环境跑同一套标准用例集，与 Kotlin 引擎结果一致。
5. **iOS 域名引擎**：Swift 单测（基数树构建/查询/格式导入）。

## 10. 分发方式

- **Android**：GitHub Releases 发正式签名 APK（README 附校验值）。
- **HarmonyOS NEXT**：侧载 HAP + 安装教程（华为市场对无障碍类工具审核极严，不上架）。
- **iOS**：源码分发，README 写清 Mac 编译与 entitlement 申请步骤。
- **隐私声明**：无障碍数据仅本地内存处理、不上传、不联网采集；订阅 URL 用户主动添加才请求网络。

## 11. 工程顺序

1. Android 端全量（引擎 → 服务 → UI → 真机验证）——唯一可即时验证端
2. selector-spec.md 与标准用例集先行（Android 引擎开发的同时产出）
3. Harmony TS 引擎 + NEXT 工程（复用订阅与规范）
4. iOS DNS 过滤（独立，无依赖）
5. 下期：VisionMatcher（YOLO 兜底，数据标注 + 三端推理集成）
