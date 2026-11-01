# 0.1.0 验证记录

日期：2026-11-01（Asia/Shanghai）。

## 交付

- `TiaoTiao-0.1.0-debug.apk`：可安装调试版；包名 `com.haibo.tiaotiao`，versionCode 1，最低 Android API 26，target API 36。
- `screenshots/proposal.png`：模拟开屏中的试跳浮层。
- `screenshots/confirmation.png`：试跳后的结果确认浮层。
- 使用方式和功能边界见项目根目录 `README.md`。

## 已通过

`./gradlew :core:test :app:lintDebug :app:assembleDebug`

- 核心 JVM 测试：15 项，失败 0，错误 0。
- Android Lint：Error/Fatal 0；保留依赖升级提示、版本兼容提示及中文原生演示文案等 Warning，不声称零警告。
- APK 构建成功。

`ANDROID_SERIAL=emulator-5580 ./gradlew :app:connectedDebugAndroidTest`

- 独立 Pixel 7 配置模拟器，Android 16 / API 36 / arm64。
- 1 项完整端到端用例通过：模拟未知界面产生待确认规则、试跳成功前后均不擅自启用、人工确认后离线自动复用、导入规则必须重新确认、正常“跳过登录”不操作、暂停后不点击。
- 测试只操作本应用的模拟页面，云端分析关闭，没有请求付费模型。
- 过程中发现并修复了试跳切换页面时结果确认浮层被移除的问题。

## 未实测项

- 未提供 Jev API Key，因此未调用真实 Jev，未测量其实际准确率、费用或网络延迟。Jev 请求与响应形状依据官方 API 定义，解析与候选校验由本地测试覆盖。
- 未配置远端 HTTPS/WebDAV 端点，因此上传下载尚未经过真实服务器验证。
- 未连接小米 14 Pro；HyperOS 以及其他系统版本上的服务驻留和真实第三方开屏广告效果尚未验证。
- 首版不包含截图/图片按钮识别，相关能力仍在设计文档的后续阶段。

测试报告：`core/build/reports/tests/test/index.html`、`app/build/reports/androidTests/connected/debug/index.html`、`app/build/reports/lint-results-debug.html`。
