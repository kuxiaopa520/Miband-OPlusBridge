# 小米手环 10 与 OPPO 健康共存

本 Fork 基于上游提交 `1ccdda52ec959587c698926e79b73236103c235f`。当前版本 `1.8.7-coexist.2` 增加 OPPO 健康闪退日志导出；共存实现与 `coexist.1` 相同，闪退根因仍待设备崩溃记录确认。

## 能做到什么

新增实验性共存模式，默认开启。小米运动健康保持启用，继续负责手环蓝牙连接、通知、天气、表盘、固件升级、NFC 等设备功能。桥接监听其正常接收的健康文件，将文件复制到本机，再复用上游解析、去重和 OPPO 健康写入流程。

```text
小米手环 10 → 小米运动健康 → 桥接本机副本 → OPPO 健康
                 │
                 └─ 继续负责蓝牙连接与设备设置
```

两个 App 可以保留并打开使用；本实现采用一个 App 管理连接、另一个 App 读取同步副本的方式。它没有让两个 App 同时建立独立的手环控制连接，也没有实现双向数据同步。

| 内容 | 本版行为与边界 |
| --- | --- |
| 小米运动健康 | 共存流程不执行强停或禁用小米 App 的命令；仍使用其原有同步流程 |
| 步数、心率、静息心率、血氧、压力、睡眠等 | 支持上游解析器和 OPPO 写入器能识别的文件格式；具体手环固件和 OPPO 展示效果待真机验证 |
| 运动记录 | 复用上游运动文件解析；不同运动类型和详细轨迹不保证全部覆盖 |
| 连接状态、电量 | 观察小米 App 的连接事件和电量响应；并非桥接另建连接读取 |
| 小米 App 内已有历史 | 不扫描和搬运整套小米健康数据库；只能复制启用后再次接收到的手环文件 |
| OPPO → 小米 | 未实现；在 OPPO 手工添加或修改的数据不会反向进入小米 App |
| 表盘、OTA、NFC、天气、手环通知 | 继续在小米 App 中操作；OPPO 的相关设备控制页面不代表这些控制已接通 |
| 云端数据 | 本次没有验证任何账号的云端上传、下载或两家云端互通 |

原有独占接管模式仍保留。关闭共存开关、按提示添加并接管后，会执行上游禁用小米 App 的流程；有共存需求时保持开关开启。

## 本次 APK 适配范围

| App | 提供的版本 | SHA-256 |
| --- | --- | --- |
| OPPO 健康 | `6.7.17_a4b586b_260821` / `6071700` | `3bdecd3c3dd84fc1094f2cd6a02a575cbb06df00c764f4c5fe299d0394c1cb30` |
| 小米运动健康 | `3.59.1` / `359001` | `46100649d47000e8886d94cfbb34c18706e492a5d3aaa7f34cd4785841ffc254` |

用户设备为小米手环 10、一加 Ace 6、Android 17，已 Root 并安装 Xposed 框架。本次电脑没有通过 ADB 连接到该手机，因此以上是适配目标，不能作为已完成真机测试的声明。

提供的 OPPO 安装包版本号低于上游已列出的 6.9.40。本次针对所提供的 6.7.17 构建核对接口，不声称这是全网最新版本。核心数据 API 和数据模型接口可以沿用，新增精确版本识别和可重复的静态核对记录。

## 安装与使用

1. 先在小米运动健康中登录并正常绑定小米手环 10，确认它可以连接和同步。保留系统蓝牙配对。
2. 安装本 Fork 构建的模块 APK。模块沿用上游包名，但使用本地 Fork 签名，不能直接覆盖使用另一签名的上游模块；若已安装上游模块，应先备份所需模块数据再换装。两个健康 App 本身保持原版即可。
3. Xposed 框架须支持 **libxposed API 102**，例如支持该 API 的 Vector；仅有较旧的 LSPosed 并不足以证明能加载本模块。作用域勾选 `com.mi.health`、`com.heytap.health`、`com.heytap.mydevices`。共存期间始终保留小米运动健康的作用域。`com.coloros.alarmclock` 用于上游时钟功能，共存数据复制本身不依赖它。
4. 首次启用作用域后重启手机，或手动强停并重新打开相关 App，使 hook 生效。在 Root 管理器中授权「小米手环桥接」。
5. 打开桥接：检查 Root → 导入绑定 → 保持「与小米运动健康共存（实验）」开启 → 添加到健康。若以前由模块独占接管，进入此流程会先结束桥接直连，并恢复由模块禁用的小米 App。
6. 打开 OPPO 健康，完成登录并进入首页，让桥接取得当前 OPPO 账号；然后在小米运动健康中连接并同步手环。界面打开或蓝牙重新连接本身不保证手环会重传已被官方 App 消费的历史文件。
7. 打开 OPPO 健康检查对应日期的数据。日常保持小米运动健康的后台连接、蓝牙权限和其原有通知权限。共存模式无需桥接另取一条蓝牙连接，也无需为数据复制额外开启 OPPO 的通知使用权。

OPPO 健康里的数据会受实际同步时机、账号状态和已支持的文件类型影响。建议先核对一次步数、一次新测心率和次日睡眠，再决定是否作为日常使用方案。

## 实现依据

- 小米 `classes10.dex`：`com.xiaomi.fit.fitness.device.mi.receive.FitnessReceiverHandler.handleDataInternal(String, int, byte[])` 在类型 `102` 时将带设备 DID 的原始分片交给 `FitnessDataReceiver.onReceive(byte[])`。
- 生态设备分支的接收方法名为 `handleData(String, int, byte[])`，分别安装 hook。
- 官方接收器的分片头为两个小端 16 位数：总片数和片序号。完整文件包含 7 字节文件 ID 和 CRC32，和上游 `BandHistoryParser.acceptFragment` 的输入一致。
- 在官方接收函数执行前复制分片；最后一片在桥接完成本机持久化后返回。原始参数、返回值和官方确认行为保留。镜像不可用时允许官方函数继续运行，不阻断其同步。
- 小米 `classes3.dex`：从 `WearApiCall` 获取选中设备的 DID、地址、固件和连接事件，观察电量回包；不发送新的蓝牙控制命令。
- 桥接 Provider 验证来访 UID 属于 `com.mi.health`，只接受已登记设备的 DID/地址；开机后用户存储首次解锁前暂停，独占模式或移除登记后停止接收。每个分片最多 128 KiB，完整文件复用上游 1 MiB 限制。
- OPPO 的账号、数据 API、插入/读取选项、点数据模型、步数/睡眠/运动模型和设备页入口通过 DEX 签名核对。`SyncObserver.dispose()` 由父类提供，按实际继承链核对。
- 复用已有的文件归档、记录 ID、版本去重和原生数据读回确认；没有改写两家原始 APK，也没有导出用户账号凭据。

异常或进程退出可能令正在接收的文件缺少分片，新文件第一片会重置解析器。镜像不可用时小米仍可能完成确认，因此无法保证该文件以后会自动重传。这也是本版保留实验标记的原因之一。

## 验证

静态检查脚本只读取原 APK，不修改 APK。需要 Python 3.11 或更新版本：

```powershell
python scripts/check_host_apks.py --oppo 'F:/Downloads/OPPO 健康.apk' --mi 'F:/Downloads/小米运动健康.apk' --output analysis/reports/host-contracts.json
```

检查清单见 `compat/coexist-hosts.json`，实际静态结果见 `compat/coexist-verification.json`。这些文件中的 `runtimeVerified` 保持 `false`；类和方法存在不等于实际连接或写入成功。

共存首版核对结果：OPPO 健康 22 个类、143 个方法契约通过，小米运动健康 6 个类、15 个方法契约通过。首版全部 360 项 JVM 单元测试通过，上游复用目标检查通过。完整 `lintRelease` 报出 24 个错误和 45 个警告；24 个错误的源码位置均可在上游原代码中找到，主要为权限标注问题，本次未顺便修改。普通 Release 构建及其 `lintVitalRelease` 可完成；完整 Lint 不能记为通过。`coexist.2` 另运行日志导出、Shell 引号和有界读取相关的 10 项测试，全部通过；OPPO 的实际闪退仍待设备堆栈定位。

构建使用上游 Gradle 项目和签名变量：`OPLUSBAND_KEYSTORE`、`OPLUSBAND_STORE_PASSWORD`、`OPLUSBAND_KEY_ALIAS`、`OPLUSBAND_KEY_PASSWORD`。Fork 的签名私钥只保存在本机忽略目录，未提交到 GitHub。

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

单独生成安装包可运行 `:app:testDebugUnitTest :app:assembleRelease`；此命令仍运行构建自带的 `lintVitalRelease`，但不代表完整 `lintRelease` 通过。

真机验收还需要确认：框架实际加载共存 hook、小米保持启用和连接、OPPO 账号下真实新增步数/心率/睡眠、重启后的持续同步以及设备页展示。调试日志中的 `MI_MIRROR_HOOK_INSTALLED`、`mirror file PARSED` 可帮助定位接收阶段；OPPO 展示仍须实际核对。

## OPPO 健康点开闪退时

`mirror file PARSED` 只能说明健康文件到达桥接，不能证明 OPPO 页面正常启动或已完成数据写入。旧导出里的 `live-status` 是独占连接服务最后保存的状态，也可能早于当前共存会话。

`coexist.2` 的日志头会重新读取当前所有权、登记状态和模块版本；旧服务状态标为 `last-direct-status`。导出末尾新增 `OPPO Health Android crash buffer`，通过已授予的 Root 权限读取 OPPO 健康 UID 的 Android crash buffer，最多 240 行、128 KiB。读取有 8 秒超时，临时文件保存在模块私有缓存并在读取后删除。导出不会清除崩溃缓冲区、健康 App 数据或账号。

收集方法：覆盖安装 `coexist.2` → 再打开 OPPO 健康复现闪退 → 回到桥接的「帮助 → 发送调试日志」。如果末尾显示 `HEALTH_CRASH_BUFFER_EMPTY`，说明这次读取没有取得崩溃行；`HEALTH_CRASH_LOG_UNAVAILABLE` 则表示日志读取未完成。此时需要框架日志或 ADB 崩溃记录，不能仅根据镜像接收日志判定原因。
