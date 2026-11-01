# 0.1.0 验证记录

日期：2026-11-01（Asia/Shanghai）。

## 0.1.4 重复进入应用的检测会话

- 新安装包：`TiaoTiao-0.1.4-debug.apk`，versionCode 5。
- 修复小米应用窗口焦点/活动标记均为 false 时，离开应用后旧检测会话可能保留“已点击”状态的问题。根据可读取的应用窗口归属和窗口事件归属更新会话，锁屏时结束会话。
- 给窗口会话增加递增编号，点击后的延迟观察不会把下一次启动误当作上一次点击的结果。
- 已验证规则点击后检查一次界面变化；目标仍存在时给出明确记录，并保持禁止盲目重复点击。
- 核心测试 22 项、Android JVM 测试 5 项通过；APK 构建和 Lint（无 Error/Fatal）通过；模拟器完整人工确认/离线复用用例再次通过 `OK (1 test)`。
- 新增回归场景：经桌面返回相同应用、锁屏后返回相同应用，以及系统窗口/短暂空根节点不能重开已完成会话。

## 0.1.3 Vercel 网关与检测诊断

- 新安装包：`TiaoTiao-0.1.3-debug.apk`，versionCode 4。
- 增加 Vercel AI Gateway 的 TypeSafe 兼容接口，使用 `typesafe-ai/jev` 模型和 Gateway 密钥；保留 TypeSafe 直连。
- 增加保存并测试、固定公开语句的连接探测、首页连接状态和最近检测原因。请求失败不显示原始响应正文或密钥。
- 兼容“跳过广告”“5秒 | 跳过”“Skip ad 3s”等文字形式，继续排除跳过登录、教程等普通功能。
- 修复系统提示窗口被误当作应用离开前台、短暂读不到界面时重置检测计时的问题。
- 核心测试 19 项、Android JVM 网络/错误测试 5 项通过；APK 构建和 Lint（无 Error/Fatal）通过。
- 在独立 API 36 模拟器手动指定 serial 安装测试 APK，运行完整人工确认与离线复用流程，结果 `OK (1 test)`。未在真实手机安装或运行测试 APK，避免测试重置真实配置。
- 已在小米手机覆盖安装 0.1.3，保留原配置与加密凭据；手机内真实 Vercel 固定请求测试成功，曾记录 861 ms。该结果只证明这次连通性，不代表平均延迟或识别准确率。
- 在知乎记录到 Vercel 识别候选、人工试跳确认，以及一次已验证规则的点击；0.1.4 继续修复重复进入应用时的检测会话问题。

## 0.1.2 首页密钥入口

- 新安装包：`TiaoTiao-0.1.2-debug.apk`，versionCode 3。
- 未配置 Jev 密钥时，首页顶部显示高对比度提示卡片和“填写 API Key”按钮。
- 点击后跳转设置、定位密钥输入框并弹出键盘；保存后收起键盘，首页提示隐藏；清除密钥后提示恢复。
- 已通过 APK 构建和 Android Lint（无 Error/Fatal）。在独立 API 36 模拟器实际验证入口、自动聚焦、保存/清除后的提示状态，以及普通进入设置时不自动聚焦。
- 交互验证仅使用本地假密钥，云端分析保持关闭，未发起模型调用。截图见 `screenshots/home-key-entry.png` 和 `screenshots/key-entry-focused.png`。

## 0.1.1 名称更新

- 新安装包：`TiaoTiao-0.1.1-debug.apk`，versionCode 2。
- 桌面、应用标题和无障碍服务名称统一为 `TiaoTiao`，其余界面语言保持中文。
- 已重新通过核心测试、Lint（无 Error/Fatal）和 APK 构建；名称更新不改变跳过与确认流程。
- 下方端到端记录与截图为 0.1.0 验证留档，显示当时的旧名称。

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
