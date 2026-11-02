# 0.2.2 滚动性能检查

日期：2026-11-02，Asia/Shanghai。原版为本机保存的 0.2.1 debug APK，修复版为 0.2.2 debug APK。

## 确认的问题与调整

1. 无障碍事件会在判断应用是否需要处理之前，先在主线程获取窗口、版本并遍历最多 300 个节点。管理页自身事件也会进入此路径。现在在事件入口过滤管理页，在窗口根节点处再次排除；普通和智能模拟广告页仍允许识别。增加 `treeReads` 与 `ignoredDashboardEvents` 安全计数，不记录界面文字。
2. 诊断日志在调用线程串行化整个列表并执行 AtomicFile 写盘，服务通常从主线程调用。现在先更新内存状态，再由单一 IO 消费者保存最新快照；高频写入合并，清空与后续日志保持顺序。规则及验证记录的存储方式不变。突然终止进程时，最后尚未写出的诊断日志可能丢失。
3. 规则卡片同步查询 PackageManager 和应用名称；列表项移出再进入时又会查询。改为 IO 读取及应用级内存缓存。规则验证状态在展示模型创建时计算，列表筛选、排序、计数按数据变化缓存，避免滚动期间重复计算规则摘要。
4. 整个主界面原先订阅日志列表，其他页面也会受日志更新影响。现在只有记录页订阅；记录使用稳定 ID，并复用日期格式器。应用及规则列表增加内容类型，预览动画在绘制阶段读取进度。

## 同场景诊断采样

使用独立 API 36 arm64 模拟器、1080 × 2400、无窗口软件渲染。两份 APK 均为 debug；同一采样工具创建 60 条已验证合成规则与 150 条诊断记录，关闭云端分析，逐页执行三组往返滑动。每组校验无障碍服务实际连接，分别测试检测关闭和开启。

采集 `FrameMetrics.TOTAL_DURATION` 与 `FrameMetrics.DEADLINE`，排除首绘帧。下表为**检测开启**时的单次样本；“超预算”是前者大于后者，不是手机 FPS 或 Android vitals 的统计。

| 页面 | 采集帧数：前 → 后 | 超预算帧：前 → 后 | 超预算比例：前 → 后 | P95 毫秒：前 → 后 |
| --- | --- | --- | --- | --- |
| 首页 | 128 → 186 | 39 → 10 | 30.5% → 5.4% | 72.3 → 53.6 |
| 规则 | 137 → 186 | 37 → 10 | 27.0% → 5.4% | 67.1 → 52.6 |
| 记录 | 124 → 184 | 27 → 13 | 21.8% → 7.1% | 50.6 → 54.1 |

修复版三个页面在采样期间的 `treeReadDelta` 均为 0。原始数据包含关闭检测的样本及服务连接状态：

- [`before-debug-connected.json`](../artifacts/performance/0.2.2/before-debug-connected.json)
- [`after-debug-connected.json`](../artifacts/performance/0.2.2/after-debug-connected.json)

超预算比例在该次场景下降，但记录页 P95 未改善。样本量有限、未进行多轮统计，debug/JIT 热身、执行顺序及软件渲染均会影响结果；不能将它换算为小米手机的整体提速百分比，也不能宣称全部卡顿已消除。Android 官方建议用发布构建及 R8 做正式性能评估：[Compose 性能](https://developer.android.com/develop/ui/compose/performance)。

检查开始时，小米手机累计帧统计为 5,509 帧、36 个 janky 帧，P95 24 ms、P99 113 ms。这些数据混合启动、切页和其他操作，不作为滚动对照样本。

## 验证

- 核心 JVM 36 项、Android JVM 8 项通过，APK/测试 APK 构建及 Lint 通过，Error/Fatal 0。
- 模拟器五项功能检查通过：普通试跳/确认/离线复用、画布视觉学习、规则界面导航、开启检测时管理页滚动不读树、日志突发/清空的 StrictMode 与持久化顺序。性能计时用例默认跳过，显式执行通过。
- 所有会重置数据或修改无障碍设置的测试均限定模拟器；真实手机只覆盖安装修复版、核对 APK 校验值及读取安全诊断信息。
- 真机打开管理页后，安全诊断为 `enabled=true`、`scanning=false`、`treeReads=0`、`ignoredDashboardEvents=42`，确认实际服务已进入管理页排除路径。手机 APK 与交付文件校验值一致；没有对安装后的手机进行自动滑动或宣称已获得真机帧率对照。

显式运行计时工具（只在独立模拟器执行，会替换测试规则与日志）：

```sh
adb -s emulator-5580 shell am instrument -w \
  -e class com.haibo.tiaotiao.ScrollPerformanceTest \
  -e scrollPerf true -e perfLabel sample \
  com.haibo.tiaotiao.test/androidx.test.runner.AndroidJUnitRunner
```

工具将统计保存在本应用私有目录 `files/scroll-sample.json`。普通功能测试不依赖帧耗时阈值。
