# 依赖与来源

仓库提交自己的 Java / Python 源码、构建元数据和最小应用规则。构建工具下载后缓存在 `.cache/`，不提交第三方二进制。

| 依赖 | 用途 | 上游 |
| --- | --- | --- |
| Android 4.1.1.4 编译桩 | 仅用于编译本项目使用的基础 Android API | Maven Central `com.google.android:android:4.1.1.4` |
| Xposed API 82 | 编译时接口，由设备上的注入框架提供运行实现 | https://api.xposed.info/ |
| R8 / D8 8.3.37 | Java 字节码转 DEX | Google Maven `com.android.tools:r8` |
| Apktool 2.11.1 | 清单编译与 APK 打包 | https://github.com/iBotPeaches/Apktool |
| uber-apk-signer 1.3.0 | 实验 APK 对齐、调试签名与校验 | https://github.com/patrickfav/uber-apk-signer |

固定下载地址与 SHA-256 在 `tools/dependencies.json` 中。各依赖许可由对应上游提供，仓库未重新授权第三方组件。

设备端使用 KernelSU、Zygisk Next、Vector；本项目不打包或安装这些框架。应用名和 Activity 标识用于定位已安装应用，不包含 B 站、酷安或厂商系统的 APK、反编译代码、完整私有规则库。

此前参考项目的功能构想不等于代码授权；本仓库未复制 sothx/mipad-magic-window 的代码、界面或规则。
