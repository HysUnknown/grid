# 分册 06：真机验证（Task 16）

前置：Task 1-15 全部完成，`app-debug.apk` 可构建。设备已开启 USB 调试并连接电脑。

---

### Task 16: 真机验证清单

**Files:** 无新增（验证任务，结果记录到 PR 描述或发布说明）

- [ ] **Step 1: 安装并开启服务**

```bash
adb devices                                              # 确认设备在线
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n cn.hys159x.grid.app/.MainActivity  # 拉起 App，确认首页显示规则数
adb shell settings put secure enabled_accessibility_services cn.hys159x.grid.app/cn.hys159x.grid.app.service.GridAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

Expected: App 首页显示"服务运行中"；`adb shell settings get secure enabled_accessibility_services` 输出包含 `cn.hys159x.grid.app`。

- [ ] **Step 2: 验证订阅拉取**

打开 App 观察"规则：N 条 · 订阅 vX"（或 logcat 过滤）：

```bash
adb logcat -d | grep -i "grid"
```

Expected: 数分钟内规则数 > 1000（首次需网络）。若无：手机浏览器直接访问 `https://fastly.jsdelivr.net/npm/@gkd-kit/subscription` 排查网络，确认订阅 JSON5 可达后重启 App。

- [ ] **Step 3: 开屏广告实测（5 个 App）**

对以下 App 逐个：息屏 10 秒 → 亮屏冷启动 → 观察开屏是否被自动跳过：

| App（包名） | 预期 |
|---|---|
| 知乎 com.zhihu.android | 开屏 1-3 秒内自动跳过 |
| 微信读书 com.weread.e reader | 同上 |
| 淘宝 com.taobao.taobao | 同上（规则命中情况以订阅为准） |
| 京东 com.jingdong.app.mall | 同上 |
| 哔哩哔哩 tv.danmaku.bili | 同上 |

同时核对日志：打开 App"拦截日志"页，每条记录的包名/动作与实际行为一致。

- [ ] **Step 4: 弹窗与误点回归**

1. 任选 2 个 App 触发"更新提醒/评价弹窗"场景，观察是否自动关闭。
2. 正常使用 10 分钟（浏览信息流、打开设置页），日志页**不应出现**与广告无关的误点记录；若出现，记录包名+界面截图，在 GKD 订阅库对应规则处反馈或临时关闭该 App 规则组。

- [ ] **Step 5: 性能与稳定性**

```bash
adb shell dumpsys battery                                  # 对比开启前后耗电（30 分钟差值 < 2%）
adb logcat -d -s AndroidRuntime:E | grep -i grid           # 无崩溃
```

Expected: 无 ANR/崩溃；日常使用无可感知卡顿（匹配 200ms 预算内完成）。

- [ ] **Step 6: 服务存活（ROM 保活）**

锁屏 30 分钟 → 亮屏冷启动任一 App：

Expected: 拦截仍生效。若失效：按 App 内引导开启自启动/关闭电池优化后复测；华为机型需"启动管理→手动管理"三开关全开。

- [ ] **Step 7: 验收汇总与 Commit**

全部通过后，把实测结果表（App × 结果 × 备注）追加到 `README.md` 的"实测记录"章节并提交：

```bash
cd D:/dev/app
git add README.md
git commit -m "docs: Task16 真机验证记录"
```

**发版前必办（本计划范围外，写入 issue）：** Release 签名配置（signingConfigs + `keystore.properties`）、GitHub Releases 发布、校验值（sha256sum）附注。
