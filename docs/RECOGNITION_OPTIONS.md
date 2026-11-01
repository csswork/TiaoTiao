# 识别库候选与当前接入状态

日期：2026-11-01。当前 0.2.0 接入 ML Kit 随包中文 OCR 和可更换的 Vercel 视觉模型。下列开源项目尚未加入生产依赖，不能据此声称识别率已经提高。

| 项目 | 适合的部分 | 本项目建议 |
| --- | --- | --- |
| [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) / [官方 Android 部署](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/inference_deployment/cross_platform/android_deployment.en.md) | 中文文本检测与识别、端侧部署，Apache-2.0 | 首选 OCR 对比候选，以同一批大麦画面测按钮召回、位置误差、冷启动与 P95 延迟 |
| [RapidOCR Android Kotlin/Compose](https://github.com/RapidAI/RapidOcrAndroidOnnxCompose) | ONNX Runtime + OpenCV 的安卓 OCR 示例，Apache-2.0 | 评估嵌入成本、ABI、模型格式与耗电，不能把示例当作现成广告分类器 |
| [GKD](https://github.com/gkd-kit/gkd) | 无障碍、高级选择器、规则订阅，GPL-3.0 | 参考稳定目标描述和规则维护，避免重造完整选择器；不是视觉广告识别模型。复用代码与订阅前分别核对其许可 |
| [Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM) | 手机 GUI 理解与操作模型/框架 | 作为后台图像理解候选，不直接扩大本应用的动作范围，不把整套 ADB 控制框架嵌入 APK |
| [OmniParser](https://github.com/microsoft/OmniParser) | 屏幕图标与交互元素解析 | 可在后端评估非文字控件定位，代码与各组件/权重许可分别核对；先不作为手机实时依赖 |

OCR 解决文字与位置，GUI 模型补充图像语义；广告判断还需要启动上下文、目标可用性和执行后证据。使用固定正例、无按钮广告、普通首页、登录、引导等反例比较，统计误触、漏检和用户介入次数。不能仅以公共 OCR 榜单代替本机验收。
