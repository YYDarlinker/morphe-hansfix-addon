# 公开发布与回归门槛

用户已于2026-09-07提供干净原包和Manager 1.29.0截图，并授权验证后按Morphe要求公开源码和发布源。专家模式“官方＋独立addon同Session”不变。

## 发布前

- JDK21纯Java运行时测试通过；生产Kotlin/extension构建通过且runtime仅自有namespace。
- 精确原包/官方包哈希已验证；使用两个独立bundle加载入口进入同一个Patcher实例。
- 最小官方Captions组合、官方默认兼容组合、逆序输入组合能实际生成APK；缺官方源明确失败。
- 对输出核对bridge、同寄存器网络改写、4行UI和2摘要hook、模型字段不变、无重复class；对照官方-only结果分析任何非预期差异。
- index及公开历史无私有材料，工作区/源码提交状态清楚。

首版在 dev 通道预发布，并提供明确的 dev manifest 地址；稳定通道不提前宣称通过。手机安装、菜单打开和播放属于单独的运行态验收。发布说明必须如实标明该证据边界，不能把本地合成成功或旧手工APK的成功冒充新手机验收。

## 原生发布

使用恢复自官方模板的`.releaserc`和release workflow。安全检查、Python tests、JDK21 runtime tests先执行；之后由semantic-release计算版本并生成manifest、补丁列表、README、tag及当前版本`.mpp`。

- dev：预发布，Manager需开启该源预发布。
- main：正式通道。
- manifest必须指向本仓库相同版本的release `.mpp`，不能指向官方包或测试APK。
- 只开放干净源码和补丁包；用户APK签名在其Manager本地完成，不使用仓库或CI中的用户私钥。
- 当前CI只使用GITHUB_TOKEN，不引入用户APK/签名/会话secret。

发布后核验公开可读的manifest、版本/asset/hash、Morphe元数据解析与远程下载的bundle内容。正式功能状态看当前根manifest和release，不看历史准备记录。
