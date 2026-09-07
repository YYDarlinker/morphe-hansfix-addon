# YYDarlinker HansFix Addon

**独立附加源 · 专家模式 · 官方源与本源在同一次改包中同时使用。**

**PREPARATION ONLY：正式 HansFix patch 尚未实现／发布，目前不能作为可用源添加。**

本仓库不替代、不打包完整 Morphe 官方补丁库，不要求用户切换到全量 fork。它是从官方补丁模板建立的独立工程，用于准备把已验收的“简体请求＋纯 UI 菜单改名”移植为一个专家模式 addon。

## 冻结目标

```text
未打补丁的 YouTube 21.07.247 原包
  + Morphe 官方源（包含 Captions / Cookie 功能）
  + 本 HansFix Addon 源
  → 专家模式同一次 Patcher Session
  → 用户本地 Manager 签名
```

不是两次改包，不是让用户先安装再打第二个APK补丁，也不是用本源代替官方源。

已成功的手工原型是在官方改包结果上追加修复；它是行为基线，但不能替代同一次双源 Session 的顺序和兼容验证。

## 当前准备状态

- 独立命名空间：`io.github.yydarlinker.hansfix`，不复制官方 extension 类。
- Java-only runtime，避免无必要地附带第二份 Kotlin runtime。
- 官方模板与 Manager/Patcher/官方补丁参考版本固定在 `UPSTREAM.lock.json`。
- 只有准备标记，没有注册任何可选择的功能补丁，未修改应用 hook。
- 未发布 `.mpp` 和根 `patches-bundle.json`；本地基础 bundle 只用于构建验证。
- 不包含用户 APK、DEX/smali、私钥、Cookie截图、录屏或私人迁移包。

## 可行性方向

Manager 专家模式支持多个源合并进入同一个 Session。Patcher 1.12.0 在所有 execute 之后执行 finalize，因此可研究在 addon finalize 中重新定位并检查官方已加入的 Cookie 扩展，再做必要的附加注入。**这条路径尚需真实双源测试，不能只靠补丁名称排序或源排列顺序。**

必须在官方 Captions 缺失、版本不匹配、指纹不唯一或已重复注入时明确失败，不能默默生成缺功能 APK。

## 开发入口

- [需求与阶段边界](SPEC.md)
- [双源技术设计](docs/EXPERT-MODE-DESIGN.md)
- [前期环境与验证](docs/PREPARATION.md)
- [发布门槛](docs/RELEASE-GATES.md)
- [安全边界](SECURITY.md)

```text
python -m unittest discover -s tests -p "test_*.py"
python tools/check_publish_safety.py
./gradlew :patches:buildAndroid --no-daemon
```

Windows 使用 `gradlew.bat`，JDK 21。构建产物默认不跟踪、不上传；“基础构建成功”不等于“功能已经实现”。

## 来源

基于 `MorpheApp/morphe-patches-template` 的固定公开模板创建；原 LICENSE、NOTICE 和版权保留。模板示例及自动发布工作流仅保存在 `docs/template-reference`，不会执行。这里没有复制完整官方补丁源码。

这是 YYDarlinker 的独立兼容项目，不是 Morphe 官方产品。
