# API 102 迁移

版本：`0.6.0-alpha`，versionCode 7，保留包名 `dev.tabletcompletion.keyboard` 和原有调试签名方式。

## 接口与打包

| 原实现 | 当前实现 |
| --- | --- |
| `de.robv.android.xposed` API 82 | 官方 `io.github.libxposed:api:102.0.0`，编译时依赖 |
| `IXposedHookLoadPackage.handleLoadPackage` | `XposedModule.onModuleLoaded` / `onPackageReady` |
| `XC_MethodHook` 前后回调、可变参数数组 | `Hooker.intercept(Chain)`、`proceed` 与返回值 |
| `XposedHelpers` 查找成员 | 标准 Java 反射、API 102 HookBuilder |
| `assets/xposed_init` | `META-INF/xposed/java_init.list` |
| 清单 `xposedminversion=82` 等旧标记 | `module.prop` 的最低 / 目标 API 均为 102 |
| 无打包作用域列表 | 从应用规则生成 `scope.list`，`staticScope=true` |

APK 使用标准 `android:description` 字符串资源提供模块说明。现代标记文件在 Apktool 打包后、签名前写入，签名后再次核对，防止 META-INF 在构建链路中丢失。

API AAR 的校验值与上游 102.0.0 发布资产一致；只提取 classes.jar 供编译使用。DEX 检查确认模块入口继承现代 XposedModule，没有旧 API 类型引用，也没有将 libxposed 的框架类打包进模块。

## 行为与边界

`0.8.0-alpha` 增加原生管理页和广告配置读取。APK 打包官方 service / interface 102.0.0 客户端，用于与框架交换配置；注入 API 仍为编译依赖，由框架提供。DEX 检查分别校验服务客户端依赖完整性及框架 API 实现未被打包。管理页通过共享偏好同步设置，更新配置后重开目标应用加载。

- 保留 B 站 / 酷安搜索页键盘门控和酷安原生模式下的搜索启动补充逻辑。
- 只在已适配应用的主进程、首个包就绪时注册一次。
- 参数修改使用副本，未被阻止的调用交给后续调用链，保留原返回值与异常。
- 未识别返回类型的未来输入法重载保持原调用，避免返回不匹配类型。
- 注册失败时撤销本次已注册的 Hook；回调使用框架的 PROTECTIVE 异常模式。
- 不启用自动或手动热重载：保留 `onHotReloading` 默认返回 false，`autoHotReload=false`。此功能还需要处理中途存在的 Activity 状态和投递回调，不能仅打开开关。

旧框架不再支持本模块。升级时先确认 API 102 支持，更新后重开目标应用。最低版本声明不是绕过临时 root 或框架激活的方式，也不能据此推断安装卡死问题已修复。

## 验证

`python -m unittest discover -s tests -v` 检查原生规则工具。`python tools/build.py` 编译模块，使用官方 API 102 接口执行 9 项 HookFlow 调用链检查，然后签名并检查 APK 元数据和 DEX 类型。

这些检查验证接口接线与产物完整性，不模拟真实 ART 注入。平板未连接，首次加载、酷安搜索补充、键盘触摸/旋转/返回、与酷安净化共存及稳定性都需要真机回归。

来源：[官方 API 102.0.0](https://github.com/libxposed/api/releases/tag/102.0.0)、[API 源码](https://github.com/libxposed/api)、[Vector](https://github.com/JingMatrix/Vector)。
