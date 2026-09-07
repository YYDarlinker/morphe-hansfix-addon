# HansFix Addon for Morphe

**保留官方源，使用专家模式同时选择“官方源＋HansFix”，在同一次改包中完成。**

这是一个独立附加补丁，不是官方完整补丁库的 fork，也不要求先安装再二次修改 APK。

## 功能

- 沿用官方 `Captions` 的字幕 Cookie 开关和设置。
- 开关生效时，将精确的繁体自动翻译请求目标改为简体，不改源语言、签名参数、PoToken、Cookie 值或其他请求参数。
- 将自动翻译里的繁体兼容入口显示为 **中文（简体）**，同步处理选中摘要；不修改原轨道对象、共享列表、选择身份或协议字段。
- 若原生简体选项已经共存，兼容入口会标注“中文（简体，兼容入口）”，不删除或重排原选项。
- 关闭原开关并重启应用后恢复原行为。

## 使用

1. 保留 Morphe 官方补丁源。已验证的组合为 **Manager 1.29.0 / 官方补丁 v1.41.0**。
2. 首个开发通道使用：`https://raw.githubusercontent.com/YYDarlinker/morphe-hansfix-addon/dev/patches-bundle.json`。确认该源已开启预发布。裸仓库地址默认读取 main；main 稳定通道尚未发布时不要用裸仓库地址代替 dev 源。
3. 使用未打补丁的 **YouTube 21.07.247，versionCode 1561056418，minAPI28** 原 APK。不要选当前已安装的 Morphe 成品 APK 重复套补丁。
4. 进入专家模式，选择官方所需补丁（**必须包括 `Captions`**），同时勾选本源的 **HansFix - Simplified Chinese captions**。
5. 在这一次操作中完成改包与本地签名。若要覆盖旧安装，请在 Manager 使用原来的签名密钥；不要将密钥上传到本仓库或 Actions，也不要先卸载/清数据来绕过签名问题。
6. 保留有效字幕 Cookie，开启原 Cookie 开关，重启后在自动翻译菜单选择“中文（简体）”。

**首版先按原生 dev 预发布供 Manager 验证**，不冒充完成新APK手机验收。**dev 预发布**需要在该源设置中开启预发布；main 正式发布使用默认通道。不要把本源单独使用，也不要用另一个完整官方 fork 替代官方源。

## 兼容与验证边界

- 首个目标只限定于上述原包；不宣称所有 YouTube 或官方补丁未来版本都兼容。
- 使用独立 runtime namespace，不复制官方扩展类、不附带第二份 Kotlin runtime。
- 在所有 patch 的 execute 之后重新定位并检查官方 Cookie 注入结构；通过同一个官方缓存状态的只读 bridge 工作，不靠源排列或补丁名称来等待覆盖。
- 缺少官方 `Captions`、版本/结构不符、指纹多义、输入已打本补丁或重复 addon 会明确失败，不会静默生成缺功能包。
- 原型的用户验收不是新 addon APK 的手机验收。仓库验证记录明确区分纯 Java 测试、真实两 bundle 的本地 Patcher Session、输出 DEX 检查与手机实际使用。
- 字幕服务可用性及 Cookie 过期仍由原机制决定；本补丁不提供外部翻译服务或新的字幕时间轴算法。

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
补丁列表由首次原生发布自动生成。
<!-- PATCHES_END -->

## 来源和安全

源自 Morphe 官方补丁模板，保留 LICENSE、NOTICE 和版权信息。本项目由 YYDarlinker 维护，不是 Morphe 官方产品。

源码仓库不包含 APK、完整反编译代码、私钥、密码、Cookie 截图或录屏。检查器读取 Git 的实际 index；发布任务只接受本项目当前版本的 `.mpp`。详见 `SECURITY.md`、`SPEC.md` 和 `docs/RELEASE-GATES.md`。
