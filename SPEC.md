# SPEC — 官方源＋HansFix 独立源同次改包

## 硬要求

1. Morphe 专家模式，官方源与本 addon 源在**同一次改包 Session**中同时使用。
2. 不用全量 fork 替代官方源，不重复打包官方扩展类，不重复添加 Cookie 设置。
3. HansFix 独立启用，不读取官方 Cookie 设置。官方补丁可共存，精确 URL 改写保留；详见下方最新授权。
4. 不改变轨道对象、共享列表、协议匹配、原 Cookie 值或无关参数。
5. 独立 runtime namespace；不依赖两个 bundle 的同名 class 自动覆盖。
6. 不依赖源顺序、补丁名称前缀或未经验证的跨 ClassLoader Kotlin dependsOn。

## 授权更新（2026-09-07）

用户已提供目标原APK及Manager 1.29.0版本截图，并授权推进功能实现、验证后按Morphe原生流程公开干净仓库并发布可添加的补丁源。准备阶段的“不实现/不发布”限制已被本次明确授权替代；安全和双源同Session硬要求保持。没有证据的实机效果仍必须标为未验证，不得伪称。可先发布明示该边界的 dev 预发布，让用户用实际 Manager 双源验证；稳定通道晋级保留手机反馈门槛。

## 前期准备范围（历史）

可行性研究、干净 addon 骨架、环境与基础构建、命名空间冲突检查、上传前安全检查、私有 GitHub 准备仓库。

本轮不实现生产 hook、不发布 release、不向 Manager 暴露假成品入口。没有活动补丁注册是有意的防误用门槛。

## 后续阶段

| 阶段 | 必要工作 | 通过条件 |
| --- | --- | --- |
| P0 准备 | 本轮工作 | 构建基线、来源、独立命名空间、隐私和明确未完成项 |
| P1 输入/指纹 | 干净原包与官方 v1.41.0组合；官方执行后定位 | 包名/版本/签名/哈希核验，关键指纹唯一 |
| P2 插件实现 | 独立 extension＋Kotlin patch＋官方状态桥接 | 未选择官方Captions会明确失败；不克隆官方设置或共享模型 |
| P3 双源集成 | 在一个Session加载两个独立bundle | 两种源/选择输入顺序和推荐/普通菜单分支均通过；无重复类或覆盖 |
| P4 新产物实机 | Manager生成APK，用户本地签名安装 | 菜单、摘要、开关重启、原视频字幕节奏、其他语言与原型一致 |
| P5 公开发布 | 审计后公开源码和.mpp | main manifest、release资产、tag/hash一致且Manager实际加载成功 |

## 输入与发布授权状态

- 已由用户提供并核验未打补丁的 `com.google.android.youtube` 21.07.247 / 1561056418 APK，Google签名通过；哈希见锁文件。旧Morphe成品只作对照。
- 用于复现的官方源精确版本；初始固定 v1.41.0。若要逐项复刻此前81项补丁，还需要选择/选项清单。
- 用户截图已确认 Manager 1.29.0，对应研究参考Patcher 1.12.0。
- 用户已明确授权验证后公开干净源码并按Morphe原生流程发布。未完成的新APK手机验收仍需如实标注，不作为已验证事实。

新双源结果必须单独验收；不能以手工原型成功推断同次双源集成已经成功。

## 2026-09-11：独立启用及完整字幕菜单显示

用户确认关闭官方 Cookie 后仍能正常显示繁体自动翻译，仅 HansFix 不转换。移除官方状态桥，HansFix 安装时独立启用；直接指纹匹配原生字幕请求入口，既支持无官方 Captions，也兼容官方请求头注入。不新增 Cookie 获取，不修改官方开关。

补充设置菜单的本地字幕描述文字生成及其显示文字比较，使用同一语言映射，保留原始字幕轨道、语言代码、VSS ID、URL、hashCode 和选择逻辑的其他条件。原本“纯 UI”范围扩展到承载界面文本的本地描述消息，避免只改显示后导致选项匹配失败。不修改所有繁体字串或原生繁体字幕。最新要求优先于本文历史阶段表中的官方 Cookie 前置条件。

发布只运行快速检查、官方默认组合和无官方扩展组合各一次，以及本次输出位置核对。手机播放与菜单操作由用户验收。

## 2026-09-10: Caption memory and structure-based compatibility

User authorized a second independently selectable patch in this same source: global last subtitle language and automatic captions on each new video, always active with no feature switch and no per-channel behavior. User supplied original YouTube 21.13.164 / 1561063732. Official 1.42.0 supports this version. Existing HansFix must work alongside the new patch. Replace exact version restrictions with package/signature and unique structural checks; newer versions are user-triable, not automatically device-verified. See docs/CAPTION-MEMORY.md. The no-change-to-track-fields restriction remains: invoke native selection/construction APIs rather than modifying shared track objects. Build/publish is authorized; phone acceptance remains separate.

## 2026-09-11：独立字幕诊断补丁

用户授权实现可在手机Morphe构建的标准诊断patch。沿用同一源和dev原生发布流程，新增独立可选 Caption request diagnostics，默认不勾选；运行时默认关闭，内存有界且15分钟到期，提供查看/复制/清空。只观察字幕请求，严禁记录原URL/视频ID/凭证/正文/异常文本；不自动修复、重试、发送网络请求或修改现有字幕设置。使用官方add-on偏好声明协议，需要官方Captions和1.42.0+设置支持。仅做针对性验证，手机效果单独验收。详见docs/CAPTION-DIAGNOSTICS.md。

## 2026-09-11：诊断增量发布授权

用户要求下一版直接发布并明确跳过组合检查。本版仅增加会话内匿名视频/轨道/完整请求分组和现有字幕记忆调用的固定类别事件时间线；不新增宿主hook、不改变选择结果、不增加重试/冷却/合并。保留内存上限、默认关闭和隐私边界。只运行针对性测试、MPP构建和发布核验，不生成新的组合APK；设备覆盖由用户验证。新功能不能沿用上一版组合验证冒充本版已验证。
