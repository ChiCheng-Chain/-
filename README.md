# 平板补全计划

按照使用习惯适配平板应用。首期面向 OriginOS 7，支持哔哩哔哩与酷安：横屏左侧保留首页，右侧打开搜索，点开视频或帖子后在右侧展示内容。

**当前是实验项目 `0.4.1-alpha`，不是通用适配工具或稳定发行版。** 本仓库整理自一台 iQOO 平板上已验证的 v0.4 实验；整理后的 APK 与规则工具尚待真机回归。模块更新期间曾发生设备卡死，原因尚未确定。不会自动安装、提权或重启设备。

## 两种能力

| 能力 | 实现方式 | 完整重启、临时 root 失效之后 |
| --- | --- | --- |
| 左首页、右搜索的双栏规则 | 修改 OriginOS 原生平行视窗配置 | 已写入的配置可继续生效；系统更新或云端规则刷新可能覆盖 |
| 搜索页抑制自动弹输入法 | LSP / Vector 注入目标应用 | 需要重新激活注入框架 |
| 酷安启动搜索的补充逻辑 | 模块在原生平行视窗模式中启动搜索页 | 需要注入框架；未加载时可能不自动打开右侧搜索 |

B 站首页在此前验证的分栏宽度下每行显示两个视频；模块没有强制改写视频卡片列数。系统分隔条可以拖动，实际比例由系统和用户操作决定。

这是**一个 APK 内含多应用规则**的起点。当前通过 `rules/apps.json` 维护每个应用的规则，通过 LSP / Vector 作用域分别控制注入；尚未实现图形化管理界面、运行时规则编辑或一键软重启。

## 目录

```text
module/             Android 模块源码、清单、Xposed 入口
rules/apps.json     每个应用的首页、搜索页、比例和功能选项
tools/build.py      下载并校验固定版本依赖，编译 APK 与重载辅助程序
tools/native_rules.py  原生规则查看、备份、应用和恢复
tools/ReloadRules.java  一次性的 OriginOS 配置重载请求
tests/              规则保留、转义、读写失败和恢复保护测试
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

GitHub Actions 运行相同测试和构建，产物在对应运行的 Artifacts 中下载，不自动创建 Release。正式发布前需确定独立签名密钥；不要为签名不匹配而卸载正在使用的模块。

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

可用 `--app all` 一次选择两款应用。ADB 不在 PATH 时，在子命令前指定 `--adb /path/to/adb`；多设备时加 `--serial DEVICE_SERIAL`。例如：

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

安装 APK 后，在已激活的 LSP / Vector 中启用「平板补全计划·实验版」，作用域仅选择：

- `tv.danmaku.bili`（哔哩哔哩）
- `com.coolapk.market`（酷安）

然后结束并重新打开目标应用。此版无需选择 Android 系统、SystemUI、桌面或 `system_server`。模块本身不会获取 root，也不会自动激活 Zygisk / Vector。

目标行为是进入搜索、旋转屏幕或返回页面时不自动弹键盘，点击输入框后才弹出。**已验证范围与尚未覆盖的路径请看[兼容性和验证记录](docs/compatibility.md)**，不要把目标行为视为所有场景均已通过测试。

## 当前边界

- 未包含全宽首页实验、系统状态栏偏移或自动软重启功能。
- 临时 root、Zygisk 和 Vector 的安装及激活由外部工具负责。
- 没有修改第三方 APK，没有解锁付费内容或实现去广告功能。
- 许可证尚未指定。仓库仅含本项目自行编写的代码和规则增量，未引入其他适配项目的源码或规则库。

更多说明：[工作原理](docs/architecture.md) · [兼容性](docs/compatibility.md) · [恢复与排查](docs/recovery.md) · [依赖来源](docs/dependencies.md)
