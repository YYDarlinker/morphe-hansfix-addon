# 安全边界

仅允许源代码、合成测试、公开模板来源记录及非敏感设计资料进入仓库。私人迁移工程不作为 git 工作目录。

禁止提交用户 APK/APKM、DEX/smali、签名容器、密码、Cookie值、截图/录屏、私人迁移 ZIP 或任何构建结果。官方源成品和原包只在本地受限测试目录，不上传 Actions artifact。APK 签名在用户的 Manager 本地完成，addon源码和CI不需要用户私钥。

上传检查读取 Git 实际 index 的 blob，而不是只看工作树或 `.gitignore`。唯一允许的二进制源码依赖是来自固定公开模板且哈希一致的 Gradle wrapper JAR。未知二进制拒绝；活动工作流必须通过固定内容审查，根manifest需通过严格的本仓库/版本/下载路径验证。

源代码检查任务只读；原生release任务获准使用必要的GitHub写入/attestation权限，但仅接受GITHUB_TOKEN。实际Packages凭据仅用于下载构建依赖，绝不打印或提交。用户APK、密钥与Cookie始终不得进入CI。

检查器是防误上传措施，不是所有泄漏类型的完备检测器；仍需人工审查全部 staged 文件和明确来源。任何异常只报告路径及规则，不输出敏感值。
