# TiaoTiao

个人自用 Android 开屏广告跳过工具。所有已选应用共用检测、端侧识别、AI 后台学习、自动结果检查和本地 JSON 缓存，不按第三方应用包名单独实现识别流程。支持 DeepSeek 官方图片识别与 Jev 切换比较。

## 当前版本

当前版本 `0.3.10`，Android 8.0 / API 26 及以上，Kotlin + Compose，使用系统无障碍服务，无需 Root。应用显示名称统一为 TiaoTiao。

- 增加可选的“增强点击”：安装、启动并授权 Shizuku 后，优先用系统触控执行已匹配的关闭目标。未就绪保留无障碍通道；系统通道收到动作后若失败或不明确，不再切换通道重复点击。
- 识别、预览和点击统一使用完整逻辑显示坐标。点击前重新截图核对当前目标与画面，系统进程再次核对前台应用、屏幕尺寸和动作有效期；补点保留手动选点。系统完成回调不等同于广告提前关闭。

- Jev 官方直连使用 `jev-latest`，控件、图片识别与结果复核均可使用官方接口；DeepSeek 独立 Key 与 Vercel 配置保留供切换。
- 每次启动检查只短暂显示一次“TiaoTiao 正在检查”气泡，约 1.2 秒后消失；设置中可关闭，后台检查继续运行。
- 列表和详情使用实际广告截图，不显示模拟广告占位图。详情中直接双指或双击放大、拖动、单击选点并保存；没有箭头按钮，也不需要展开才能编辑。修改后保存单独的手动位置图片，保留原点击记录。
- “确认方案正确”立即启用当前方案，单独记录本机人工确认，不伪造两次 AI 成功。位置改变或重新学习会清除旧确认，导入规则也不会信任外部人工确认。
- 自动失败采用 15 秒、60 秒、之后 5 分钟的重学退避；新 AI 识别可以恢复候选。人工停用继续保留，不连带停用其他位置。

- 应用选择与总开关；显示无障碍服务实际连接状态。
- 未配置当前图片服务密钥时，首页顶部显示醒目入口，点击后直接定位并聚焦密钥输入框。DeepSeek 官方 Key 单独加密保存，不沿用 Vercel Key。
- API 配置完成后，首页服务配置移至下方；首屏优先展示运行状态、已验证 / 待处理 / 应用三个可点击入口。
- 已验证与待处理列表同时包含普通规则和智能规则；应用入口直接打开已选择应用，可切换全部应用并搜索。
- 同一应用、版本、屏幕场景和关闭目标的智能候选归为一个待处理任务；详情可切换识别样本查看位置与进度。不同样本、节点动作和触屏动作各自保存验证证据，不叠加成功次数。重复帧的小幅图像与定位变化不再新增候选。
- 应用选择页的“全部 / 已选择 / 类别”采用同排筛选标签，可与名称或包名搜索组合。类别由已配置的 Jev 基于应用名称和包名批量识别，结果持久缓存；未识别显示待识别，不再依赖系统粗分类和内置应用预设。
- 详情默认选择有实际图片的样本；旧样本无原图时可明确标注并参考同任务最近画面进行调整，完全没有图片则如实提示。
- 管理页不参与广告识别，诊断日志后台保存，应用名称异步缓存；滚动性能检查与采样范围见 [性能记录](docs/PERFORMANCE.md)。
- 导航、箭头、搜索与状态图标使用统一的 QingIcon Line 矢量；应用内和桌面 Logo 沿用手机已安装的 0.2.5 设计，本轮不更新品牌设计。
- Jev 官方 API 节点识别：只从当前观察到的控件中选择，结果不明确则结束本轮。
- 支持 TypeSafe 直连和 Vercel AI Gateway，按服务使用对应模型名；提供固定请求的连接测试，以及首页连接与最近检测状态。
- Android 11+ 默认对所有已选应用使用通用自动学习；AI 与时序证据检查通过后启用，无需逐条人工确认。旧版普通规则及其确认路径保留。
- 已验证规则本地离线匹配；校验包名、应用版本、界面结构和目标几何位置。同一已验证界面在目标位置暂未匹配时继续等待本地规则，不重复调用 Jev。
- JSON 导入、导出和系统分享；导入规则先试跳确认，不自动信任外部验证状态。
- 手动 HTTPS / WebDAV JSON 上传下载，采用 ETag 条件写入，遇到冲突停止覆盖。
- 本地节点样本归类、处理记录与清理；API Key 使用 Android Keystore 加密保存。
- 自带模拟广告页面，不调用模型即可验证完整确认与复用流程。

通用路径结合控件、关闭图标、随包中文 OCR 和视觉模型，不要求先出现文字“跳过”。Android 8–10 或关闭自动学习时保留普通规则路径。持续后台同步、公共社区与链接分享仍属于后续阶段。这里的“见过”指页面结构与按钮特征匹配；不依赖广告图片或文案本身相同。

界面示例：[首页](artifacts/screenshots/0.3.5/ui-home.png) · [待处理任务](artifacts/screenshots/0.3.5/ui-pending.png) · [点击位置预览](artifacts/screenshots/0.3.5/ui-rule-preview.png)。截图使用合成规则，不代表第三方应用的识别效果。

0.3.6 独立配置：[未填官方 Key 时的首页入口](artifacts/screenshots/0.3.6/ui-official-missing-key.png) · [DeepSeek 官方 Key 输入页](artifacts/screenshots/0.3.6/ui-official-key-entry.png)。截图来自隔离模拟器，未包含真实密钥。

品牌预览：[Logo 与图标](artifacts/branding/tiaotiao-brand-board.png) · [SVG 源图](artifacts/branding/tiaotiao-logo.svg)。沿用已安装的双 t 标记；[生成与资源说明](artifacts/branding/README.md)。界面图标来自 [QingIcon 开源项目](https://github.com/asorn/qingicon-open)，固定源版本，仅转换为原生 VectorDrawable；[原始 SVG、Apache-2.0 许可证与 NOTICE](third_party/qingicon/README.md) 随项目保留，许可文件同时打包进 APK。

## 手机安装与使用

1. 安装 `app/build/outputs/apk/debug/app-debug.apk`，或 `artifacts/TiaoTiao-0.3.10-debug.apk`。
2. 打开 TiaoTiao，点击“打开无障碍设置”，在系统设置中开启“TiaoTiao”。部分安装来源和系统版本需要先在应用详情中允许受限设置。
3. 打开“自动跳过”。先点“打开模拟开屏”，在底部浮层点“试跳”，看到进入演示首页后点“确实跳过”。再次打开模拟开屏，检查自动复用。
4. 在“应用”选择需要处理的应用；默认全部关闭。点击“类别”可按用途筛选，并可同时使用已选择范围和名称 / 包名搜索；筛选本身不会修改应用开关。“重置”清空类别与搜索词，保留当前全部 / 已选择范围。
5. 使用 Jev 官方 Key 时，在“设置 → 云端分析与 Jev”选择 TypeSafe 直连、模型 `jev-latest`，保存并测试；图片服务选择“Jev 官方直连”，点“保存并测试图片”。使用 DeepSeek 时，在图片服务选择“DeepSeek 官方直连”，填写独立官方 Key。图片测试只发送固定白色图片。成功后开启“允许云端分析”；不要把密钥发到聊天或写进仓库。
6. 开启“通用自动学习”，打开已选应用。DeepSeek 官方模式通过 `api.deepseek.com` 识别未知开屏及复核点击结果；不需要 Jev Key，不调用 Jev 广告节点分析，不经过 Vercel。学习结果晚到时只缓存，下次重新匹配后再使用。关闭自动学习或使用 Android 8–10 时，普通规则仍先试跳与人工确认。

原 Jev 配置保留，应用分类仍可使用它；在图片服务中选择“Vercel 网关”可切回 Jev 对照。两套密钥互不替代，缺少官方 Key 时暂停新的官方图片请求，不自动回退网关。清除任一 Key 不会删除另一套密钥。

### 已识别但点击无响应

在“设置 → 增强点击”安装 [官方 Shizuku](https://shizuku.rikka.app/download/)，按其指引启动，回到 TiaoTiao 点“授权增强点击”并允许。开启“优先使用增强点击”。这不需要重新填写 AI Key 或重建已确认方案。无 Root 手机重启后通常要重新启动 Shizuku；它未就绪时，界面会明确显示状态。

参考 [GKD 的点击通道设计](https://github.com/gkd-kit/gkd/blob/7a931ff561ca4ea630c122e1e1c6e529493a5650/gkd-app/src/androidMain/kotlin/li/gkd/app/matching/action/ActionPerformer.kt)，独立实现受限的 Shizuku 用户服务，没有引入 GKD 源码。Shizuku API 固定为 Maven Central 发布的 13.1.5。对照实测与限制见 [触控排查](docs/TOUCH_EXECUTION_REVIEW.md)。

### Jev 应用分类

开启云端分析且已配置密钥后，Jev 在后台按每批最多 8 个应用识别分类，沿用现有 TypeSafe 或 Vercel 接入。只发送应用名称和包名，不读取其他应用的屏幕或账号内容。分类独立于“自动跳过”总开关，关闭云端分析则停止新请求，已有分类仍可使用。

结果保存在私有目录 `files/app_categories.json`。重新打开、滚动和切换筛选复用缓存；新增应用、应用改名、切换模型或分类定义变化后，仅分析缺少对应缓存的条目。分类页显示进度，并提供“重新识别”和失败重试；重新识别会调用模型并覆盖原分类，失败时保留已有结果。分类不改变已勾选应用。

不确定或无法归类的 Jev 结果放入其他；模型自报置信度不等同准确率。缓存不包含密钥，也不加入广告规则的导出和 WebDAV 同步。界面示例：[同排筛选](artifacts/screenshots/0.2.4/ui-category-search.png)（模拟器使用分类测试数据）。

无障碍服务由系统管理，应用无法自行绕过系统设置恢复权限。小米 14 Pro / HyperOS 如回收服务，请检查 TiaoTiao 首页连接状态并从系统设置重新开启。实现采用标准 Android API，目前没有针对小米的私有接口。

首页会显示模型是否已测试、鉴权失败、额度不足、网络失败等结果，以及最近一次检测的原因。进入已选应用后，记录会区分窗口事件、开始读取、找不到文字跳过控件、节点读取超限、模型请求和候选方案，避免把所有情况都表现为“没有反应”。普通兼容路径处理文字按钮；通用自动学习由视觉路径补充关闭图标和图片控件。

每次从桌面或其他应用返回目标应用都会建立新的检测会话，兼容窗口焦点/活动标记均为 false 的系统。启动窗口暂不可读取时有界等待，不延长会话。图像目标先跨帧确认稳定，再执行 80 ms 短点；只有新截图确认同应用、同窗口、画面未变、广告及同一关闭控件仍在时，最多重新校准补点一次。普通节点动作不追加坐标点击。日志区分动作提交与广告实际关闭；两个不同会话的 AI 与提前关闭证据要求保留。

### 小米后台运行

如果切换到其他应用后不再产生新的检测记录，即使首页仍显示服务已连接，也可能是应用进程被系统冻结。到系统“应用详情 → TiaoTiao”，将“省电策略／电量和性能”设为“无限制”，并开启“后台自启动”（若有），然后回到 TiaoTiao 再测试。菜单名称随系统版本变化；这些设置由用户在系统界面调整，应用不会自动更改。

小米手机实测中曾在主线程 `/proc/<pid>/task/<pid>/wchan` 观察到 `do_freezer_trap`，同时系统仍保留无障碍绑定，导致窗口事件无法及时交给应用。此状态与 Jev 密钥或网络连通性不同。

## 通用自动学习

Android 11 / API 30 起，对所有已勾选应用使用同一条管线；没有第三方包名白名单、40 节点限制或“必须先找到跳过文字”的条件。Android 8–10 保留普通确认路径。旧 `smartDamai` 设置自动迁移为通用开关，并保留原开关值；旧规则文件不会被清空。

1. 收到应用窗口事件后先确定当前窗口归属，立即启动有界检测。窗口元数据、截图识别和后台节点读取分开；不会因未知窗口而回到主线程整树读取。节点按广度优先检查，避免长内容子树遮蔽同层关闭按钮；在 IO 中执行，最多 160 个节点、150 ms 的遍历预算。单次系统 Binder 调用仍可能超时，但不阻塞界面线程和截图识别。
2. Jev 图片模式下，第一份可用控件快照即可交给 Jev：提供受限的布局、控件类型、资源 ID、位置和允许词，候选可以是关闭、叉号、无文字小图标或其他小型结束控件。DeepSeek 官方模式跳过 Jev 节点请求，直接使用同一图片管线。登录、授权、购买、下载、普通导航及不确定场景不执行。
3. 同时用随包 ML Kit OCR 检查画面，服务连接时通过合成图片预热。所选图片服务检查未知画面，不要求画面出现“广告”或“跳过”文字。已由 AI 确认的图像需要同时匹配当前上下文和目标图像特征，不能仅凭相同坐标复用。
4. 本轮节点与图片学习各最多 2 个请求，合计最多 4 个，节点请求不会占掉图片复核预算。不确定或与本地明确广告信号矛盾的结果允许短间隔重查；明确非广告结果短暂去重。离开应用后仍可缓存学习结果，过期结果不会点击后台应用或新窗口。
5. 连续两帧都明确包含广告标志及同一文字跳过按钮时，可以先保存通用端侧候选并使用当前位置执行，结果仍须 AI 复核；关闭图标与歧义画面继续由模型定位。图片请求向 Jev 附带广告标志、关闭区域和倒计时等公开证据，不传原始 OCR 全文。Jev 选中的原生控件使用近期节点句柄，图片位置使用受限手势；手动位置优先。首次手势后只有画面和原关闭控件均未变化才允许补点一次，用户停用的目标不会自动恢复。
6. 点击后尽早取得两帧并检查页面变化，本地不确定时交给独立视觉请求，排除广告仍在、详情、购买、登录和系统提示。首页同位置或其他位置的叉号不会直接阻止 AI 复核；截图缺失不等同点击失败。倒计时或已观察的自然结束基线用于区分提前关闭与自然结束；没有计时证据的首轮可以尝试，但不计为验证成功，后续自动观察一次自然结束。观察期间不点击，最多两次 AI 请求确认两帧均为首页，以最后仍匹配广告的画面时间建立保守时长，不把自然结束计为一次成功。基线绑定具体方案的结构/图像证据和目标。AI 接受首页特征不依赖提前关闭证据，两种证据分别积累；两个不同会话同时取得 AI 与时序证据后才启用。
7. 已启用规则可本地复用；结果检查结合当前首页信号或经 AI 接受的稳定首页局部特征。结果不确定时按配置交给 AI 复核；云端未就绪且本地证据不足时暂停复用，不继续盲点。

AI 结果低于置信阈值时保留候选，不增加成功或失败次数，也不自动停用；点击前后画面未变化时，即使 AI 返回首页，也不计为成功。当前真机证据已确认存在触控未关闭广告的情况，不能以模拟回归通过代替真实成功率，详见 [验证记录](artifacts/VERIFICATION.md)。

0.3.4 的局部校准按控件中心判断，不要求文字框与模型按钮框四条边都接近；重叠的 OCR 和节点框选较小的精确框，附近独立控件有歧义时不用于校准。首次仅有图像模板、没有当前控件边界时，至少再观察一帧后才允许尝试。结果检查遇到同一应用换窗口时丢弃过渡帧并继续取样，切换到其他应用仍停止。

DeepSeek 官方模式固定使用 `https://api.deepseek.com/chat/completions` 与 `deepseek-flash`，图片使用 base64 输入，JSON 模式返回目标或结果判断，关闭思考模式以减少等待。识别和点击前后复核都使用独立官方 Key。依据 [官方图片文档](https://api-docs.deepseek.com/guides/vision/)、[思考开关](https://api-docs.deepseek.com/guides/thinking_mode/)及 [JSON 模式](https://api-docs.deepseek.com/guides/json_mode/)接入。[官方直连实测](docs/DEEPSEEK_OFFICIAL_EVALUATION.md)已取得真实识别及结果判断，缓存点击后大麦广告仍在，尚未稳定跳过。

切回 Vercel 时，Jev 图片模型为 `typesafe-ai/jev-1.14.0`，通过 TypeSafe 兼容入口的原生 `assets`、Box 和 Noul 使用已有网关密钥，最长边缩至 1536 像素。禁止将 DeepSeek 模型配置到网关分支。云端开关控制全部新请求，应用分类使用单独保留的 Jev 配置。

升级 0.3.6 一次性选择 DeepSeek 官方模式，等待单独填写官方 Key；保留 Jev 密钥、已选应用、云端开关和已验证规则。旧未验证候选等待重新识别，切换模型时不跨提供方复用候选或叠加证据。已验证本地规则继续复用。Jev 实景历史结果和价格见 [Jev 1.14 实测记录](docs/JEV_114_EVALUATION.md)，大麦尚未满足两次提前关闭证据，不能宣称稳定跳过。

`files/visual_rules.json` 使用智能规则 schema 2，兼容读取 schema 1；保留包名、版本、目标、结构/图像匹配证据及自动验证记录，最多 500 条 / 4 MB。`tapMethod` 明确保存控件或触控方式，动作改变会重新自动验证；旧视觉规则自动迁移，用户停用及 AI 明确拒绝的方案继续暂停，仅对旧原生点击结果不明的图像方案允许重新检查。普通规则继续使用 `files/rules.json` 的 schema 1，最多 500 条 / 1 MB，保留原有语义指纹与人工确认。导入仍清除外部验证并要求本机 AI 重新识别。

原始截图只在内存中处理，缓存和发送的是遮挡非必要 OCR 文字后的图片；从长句抽取到“登录”等语义词，不会让整条原文免于遮挡。待识别图片最多 8 份，未识别画面按 24 小时过期。每个已生成方案另外保存识别、最近点击及手动调整图片，位于私有 `files/visual_previews/`，删除方案时一并清除；不加入规则分享或 WebDAV 同步。遮挡不等同完整个人信息识别。

0.3.8 使用 Jev 官方 `POST https://api.typesafe.ai/v1/systemone`，图片使用原生 assets、Box/Noul 和图片实验标记；`jev-latest` 跟随最新模型，参见 [官方模型文档](https://docs.typesafe.ai/models)和 [图片文档](https://docs.typesafe.ai/images)。升级一次性选择用户要求的 Jev 官方配置，保留已保存 Key 及 DeepSeek 独立密钥。遇到 401/403/404/451 会停止重复请求，重新保存或测试后恢复。流程审查及验证见 [流程修订记录](docs/FLOW_REVIEW.md)。

最新截图编辑示例：[详情内缩放](artifacts/screenshots/0.3.9/ui-ad-screenshot.png) · [同层选点保存](artifacts/screenshots/0.3.9/ui-edit-click-point.png) · [旧样本参考图](artifacts/screenshots/0.3.9/ui-old-sample-real-image.png)，使用模拟器合成图片。

首页“智能视觉演示”验证本地 OCR 和自动检查；另外的 `test-app` 模块只为模拟器构建两款不同包名测试应用，生产 APK 不依赖它。回归覆盖陌生应用首次关闭、260 余节点的深层内容、无文字关闭控件、没有“广告/跳过”文字且没有可读控件的纯图像、晚到结果下次复用、登录页排除以及关闭云端后的复用。测试注入模拟模型响应，不代表真实模型的识别准确率或响应速度。

云端分析仍可能晚于第一次开屏结束；此时保存候选以供下次使用，不保证每个应用第一次打开都立即跳过。截图能力由 [Android 无障碍 API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService) 提供，文字识别使用 [ML Kit 随包中文模型](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)。识别库候选见 [识别库评估](docs/RECOGNITION_OPTIONS.md)。

## Jev 接入配置

| 接入服务 | 密钥来源 | 默认模型 | 请求地址 |
| --- | --- | --- | --- |
| TypeSafe 直连 | TypeSafe | `jev-latest` | `https://api.typesafe.ai/v1/systemone` |
| Vercel AI Gateway | Vercel AI Gateway | `typesafe-ai/jev` | `https://ai-gateway.vercel.sh/typesafe/v1/systemone` |

接入使用固定地址，不自动把同一密钥轮流发往多个服务。切换服务后模型名会按服务迁移；输入框留空沿用手机中已保存的密钥。保存并测试只发送一条固定的公开测试语句，不读取其他应用，也不自动开启云端分析。测试会使用对应账户的模型额度。

Vercel 路径依据 [TypeSafe API with AI Gateway 官方文档](https://vercel.com/docs/ai-gateway/sdks-and-apis/typesafe)，保留 Noul/Choice 请求与响应格式。

## 数据与分享

`files/rules.json` 是本地规则存储，格式版本为 1，结构定义在 `core/.../Models.kt`。普通规则包含包名、精确应用版本、规范化界面指纹、目标控件条件、状态、试跳记录及人工确认记录。智能规则另外记录视觉目标特征和自动验证证据。

普通规则只有人工确认后进入自动执行；通用智能候选使用上文所述自动尝试与验证流程。修改匹配条件、动作或版本会使原确认记录失效；导入文件的状态统一转为 `IMPORTED`，保留已有本地已确认规则。规则不匹配、候选不唯一、窗口变化、输入树被截断时不会点击。

“规则”页面可导出、分享已验证规则，或者导入 JSON。导出不包含 API Key、同步凭据、样本、完整屏幕内容或日志。普通规则文件最多 1 MB、智能规则文件最多 4 MB，两者各最多 500 条；不执行任意脚本，不支持压缩包导入。

节点样本只保留经过允许词筛选的文本，例如“广告”“跳过”；输入框和密码子树不采集。每条新方案保存一个本地样本，最多 100 个，日志最多 150 条。删除规则会删除对应样本。普通云端识别发送筛选后的节点数据；通用视觉学习向所选的 DeepSeek 官方服务或 Vercel 网关发送遮挡后的画面，用于识别和自动检查。密钥与截图不包含在规则导出文件中。

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

覆盖普通规则的首次人工确认、离线复用、连续返回、导入重新确认与排除“跳过登录”；另有画布广告的端侧 OCR、自动尝试、不同会话验证后自动启用、关闭后不操作。真实手机同时连接时应手动指定模拟器 serial 安装并运行 instrumentation，避免误将测试运行在手机上。运行报告位于各模块 `build/reports/`。

## 项目结构

```text
app/                 Android UI、无障碍服务、私有存储、Jev/同步网络接口
core/                规则模型、匹配、确认状态机、JSON 校验与 Jev 协议
docs/AI_DATA_PLAN.md  完整设计与后续阶段
artifacts/           可安装 APK、验证记录及页面截图
```

Jev 接入依据 TypeSafe [官方 Choice 文档](https://docs.typesafe.ai/primitives/choice)、[API 定义](https://api.typesafe.ai/openapi.json)与 Vercel 的官方兼容接口文档。模型连通性、实际识别效果与 UI 演示分别验证；演示通过不代表所有第三方应用的广告都能识别。各版本验证范围见 `artifacts/VERIFICATION.md`。

### 独立模拟器通用回归

先构建两款测试应用，再显式指定模拟器安装；测试包含硬件守卫，禁止在真实手机运行。

```sh
./gradlew :test-app:assembleAlphaDebug :test-app:assembleBetaDebug :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5580 install -r test-app/build/outputs/apk/alpha/debug/test-app-alpha-debug.apk
adb -s emulator-5580 install -r test-app/build/outputs/apk/beta/debug/test-app-beta-debug.apk
adb -s emulator-5580 shell am instrument -w com.haibo.tiaotiao.test/androidx.test.runner.AndroidJUnitRunner
```
