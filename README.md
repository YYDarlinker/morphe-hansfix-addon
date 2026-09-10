# HansFix Addon for Morphe

**保留官方源，使用专家模式同时选择“官方源＋HansFix”，在同一次改包中完成。**

这是一个独立附加补丁，不是官方完整补丁库的 fork，也不要求先安装再二次修改 APK。

## 功能

同一源提供两个独立补丁：

- **HansFix - Simplified Chinese captions**：复用官方字幕 Cookie 开关，将繁体自动翻译兼容入口的请求改为简体，并只修改菜单显示。原生繁体字幕不受影响。
- **Remember subtitle language**：全局记住最后手动选择的字幕语言，新视频自动打开字幕，包括可用的自动翻译。选中补丁后默认生效，没有运行时设置开关，不按频道区分。手动关闭 CC 只影响当前视频，不清除已记住语言。首次使用或目标语言缺失时回退到原生默认字幕。

两个补丁可以一起选择，扩展只合并一次。字幕记忆保存语言代码，不跨视频复用 URL、轨道对象、签名参数或 Cookie。

## 使用

1. 保留官方 Morphe 补丁源；本轮目标为 **Manager 1.29.0 / Patcher 1.12.0 / 官方补丁 1.42.0 / YouTube 21.13.164（1561063732）**。
2. 继续使用原开发源：`https://raw.githubusercontent.com/YYDarlinker/morphe-hansfix-addon/dev/patches-bundle.json`，开启该源预发布并刷新。不要使用旧 v1.0.0-dev.1 测试新版 APK；它限制 21.07.247。
3. 选未打补丁的 Google YouTube 原 APK，进入专家模式。官方补丁必须包含 **Captions**；本源选择所需的两个补丁。
4. 同一次改包后由 Manager 本地签名安装。覆盖更新沿用自己的原签名密钥，不必卸载或清数据。
5. 要使用 HansFix 简体兼容入口，继续保留有效 Cookie、开启原 Cookie 开关并重启。在视频字幕菜单手动选择一次所需语言，后续视频由记忆补丁自动选择。

## 兼容边界

本次取消 addon 的精确版本号限制，以签名、包名和实际结构唯一匹配决定能否应用。你可以直接尝试新版原 APK；结构不匹配会明确失败。**允许尝试不代表该版本已经实机验证**，官方源自己的版本限制也仍然有效。

旧 HansFix v1.0.0-dev.1 的双源手机验收记录保留。字幕记忆以及新的 21.13.164 组合需由用户单独实机确认。本地合成不能代替手机安装和字幕播放验收。

新功能调用 YouTube 当前视频原生字幕选择与翻译列表，不新增翻译服务。无字幕或翻译不可用时无法凭空生成字幕；优先选择记住语言的原生字幕，否则尝试当前视频的自动翻译。特殊播放路径（例如部分直播、Shorts 或投屏）未单独承诺。

详见 `docs/CAPTION-MEMORY.md`。未来只针对实际受影响的入口适配，不默认重复完整测试矩阵。

## 开发与发布

JDK 21；Gradle wrapper、Morphe patcher 和构建插件版本见 `UPSTREAM.lock.json`。

```text
./gradlew :patches:buildAndroid --no-daemon
python tools/run_runtime_tests.py --jdk <JDK21目录>
python -B -m unittest discover -s tests -p "test*.py"
python tools/check_addon_runtime.py patches/build/libs/patches-<version>.mpp
python tools/check_publish_safety.py
```

同 Session 集成入口是单独的 `:patches:runIntegration`，需要本机原包及官方 bundle，不属于普通 build 或公开 CI；用法见测试目录中的 README。私人原包和输出 APK 必须保存在仓库外。

发布沿用官方模板的 semantic-release：dev 预发布、main 正式版，自动生成 manifest、补丁列表、变更记录、tag 与 `.mpp` 资产。不要手工上传 APK 代替补丁包。

## 补丁列表

<!-- PATCHES_START EXPANDED -->
> **[v1.0.0-dev.1](https://github.com/YYDarlinker/morphe-hansfix-addon/releases/tag/v1.0.0-dev.1)**&nbsp;&nbsp;•&nbsp;&nbsp;`dev`&nbsp;&nbsp;•&nbsp;&nbsp;1 patches total
<details open>
<summary>📦 YouTube&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 21.07.247 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [HansFix - Simplified Chinese captions](#hansfix-simplified-chinese-captions) | Use with official Captions in expert mode. Maps Traditional auto-translation to Simplified and changes UI labels only. YouTube 21.07.247 (1561056418). |  |

</details>

<!-- PATCHES_END -->

## 来源和安全

源自 Morphe 官方补丁模板，保留 LICENSE、NOTICE 和版权信息。本项目由 YYDarlinker 维护，不是 Morphe 官方产品。

源码仓库不包含 APK、完整反编译代码、私钥、密码、Cookie 截图或录屏。检查器读取 Git 的实际 index；发布任务只接受本项目当前版本的 `.mpp`。详见 `SECURITY.md`、`SPEC.md` 和 `docs/RELEASE-GATES.md`。
