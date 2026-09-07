# 发布门槛

当前仅私有准备仓库，根 patches-bundle.json 故意不存在，没有release。不要现在把仓库当作可用源添加。

正式发布后，用户保留官方源，在专家模式同时选择官方所需功能和本源的 HansFix；不能勾选另一个完整fork来替代官方源。

普通 GitHub 地址会被 Manager 解析为 main/patches-bundle.json，再读取其 download_url。不是自动搜索最新Release。正式manifest需有 created_at、description、download_url、version，其他字段依当前Manager DTO。

私有仓库不能作为匿名可下载的普通raw源。准备阶段先私有保存；正式源需审计后确认公开，并提供可公开下载的.mpp，或另行选择明确可访问的托管方式。

发布前必须完成：独立addon实现 → 干净原包的官方＋addon同Session测试 → 用户新产物验收 → 隐私和源码审计 → 正式公开确认 → 按官方模板semantic-release维护main/dev manifest及release → 验证实际Manager双源加载。

上游release配置保存在docs/template-reference中。以后基于官方原生流程调整，不用手工造一个下载URL或把手工APK冒充.mpp。无功能的准备bundle不上传为Release。
