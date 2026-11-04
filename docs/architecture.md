# 工作原理

## 应用规则

`rules/apps.json` 是应用策略的单一来源。`package`、`homeActivity`、`searchActivity` 定位应用与页面。`defaultRatio`、`draggable` 控制原生平行视窗规则。`manualKeyboard`、`searchLaunchFallback`、`blockAds` 分别控制搜索页键盘门控、搜索启动补充和广告拦截。当前四款应用均参与原生双栏配置，旅法师营地同时启用广告拦截。

构建时生成 `AppRules.java` 和 `META-INF/xposed/scope.list`，把规则编入同一个 APK。`staticScope=true` 将框架可选作用域限制在这些应用内，用户仍可只启用其中一个。新增适配需要确定目标应用的实际页面和导航行为、增加规则、重新构建并验证；填写包名不能保证适配成功。

## 管理页与持久化

`SettingsActivity` 提供宽屏左右导航、窄屏顶部导航、比例示意及明暗主题。用户显式保存后，`SettingsApp` 使用同步 `SharedPreferences.commit()` 持久化本机选择，后台单线程串行执行框架同步和系统比例应用。任务由 Application 持有，Activity 因旋转重建时可以继续显示执行状态。草稿与已提交值分开显示。

广告选择使用官方 libxposed service / interface 102.0.0 客户端，清单注册官方 `XposedProvider`。管理页以 `settings` 为组名、`包名.ads` 为键，将选择通过框架共享偏好提交；本机保留镜像，断连时显示待同步。`Entry.onPackageReady` 在注册广告钩子前通过 API 的 `getRemotePreferences("settings")` 读取，关闭时跳过广告钩子注册。旧数据没有键时沿用规则默认值，读取异常时放行广告请求并记录错误。当前进程的钩子保持到进程结束，新选择由下一次启动读取。

比例选择通过 `su` 启动 APK 内的 `NativeRatio` 一次性入口。它校验主用户 0、系统平行视窗开关及既有首页/搜索附属关系，保存兼容 CLI 恢复格式的独立备份并 fsync，再只修改 PAD 比例与 `draggable=false`。后者用于避免用户拖动位置覆盖设置页所选默认值。未知字段及其他设备类型比例保留，写入前再次读取以发现外部修改，写入和重载后都按 JSON 结构核对，失败时在当前规则仍与本次修改一致的条件下尝试回滚。界面显式显示待应用、失败或读回成功。

一次性入口使用文件锁排除本应用遗留辅助进程的并发应用，系统更新请求同时携带预期的旧配置作为条件，条件不符时报告失败。系统设置调用最多等待 30 秒，超时后停止当前管理页进程内后续系统写入。UI 的成功状态表示写入及读回完成，实际分栏和重启目标应用后的行为仍由用户验证。

## OriginOS 原生配置

系统规则位于 `com.vivo.smartmultiwindow.multiwindow` Provider 的 `p_client` 表，功能名为 `multi-landscape`。独立的 `app_switch` 表控制用户是否开启平行视窗。工具只更新前者的 `config` 字段，不更改其他应用，也不直接写底层数据库。

工具用设备当前配置作为基础：设置首页与附属搜索页，允许首页向内容页面跳转，将搜索页移出强制全屏列表，设置 PAD 的默认分栏比例并允许拖动。保留其他厂商字段与页面关系。数据写入后查询回读，而不是仅信任 `content` 命令退出码。

OriginOS 7 上观察到的重载入口为向 `android` 包发送 `com.vivo.smartmultiwindow.multilandscape.update_client_config`，`params` Bundle 内含 `client_names` 与 `mode=1`。`ReloadRules` 通过 root 下的 `app_process` 取得系统上下文，发送一次请求后退出。它不常驻、不运行轮询，也不重启系统。

接口属于厂商实现，系统升级后可能改变。CLI 转义与重载 Java 辅助程序在本次仓库整理中进行了工程化，仍需要实机回归。

## 应用内注入

`Entry` 继承现代 `XposedModule`，在 `onModuleLoaded` 记录进程名，在 `onPackageReady` 只为规则表中的应用主进程注册一次 Hook。最低与目标 API 都是 102，使用 `hook(...).setId(...).setExceptionMode(PROTECTIVE).intercept(...)`，没有旧 Xposed 接口或兼容桥。

搜索 Activity 的键盘状态按生命周期重新锁定：创建、恢复、暂停、配置变化或失去窗口焦点时抑制自动弹键盘，编辑框触摸后放行。通过窗口软键盘策略、输入法调用和编辑框焦点行为配合实现。API 102 参数列表不可变，改参时复制为数组交给 `chain.proceed(args)`；拦截调用时直接返回符合原方法签名的结果。`HookFlow` 的桌面测试覆盖放行、短路、参数保留和原始异常传播，但不替代 ART 和 Android 生命周期验证。

状态和 Hook 句柄属于模块入口实例；注册失败时撤销已经注册的 Hook，避免留下不完整的键盘策略。此版不启用热重载，仍需重开应用加载更新。

控件无障碍点击与无障碍焦点动作也会放行，保留辅助输入可用性；因此“仅手动点击”是交互目标，不是对所有系统自动化路径的绝对保证。当前钩子不覆盖评论编辑页等所有输入场景。

酷安的原生附属搜索有时未自动启动，模块保留了一次性的启动补充逻辑，仅在首页首次创建、无恢复状态且窗口模式为 OriginOS `86` 时触发。它与厂商自动启动逻辑并存，需要继续验证不会形成重复搜索页面。

## 生命周期

原生规则保存在系统侧，与注入进程是否存活分离。完整重启后临时 root 消失，已经保存的双栏规则仍可能运行；输入法钩子和酷安启动补充逻辑需要重新激活框架。只关闭电脑或拔掉数据线不会使已运行的模块自动失效。
