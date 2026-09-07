# 当前准备结果

日期：2026-09-07。

## 已完成

- 目标已固定为专家模式“官方源＋独立addon”同一Session，不使用替代官方源的全量fork。
- 从官方公开模板建立独立工程，原型私人材料不参与源码导入。
- 固定参考：官方bundle v1.41.0、Manager v1.29.0、Patcher v1.12.0、patches插件1.3.4。
- 配置并验证JDK 21.0.12.1、Gradle 9.7.1；沿用wrapper SHA-256。
- addon基础 `:patches:buildAndroid` 已成功；没有生产patch注册，产物不发布。
- 应用内extension改为Java-only，排除不需要的Kotlin/annotations runtime。当前runtime只有2个自有类。
- 建立Git index内容检查、命名空间检查及29项准备单元测试。
- 准备CI只读，仅检查源码和安全门槛，无release或上传资产步骤。

## 当前不是成品

生产指纹、桥接、网络/UI hook、两份真实bundle的同次改包、Manager加载和实机验收均未完成。不能将基础构建成功作为这些步骤的替代证明。

尚缺经验证的原始YouTube 21.07.247 APK。已有已改包APK仅用于行为与结构参照，不重新套官方完整补丁。

## 本机开发配置

使用完整JDK21并将JAVA_HOME设置为该JDK；SDK写在忽略的local.properties。GitHub Packages凭据沿用受保护的Gradle用户配置，不放入仓库。

```text
./gradlew :patches:buildAndroid --no-daemon
python tools/check_addon_runtime.py patches/build/libs/patches-0.1.0-dev.0.mpp
python -m unittest discover -s tests -p "test_*.py"
python tools/check_publish_safety.py
```

Windows使用gradlew.bat。SDK XML版本和Gradle弃用警告已观察到，未导致固定版本构建失败；不因此顺带升级框架。

## 公开发布

仓库先私有，避免把准备工程误当可用源。未来正式源需公开可下载的main manifest和.mpp，或另选可访问的托管方式。公开和release在源码安全及功能门槛通过后确认。
