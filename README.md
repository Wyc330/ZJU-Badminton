# 抢场地 · 浙大体艺羽毛球场地自动预约（0.9.0alpha5）

<sub>项目代号：**π = 3.14** · 仓库：<https://github.com/Wyc330/ZJU-Badminton> · 协议：[MIT](LICENSE)</sub>

📦 **下载 APK**：[Releases · 0.9.0alpha5](https://github.com/Wyc330/ZJU-Badminton/releases/latest) —— `app-0.9.0alpha5-debug.apk`

Android 原生工程（Kotlin），用无障碍服务在「浙大体艺」里自动完成羽毛球场地预约；
到点由精确闹钟唤醒，全自动点完流程，最后停在「提交订单」之后（按需求：不支付）。

**0.9.0alpha5 是按 2026-10-19 的真机截图 + 真机 dump + 放号规则写的**：流程顺序、按钮文案、日期切换方式、
放号节奏、时段表识别方式全部按实测结果。单测 46 个全过，APK 可直接装。

---

## 一、真机截图纠正了原描述里的 4 处（重要）

| 原描述 | 实测界面 | 影响 |
|---|---|---|
| 第 11 步「点列表最上面的**通报信息**条目」 | 实际是「**同伴信息**( 最少1人最多7人 )」页，勾同伴后弹「**选择同伴XXX**」对话框，要求「**5位数字同伴码**」 | v1.1 已按「勾第一个同伴 → 填同伴码 → 确定」实现 |
| 第 5 步「打开日期入口，选择日期」 | 实际是**左右箭头 + 中间日期框**（`< 2026-10-19 📅 > ↻`），点日期框才弹日历 | 改成用箭头切日期，不再解析日期格 |
| 预约窗口 | 日历里只有**今天 + 2 天**可点（19 号时 22 号起是灰的） | `maxDaysAhead = 2`；窗口里没有周五/六/日时本次不抢 |
| 首页 → 场馆预约 | 中间还有一层**六宫格页**（场馆预约/艺术中心/预约记录/套餐中心/同伴管理/培训课程） | 流程加了这一步，并且页面标题与图标同名，会「依次点击直到界面变化」 |
| 放号规则（你补充的） | 所有场地只能约**今天/明天/后天**；每天 **09:00 刷新**，9 点起才放出「后天」的场 | 默认触发时间改成 **08:59:45**；流程提前就位、09:00:00 点一次刷新再抢；候选日期按「后天优先」排序；放号后没看到绿格会刷新重试最多 20 秒 |
| 同伴码（你补充的） | 每次不同，需要每次现场输入 | 到点弹全屏页等你输入（默认 120s）；提前填进主界面则全自动 |

另外两处实测确认：

- **时段表是纯色块，没有文字**：绿=空闲、红=已预约、灰=不开放、黄=未支付、浅蓝=已选择。
  无障碍节点里读不到状态 → v1.1 用 `AccessibilityService.takeScreenshot()` **截图取色**判断。
- **进「场馆服务」会弹「预约须知」**（底部「关闭」，19 秒后自动关）→ 每次页面跳转后都会自动点一次「关闭」。

---

## 二、完整流程（0.9.0alpha5）

```
① 启动/切到浙大体艺（net.crigh.mysport）
② 关弹窗
③ 等首页（九宫格里的「场馆服务」）
④ 点「场馆服务」
⑤ 关弹窗（预约须知）
⑥ 点「场馆预约」（六宫格）
⑦ 关弹窗
⑧ 等「预约场地」页
⑨ 确认校区「紫金港校区」（默认已选中，点一下确保）
⑩ 场馆 →「风雨操场」
⑪ 场地 →「羽毛球场」
⑫ 等到 09:00 放号点（提前就位）→ 到点先点一次「刷新」→ 再选日期
⑬ 日期：窗口(今天/明天/后天)里按「后天优先」排（9 点刚放出来的那天最有货），
    再按「周五 → 周六 → 周日」优先级过滤；用 < / > 箭头移过去，每次点完核对日期框文字
⑭ 时段：截图取色 → 去掉第一个和最后一个时间段 → 从最接近中间的时段开始找绿色(空闲)格；
    周五只保留下午时段；点完再截一次图确认变成"已选择"色；
    若暂时没有绿格（放号延迟几秒），刷新页面重试最多 20 秒
⑮ 勾选「已阅读并同意」（先找 checkable 节点，核对 checked 状态）
⑯ 点「我要预约」→ 关掉预约须知弹窗
⑰ 等「同伴信息」页 → 勾第一个同伴（可用 companionName 指定）→ 弹「5位数字同伴码」→ 填码 → 确定
⑱ 点「提交订单」
⑲ 找成功/失败标志 → 记录日志 + 通知 → 结束（不支付）
```

任何一步失败：写日志（界面全部文字 + 可点节点摘要）→ 自动抓一份节点树 → 停止，不盲目往下点。

---

## 三、安装后要开的权限（MIUI / HyperOS）

0. **触发时间**：默认 **08:59:45**（9:00 放号，提前十几秒就位）。主界面可改，建议 08:59:40~08:59:50。\n1. **无障碍服务**：设置 → 无障碍 → 已下载的服务 → 「抢场地 · 自动预约助手」→ 开启
   （HyperOS 还会问「后台弹出界面」，一起允许）。
2. **精确闹钟**：设置 → 应用 → 抢场地 → 闹钟和提醒 → 允许（Android 12+）。
3. **通知权限**：允许。
4. **显示在其他应用上层**：设置 → 应用 → 抢场地 → 显示在其他应用上层 → 允许。
   ⚠️ Android 10+ 后台拉起浙大体艺必须靠这个权限。
5. **电池优化**：设为「无限制 / 不限制」。
6. **MIUI 自启动**：应用设置 → 授权管理 → 自启动 → 允许「抢场地」；最近任务里给它加锁。

> 抢场地时手机请保持**亮屏解锁**（无障碍不能替你输锁屏密码）。

---

## 四、先做一次「抓取」，确认 H5 的节点结构（关键）

普通点「抓取当前界面节点树」在**浙大体艺处于前台时**才有意义 —— 无障碍只能看到当前可见的窗口。
所以用新加的：

1. 手动把浙大体艺点到「预约场地」页（能看到日期行、校区/场馆、时段表）。
2. 切回「抢场地」→ 点 **「延时 5 秒抓取」** → 立刻切回浙大体艺 → 5 秒后自动抓取。
3. 手机上会弹「界面抓取完成」通知；回到「抢场地」点「分享最近一次抓取」，把 JSON/TXT 发出来。

抓完能看到：日期框/箭头、时段行（`07:30-08:30`）、列标题（`1号/2号`）的**真实节点与 bounds**，
以及「同伴信息」页的勾选框样式 —— 有这些就能把最后的不确定性消掉。

---

## 五、配置

第一次启动会把默认配置写到：

```
/sdcard/Android/data/com.zju.courtgrab/files/config/grab-config.json
```

改完在 App 里点「重新载入配置文件」立即生效，不用重装。
示例见 `docs/grab-config.sample.json`（只写要改的键，其余走默认）。

最可能要动的键：

| 键 | 作用 | 默认（实测） |
|---|---|---|
| `entryService` / `entryBooking` | 首页入口 / 六宫格入口 | 含「场馆服务」/「场馆预约」 |
| `venueFengYu` / `venueBadminton` | 场馆 / 场地 | 含「风雨操场」/「羽毛球场」 |
| `dateCfg.weekdayPriority` | 日期优先级 | FRIDAY → SATURDAY → SUNDAY |
| `dateCfg.maxDaysAhead` | 预约窗口天数 | 2（今天/明天/后天） |
| `releaseHour/Minute/Second` | 放号时间 | 9:00:00 |
| `waitForRelease` | 是否等到放号点再抢 | true |
| `refreshTapRatio` | 「刷新」按钮坐标比例 | 2.5（相对日期框高度） |
| `slotRetryMs` | 放号后无绿格的重试时长 | 20000 |
| `preferFreshDates` | 是否优先后天（刚放号） | true |
| `dateCfg.fridayAfternoonOnly` | 周五只抢下午 | true |
| `slotGridCfg.availableColors` | 空闲(绿)色的候选值 | `#67C23A` 等 5 个绿 |
| `slotGridCfg.bookedColors` | 已预约(红) | `#FE4B37`（截图实测） |
| `slotGridCfg.skipFirstRows/skipLastRows` | 排除首尾时间段 | 1 / 1 |
| `companionName` | 勾哪个同伴（留空=第一个） | 空 |
| `agreeCheckbox` / `btnBookNow` / `btnSubmitOrder` | 勾选 / 我要预约 / 提交订单 | 实测文案 |

---

## 六、编译与测试（本机已验证）

```bat
cd /d D:\抢场地\ZjuCourtGrab
scripts\build.cmd     :: 编译 → app\build\outputs\apk\debug\app-debug.apk
scripts\test.cmd      :: 46 个 JVM 单测（日期窗口/时段取色/控件匹配/配置 JSON）
```

工具链（不占 C 盘）：`D:\抢场地\_tools` 下 JDK17 + Android SDK 34 + Gradle 8.7。

环境坑（已绕过）：

1. AGP 8.x 不接受非 ASCII 工程路径 → `gradle.properties` 里 `android.overridePathCheck=true`。
2. `*.properties` 是 ISO-8859-1 → `local.properties` 里中文路径写成 `\uXXXX`。
3. `gradle test` 在中文路径下 worker 会 `ClassNotFoundException` → `scripts\test.cmd` 用 ASCII junction 绕过。

---

## 七、已知限制

1. **时段取色依赖截图能力**（Android 11+ 无障碍截图）。若目标页 `FLAG_SECURE` 会失败（日志会写明）。
   颜色阈值可按截图实测值在 `slotGridCfg` 里调。
2. **不能绕过锁屏密码**，到点会亮屏 + 弹输入页，密码要你自己按。
3. **同伴码每次不同**：到点弹全屏页等你输入（默认 120s）；提前填进主界面则全自动。\n   如果你能提前拿到码，建议在触发前 1 分钟填好，抢的时候就不用停下来输入。
4. **抢场地是竞态**：精确到秒的闹钟 + 前台服务保活 + 立刻定位，但能否抢到还取决于放号时间/网络/账号。
5. 提交后**不支付**，流程结束；请到「我的预约」确认订单。

---

## 八、工程结构

```
ZjuCourtGrab/
├─ app/src/main/java/com/zju/courtgrab/
│  ├─ MainActivity.kt                     主界面（权限/时间/邀请码/试跑/抓取/日志）
│  ├─ ui/CodeRequestActivity.kt           到点全屏输入 5 位同伴码
│  ├─ a11y/VenueAccessibilityService.kt   无障碍服务（承载流程、延时抓取）
│  ├─ a11y/A11yOps.kt                     抓节点/等待/点击(含坐标兜底)/输入
│  ├─ a11y/ScreenCap.kt                   ★ 截图 + 像素取色（时段表识别）
│  ├─ a11y/Dump.kt                        节点树落盘 JSON+TXT
│  ├─ flow/BookingFlow.kt                 ★ 主流程（按真机界面重写）
│  ├─ flow/InviteCodeGate.kt              同伴码闸门（流程挂起等输入）
│  ├─ alarm/*.kt                          精确闹钟、到点处理、开机重排
│  ├─ service/GrabForegroundService.kt    抢场地期间前台服务保活
│  ├─ core/GrabConfig.kt                  ★ 全部界面事实 + 实测默认值
│  ├─ core/SlotGrid.kt                    ★ 颜色→状态分类 + 中间优先选格
│  ├─ core/DatePlanner.kt                 ★ 日期窗口/优先级（纯函数）
│  ├─ core/ConfigJson.kt / ConfigRepo.kt  配置 JSON 读写 + 热重载
│  ├─ core/LocatorMatcher.kt              控件匹配（纯函数）
│  ├─ core/Model.kt / Prefs.kt / Logs.kt  模型 / 加密存储 / 日志
│  └─ util/{Notify,BgLaunch,Perms}.kt     通知 / 拉起目标App / 权限跳转
├─ app/src/test/java/com/zju/courtgrab/   46 个单元测试
├─ scripts/{build,test}.cmd
└─ docs/grab-config.sample.json
```

---

## 九、开源协议

本项目采用 [MIT License](LICENSE)。

Copyright (c) 2026 RTXwcz

> 仅供个人学习与自动化研究使用。请遵守学校场馆预约规则，不要用于恶意抢场、囤场或转卖；
> 由使用行为产生的一切后果由使用者自行承担。
