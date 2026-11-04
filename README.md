# 平板补全计划

按照使用习惯适配平板应用。首期面向 OriginOS 7，为哔哩哔哩、酷安、网易大神和旅法师营地配置横屏左首页、右搜索的双栏规则，并抑制搜索页自动弹出输入法。旅法师营地另提供应用内广告拦截（开屏、插屏、信息流、横幅）。

**当前是实验项目 `0.8.0-alpha`，使用现代 libxposed API 102。** 本仓库整理自一台 iQOO 平板上已验证的 v0.4 实验。用户于 2026-11-04 确认 0.7.0 的网易大神双栏及抑制自动弹输入法、旅法师营地双栏及去广告已通过真机测试。0.8.0 的管理界面、比例应用和广告开关尚待真机验证，详细范围见兼容性记录。模块更新期间曾发生设备卡死，原因尚未确定。

## 功能

| 能力 | 实现方式 | 完整重启、临时 root 失效之后 |
| --- | --- | --- |
| 左首页、右搜索的双栏规则 | 修改 OriginOS 原生平行视窗配置 | 已写入的配置可继续生效；系统更新或云端规则刷新可能覆盖 |
| 搜索页抑制自动弹输入法 | LSP / Vector 注入目标应用 | 需要重新激活注入框架 |
| 酷安启动搜索的补充逻辑 | 模块在原生平行视窗模式中启动搜索页 | 需要注入框架；未加载时可能不自动打开右侧搜索 |
| 旅法师营地广告拦截 | LSP / Vector 注入目标应用 | 需要重新激活注入框架 |

B 站首页在此前验证的分栏宽度下每行显示两个视频；模块没有强制改写视频卡片列数。CLI 规则默认允许拖动。通过管理页应用比例时会设置 `draggable=false`，将比例交给管理页调整，避免系统拖动位置覆盖所选默认比例；实际显示仍需在目标系统验证。

这是**一个 APK 内含多应用规则**的起点。通过 `rules/apps.json` 维护基础规则，通过 LSP / Vector 作用域分别控制注入。APK 提供自适应明暗主题的原生管理页，可按应用保存比例，并选择是否启用已有广告拦截。

## 管理界面与保存

安装后从桌面打开「平板补全计划」。宽屏左侧选应用，右侧配置；窄屏使用顶部应用导航。

- **比例**可调整左栏 30%–70%，提供 40/60、50/50、60/40 快捷选项。点击「应用并保存比例」后请求本应用的 root 权限，备份所选应用的当前规则，仅修改 PAD 比例及拖动策略，写入后读回核对并请求系统重载。需要先完成现有原生双栏适配，并开启系统平行视窗开关。重开应用后检查实际效果。
- **去广告**目前仅旅法师营地可选。点击「保存去广告设置」将选择同步提交到框架的 `settings` 共享配置，目标应用新进程启动时读取。框架未连接或提交失败时保留本机选择，并显示待同步状态。下次打开管理页且框架连接成功时会重新同步。保存后结束并重开目标应用。
- 管理页重新打开时回显按应用保存的值。比例应用失败会显示待应用，保存结果与真机效果分别标示。保存后的系统规则独立于管理页进程存活，广告拦截仍依赖已激活的框架和正确作用域。
- 比例备份位于设备本应用私有目录 `files/native-backups/`，采用 CLI 恢复工具兼容的格式。操作超时后停止本次管理页进程内后续系统写入，由用户恢复设备响应并核对结果。

## 目录

```text
module/             Android 模块源码、清单、Xposed 入口
rules/apps.json     每个应用的首页、搜索页、比例和功能选项
tools/build.py      下载并校验固定版本依赖，编译 APK 与重载辅助程序
tools/native_rules.py  原生规则查看、备份、应用和恢复
tools/ReloadRules.java  一次性的 OriginOS 配置重载请求
tools/verify_apk.py  检查 API 102 入口、作用域及 DEX 内的依赖
tests/              规则恢复测试与 API 102 调用链检查
docs/               兼容性、工作原理与恢复说明
```

## 构建

需要 **Python 3.10+、JDK 21** 和网络。设置 `JAVA_HOME` 指向 JDK。当前沿用已验证实验的 javac → D8 → Apktool 构建方式，不依赖本机绝对路径，也不要求完整 Android SDK。Android 4.1 编译桩只用于编译已有基础 API，不代表支持 Android 4.1；实际设备依赖见兼容性文档。

```sh
python -m unittest discover -s tests -v
python tools/build.py
```

依赖从上游下载，按 `tools/dependencies.json` 的 SHA-256 校验后缓存于 `.cache/`。输出：

- `dist/tablet-completion-debug.apk`：实验模块，使用构建工具的公开调试签名。
- `dist/reload.dex`：仅供原生规则工具调用的一次性重载程序。
- `dist/SHA256SUMS`：产物校验值。

构建时运行 9 项 API 102 调用链检查、3 项广告回调契约检查、5 项广告启动配置读取检查，以及覆盖全部 41 个可选百分比的原生比例检查。APK 检查覆盖新版模块元数据、声明作用域、DEX 入口继承关系、管理页入口、框架服务客户端及其 Binder 依赖，拒绝混入旧 API 引用、框架 API 实现和编译专用 JSON 库。GitHub Actions 运行相同测试和构建，产物在对应运行的 Artifacts 中下载，不自动创建 Release。正式发布前需确定独立签名密钥；不要为签名不匹配而卸载正在使用的模块。

## 原生双栏规则

**已经配置成功的平板无需重复应用。** 以下流程用于连接设备后手动部署；目前仅面向 Android 主用户 0、已验证的 OriginOS 私有接口。不要直接用于其他品牌或未知系统版本。

1. 在系统设置中开启对应应用的平行视窗。
2. 确认 ADB 已授权，设备具有 root。先查看现有配置。
3. 应用时指定一个新的备份文件；程序拒绝覆盖已有备份。

```sh
python tools/native_rules.py inspect --app all
python tools/native_rules.py apply --app bili --backup backups/bili-before.json
python tools/native_rules.py apply --app coolapk --backup backups/coolapk-before.json
```

`--app` 可选 `bili`、`coolapk`、`neteasegl`、`iplaymtg`，`--app all` 会选择全部四款应用。批量应用前需要为所有选中应用开启平行视窗。ADB 不在 PATH 时，在子命令前指定 `--adb /path/to/adb`；多设备时加 `--serial DEVICE_SERIAL`。例如：

```sh
python tools/native_rules.py --adb /path/to/adb inspect --app all
```

工具读取本机原始规则后做小范围修改，不发布或覆盖一份完整的厂商规则库；保留其他页面关系和未知字段。应用前保存备份，写入后重新查询核对，最后请求系统重载。重载请求成功不等于界面已验证，需要重开应用检查。

恢复命令：

```sh
python tools/native_rules.py restore --backup backups/bili-before.json
python tools/native_rules.py restore --backup backups/coolapk-before.json
```

恢复只还原备份中的规则 JSON。系统开关、用户拖动的分隔条位置由系统管理。若当前规则已被其他工具或系统改变，恢复工具拒绝直接覆盖。

## 输入法模块

需要**支持 libxposed API 102 的框架**。此前设备安装的 Vector 2.2 声明支持该 API，当前版本的真机测试记录见[兼容性和验证记录](docs/compatibility.md)。

安装 APK 后，在已激活且兼容的框架中启用「平板补全计划」，作用域仅选择：

- `tv.danmaku.bili`（哔哩哔哩）
- `com.coolapk.market`（酷安）
- `com.gonlan.iplaymtg`（旅法师营地，广告拦截）
- `com.netease.gl`（网易大神，搜索页键盘门控）

然后结束并重新打开目标应用；从旧版升级也需要重开目标应用，以替换进程中旧代码。模块使用 `META-INF/xposed/` 声明入口，最低与目标 API 均为 102，作用域由规则文件生成并限制在已适配应用内。此版不作用于 Android 系统、SystemUI、桌面或 `system_server`。管理页仅在用户应用比例时通过 `su` 请求 root；注入逻辑通过框架运行。Zygisk / Vector 的激活由用户完成。

本次未启用自动热重载（`autoHotReload=false`，保留框架默认拒绝热重载的回调）。支持 API 102 不等于已经实现运行中无缝更新。

目标行为是进入搜索、旋转屏幕或返回页面时不自动弹键盘，点击输入框后才弹出。**已验证范围与尚未覆盖的路径请看[兼容性和验证记录](docs/compatibility.md)**，不要把目标行为视为所有场景均已通过测试。

## 当前边界

- 未包含全宽首页实验、系统状态栏偏移或自动软重启功能。
- 临时 root、Zygisk 和 Vector 的安装及激活由外部工具负责。
- 没有修改第三方 APK，没有解锁付费内容。旅法师营地的广告拦截只阻断本仓库模块进程内注入的广告请求，不承诺覆盖未来版本变化。
- 许可证尚未指定。仓库仅含本项目自行编写的代码和规则增量，未引入其他适配项目的源码或规则库。

更多说明：[工作原理](docs/architecture.md) · [API 102 迁移](docs/api-102-migration.md) · [兼容性](docs/compatibility.md) · [恢复与排查](docs/recovery.md) · [依赖来源](docs/dependencies.md)

开发与设备联调按 [AGENTS.md](AGENTS.md) 中的分工执行：代理开发并交付，用户反馈实际界面效果。
