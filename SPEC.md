# SPEC — 官方源＋HansFix 独立源同次改包

## 硬要求

1. Morphe 专家模式，官方源与本 addon 源在**同一次改包 Session**中同时使用。
2. 不用全量 fork 替代官方源，不重复打包官方扩展类，不重复添加 Cookie 设置。
3. 复用官方 Cookie 开关，保留已验收的精确 URL 改写与纯 UI 改名语义。
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

## 2026-09-10: Caption memory and structure-based compatibility

User authorized a second independently selectable patch in this same source: global last subtitle language and automatic captions on each new video, always active with no feature switch and no per-channel behavior. User supplied original YouTube 21.13.164 / 1561063732. Official 1.42.0 supports this version. Existing HansFix must work alongside the new patch. Replace exact version restrictions with package/signature and unique structural checks; newer versions are user-triable, not automatically device-verified. See docs/CAPTION-MEMORY.md. The no-change-to-track-fields restriction remains: invoke native selection/construction APIs rather than modifying shared track objects. Build/publish is authorized; phone acceptance remains separate.
