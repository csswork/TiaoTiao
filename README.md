# TiaoTiao

个人自用 Android 开屏广告跳过工具。未知界面交给 Jev 判断，试跳结果由用户确认；相同界面再次出现时，直接执行已验证的 JSON 规则。

## 当前版本

当前版本 `0.1.4`，Android 8.0 / API 26 及以上，Kotlin + Compose，使用系统无障碍服务，无需 Root。应用显示名称统一为 TiaoTiao。

- 应用选择与总开关；显示无障碍服务实际连接状态。
- 未配置 Jev 密钥时，首页顶部显示醒目入口，点击后直接定位并聚焦密钥输入框；保存后提示自动隐藏，清除后重新显示。
- Jev 官方 API 节点识别：只从当前观察到的控件中选择，结果不明确则结束本轮。
- 支持 TypeSafe 直连和 Vercel AI Gateway，按服务使用对应模型名；提供固定请求的连接测试，以及首页连接与最近检测状态。
- 新规则底部浮层试跳、人工确认；未确认规则不能自动执行。
- 已验证规则本地离线匹配；校验包名、应用版本、界面结构和目标几何位置。
- JSON 导入、导出和系统分享；导入规则先试跳确认，不自动信任外部验证状态。
- 手动 HTTPS / WebDAV JSON 上传下载，采用 ETag 条件写入，遇到冲突停止覆盖。
- 本地节点样本归类、处理记录与清理；API Key 使用 Android Keystore 加密保存。
- 自带模拟广告页面，不调用模型即可验证完整确认与复用流程。

首版处理无障碍节点中能确认的文字跳过按钮；截图识别、图片按钮、坐标手势、持续后台同步队列、公共规则社区与链接分享仍属于后续阶段。这里的“见过”指页面结构与按钮特征匹配；不依赖广告图片或文案本身相同。

## 手机安装与使用

1. 安装 `app/build/outputs/apk/debug/app-debug.apk`，或 `artifacts/TiaoTiao-0.1.4-debug.apk`。
2. 打开 TiaoTiao，点击“打开无障碍设置”，在系统设置中开启“TiaoTiao”。部分安装来源和系统版本需要先在应用详情中允许受限设置。
3. 打开“自动跳过”。先点“打开模拟开屏”，在底部浮层点“试跳”，看到进入演示首页后点“确实跳过”。再次打开模拟开屏，检查自动复用。
4. 在“应用”选择需要处理的应用；默认全部关闭。
5. 点击首页顶部“填写 API Key”，或进入“设置”，选择与密钥对应的接入服务。Vercel AI Gateway 使用模型 `typesafe-ai/jev`，TypeSafe 直连使用 `jev-latest`。点击“保存并测试”，确认连接成功后开启“允许云端分析”。不要把密钥发到聊天或写进仓库。
6. 打开目标应用。新方案先显示试跳提示；确认后规则保存在手机应用私有目录的 `files/rules.json`。若开屏已结束，可下次打开应用再试跳。

无障碍服务由系统管理，应用无法自行绕过系统设置恢复权限。小米 14 Pro / HyperOS 如回收服务，请检查 TiaoTiao 首页连接状态并从系统设置重新开启。实现采用标准 Android API，目前没有针对小米的私有接口。

首页会显示模型是否已测试、鉴权失败、额度不足、网络失败等结果，以及最近一次检测的原因。进入已选应用后，记录会区分窗口事件、开始读取、找不到文字跳过控件、节点读取超限、模型请求和候选方案，避免把所有情况都表现为“没有反应”。图片按钮仍不在首版能力范围内。

每次从桌面或其他应用返回目标应用都会建立新的检测会话，兼容应用窗口焦点/活动标记均为 false 的系统。缺失窗口根节点、系统提示和输入法不会单独重启检测。已验证规则每次命中后仍检查一次界面变化；点击被系统接受与广告实际关闭分别记录，目标仍存在时不会连续重复点击。

## Jev 接入配置

| 接入服务 | 密钥来源 | 默认模型 | 请求地址 |
| --- | --- | --- | --- |
| TypeSafe 直连 | TypeSafe | `jev-latest` | `https://api.typesafe.ai/v1/systemone` |
| Vercel AI Gateway | Vercel AI Gateway | `typesafe-ai/jev` | `https://ai-gateway.vercel.sh/typesafe/v1/systemone` |

接入使用固定地址，不自动把同一密钥轮流发往多个服务。切换服务后模型名会按服务迁移；输入框留空沿用手机中已保存的密钥。保存并测试只发送一条固定的公开测试语句，不读取其他应用，也不自动开启云端分析。测试会使用对应账户的模型额度。

Vercel 路径依据 [TypeSafe API with AI Gateway 官方文档](https://vercel.com/docs/ai-gateway/sdks-and-apis/typesafe)，保留 Noul/Choice 请求与响应格式。

## 数据与分享

`files/rules.json` 是本地规则存储，格式版本为 1，结构定义在 `core/.../Models.kt`。规则包含包名、精确应用版本、规范化界面指纹、目标控件条件、状态、试跳记录及人工确认记录。

只有人工确认过的规则进入自动执行路径。修改匹配条件、动作或版本会使原确认记录失效；导入文件的状态统一转为 `IMPORTED`，保留已有本地已确认规则。规则不匹配、候选不唯一、窗口变化、输入树被截断时不会点击。

“规则”页面可导出、分享已验证规则，或者导入 JSON。导出不包含 API Key、同步凭据、样本、完整屏幕内容或日志。文件最多 1 MB、500 条规则；不执行任意脚本，不支持压缩包导入。

首版只保留经过允许词筛选的节点文本，例如“广告”“跳过”；输入框和密码子树不采集。每条新方案保存一个本地样本，最多 100 个，日志最多 150 条。删除规则会删除对应样本。云端识别把筛选后的控件数据发送给所选接入服务及其模型提供方，截图不采集也不上传。

## HTTPS / WebDAV 同步

在“设置”填写一个可读写的完整 JSON 文件 URL，例如 `https://your-host.example/dav/tiaotiao-rules.json`。如需要鉴权，在 Authorization 字段填入服务要求的完整值，例如 `Bearer …` 或 `Basic …`。凭据加密保存，不嵌入 URL。

- 首次上传使用 `If-None-Match: *`，只创建文件；存在远端文件时请先下载核对。
- 下载成功且服务器提供强 ETag 后，上传使用 `If-Match`，防止覆盖期间发生变化的远端文件。
- 上传内容是本机已验证规则的完整集合。合并远端规则时，需要在本机试跳确认后再上传，以免把未确认内容当作可执行规则。
- 服务器必须正确实现条件请求。没有可用强 ETag 的现有资源不会被直接覆盖；不跟随重定向，不支持明文 HTTP。
- 同步由用户手动触发；不需要部署本项目专用后台。

## 构建与检查

需要 JDK 21、Android SDK platform 37，以及 Android SDK build-tools。Gradle Wrapper 固定在 9.7.0，Android Gradle Plugin 9.3.1；运行目标版本为 Android 16 / API 36，最低 API 26。设置 `ANDROID_HOME`，或在不提交到仓库的 `local.properties` 中设置 `sdk.dir`。

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

端到端测试只在独立模拟器运行，会开启本应用无障碍服务、调整本应用设置并替换模拟页面的规则；不调用 Jev、不操作第三方应用：

```sh
ANDROID_SERIAL=emulator-5580 ./gradlew :app:connectedDebugAndroidTest
```

覆盖首次规则等待人工确认、确认后离线复用、导入规则重新确认、普通“跳过登录”页面不操作、暂停后不点击等流程。运行报告位于各模块 `build/reports/`。

## 项目结构

```text
app/                 Android UI、无障碍服务、私有存储、Jev/同步网络接口
core/                规则模型、匹配、确认状态机、JSON 校验与 Jev 协议
docs/AI_DATA_PLAN.md  完整设计与后续阶段
artifacts/           可安装 APK、验证记录及页面截图
```

Jev 接入依据 TypeSafe [官方 Choice 文档](https://docs.typesafe.ai/primitives/choice)、[API 定义](https://api.typesafe.ai/openapi.json)与 Vercel 的官方兼容接口文档。模型连通性、实际识别效果与 UI 演示分别验证；演示通过不代表所有第三方应用的广告都能识别。各版本验证范围见 `artifacts/VERIFICATION.md`。
