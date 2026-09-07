# 双源专家模式设计与证据

核验日期：2026-09-07。用户要求以双源同Session为准，单源集成方案不采用。

## 已证实

- Manager v1.29.0 的专家模式可将多个源选中的 patch 汇总进同一个 Session，提示兼容风险后继续。
- Manager 将各 bundle 分别加载；不能假定跨 bundle 的 Kotlin依赖实例会自动绑定。
- Patcher v1.12.0：先处理已选择patch及其依赖的 execute；全部 execute 后，对成功patch按执行顺序逆序运行 finalize。
- BytecodePatch 的 extension 在该 patch execute 之前合并。
- 同名 class 进入实验性 ClassMerger；它不是有明确覆盖保证的“后一源替换前一源方法”机制。

## 待验证的实现方向

1. addon 只打包独立 Java runtime，不复制官方 Cookie/Settings 类或整个官方库。
2. execute阶段准备自身扩展和必要输入检查；finalize阶段重新解析当前 mutable bytecode，而不使用 execute前缓存的指令下标。
3. finalize检查官方 Cookie设置/扩展/网络注入的结构前置条件。缺少官方 Captions、匹配不唯一、版本不支持或官方补丁失败时明确失败。
4. 官方开关的单一状态来源需有明确桥接：优先评估在已存在官方类中注入唯一命名的只读 accessor，返回同一缓存布尔值，供自有helper使用。不得重新维护一套Cookie开关状态，也不导出Cookie值。
5. 保持URL实际改写位于官方Cookie判断之前且使用同一请求寄存器；保留原参数和数据流。
6. UI只改菜单显示对象的标题／摘要，不改原轨道对象。Java helper只用普通字符串/布尔值；混淆字段由patch在目标APK中推导，不写死原型类名。
7. 仍要检查其他官方 finalize 是否继续修改相关方法。不能把“所有execute已完成”误说成“所有其他操作已完成”。
8. 用两个独立bundle的真实Session验证源/选中patch输入顺序变化。不要用同一个Kotlin工程里的两个函数测试冒充多ClassLoader整合测试。

以上只是可行的候选设计；桥接、指纹、hook和双源运行均未实施或验收。

## 固定源码依据

- [Manager专家模式警告与放行](https://github.com/MorpheApp/morphe-manager/blob/1fd754eb690d967053fccdccb44e075e9aeb72f9/app/src/main/java/app/morphe/manager/ui/screen/home/ExpertModeDialog.kt#L477-L507)
- [多源进入Session](https://github.com/MorpheApp/morphe-manager/blob/1fd754eb690d967053fccdccb44e075e9aeb72f9/app/src/main/java/app/morphe/manager/patcher/runtime/process/PatcherProcess.kt#L65-L126)
- [逐bundle加载](https://github.com/MorpheApp/morphe-manager/blob/1fd754eb690d967053fccdccb44e075e9aeb72f9/app/src/main/java/app/morphe/manager/patcher/patch/PatchBundle.kt#L59-L102)
- [Patcher执行与finalize](https://github.com/MorpheApp/morphe-patcher/blob/ac0d688eaacb7ece80b65ebf719b252f69455783/src/main/kotlin/app/morphe/patcher/Patcher.kt#L72-L145)
- [extension在execute前合并](https://github.com/MorpheApp/morphe-patcher/blob/ac0d688eaacb7ece80b65ebf719b252f69455783/src/main/kotlin/app/morphe/patcher/patch/Patch.kt#L257-L262)
- [同名class合并边界](https://github.com/MorpheApp/morphe-patcher/blob/ac0d688eaacb7ece80b65ebf719b252f69455783/src/main/kotlin/app/morphe/patcher/util/ClassMerger.kt#L22-L44)
