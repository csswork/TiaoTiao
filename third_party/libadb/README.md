# 内置无线调试

使用 [LibADB Android 3.1.1](https://github.com/MuntashirAkon/libadb-android/tree/3.1.1) 完成本机 ADB 配对与 TLS 连接，选择其双许可中的 Apache-2.0。版权及来源保留于上游源码，许可文本位于本目录和 APK 的 `assets/licenses/libadb/`。

TLS 使用 Conscrypt Android 2.5.3；证书生成使用 Bouncy Castle 1.81。依赖版本固定在 Gradle 中。系统触控、单应用 Binder 服务、调用身份校验、配对通知与界面由本项目实现，不依赖外部 Shizuku 管理器或服务，也不复制 GKD 源码。

ADB 只连接 `127.0.0.1`，用于启动本应用的固定服务。触控时使用受限 Binder 接口，启动后断开 ADB。无线身份独立加密保存，不进入规则导出或同步，配对码不持久化。
