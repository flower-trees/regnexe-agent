# 发布前验证

运行环境：Java 17、Maven，脚本相关测试还需 Python。项目正式版本为 `0.1.3`，依赖 `j-langchain 1.0.19`。

## 本地回归与打包

```bash
mvn clean verify -Plocal-tests -Dgpg.skip=true
```

依赖全部已缓存时可加 `-o`。`local-tests` 显式选择事件、插件市场、缓存安装加载、配置兼容和作用域测试，不调用真实模型。默认 `mvn test` 的选择不变。配置兼容测试有一项依赖本机 Claude 插件，未安装时会跳过，因此要检查测试统计，不能仅看 BUILD SUCCESS。

构建生成主 JAR、源码包和 Javadoc 包，跳过签名，不执行上传。此结果不代表全量集成验收完成。Javadoc 插件当前配置 `failOnError=false`，发布前仍需检查生成的文档和构建日志。

## 真实模型验收

确认模型配置、Key 和可用额度后逐项运行。下面的测试会访问真实服务，可能产生费用；不要将 Maven `-o` 当成模型网络隔离。

```bash
mvn test -Dtest=ExampleReadme01MultiToolTest
mvn test -Dtest=RegnexeAgentExecuteSkillTest
mvn test -Dtest=Example06SessionMemoryTest
mvn test -Dtest=ExampleReadme09ObservabilityTest
```

另行验收任务暂停、补充指令恢复、失败任务恢复、跨轮摘要和 `write_todos`。旧 `ExampleReadme08PauseResumeTest` 已删除，不能当成已通过的恢复证据；当前没有覆盖全部恢复路径的自动测试。

验收跨进程恢复时，必须使用持久化 TaskStore；默认 InMemoryTaskStore 仅支持当前进程。对于具有外部副作用的工具，确认恢复重试时的幂等处理。

## 签名与发布

```bash
mvn clean verify -Plocal-tests
```

这一步需要本机 GPG 配置，不包含上传；执行后检查主包、源码包、Javadoc 包及其签名。最终集成验收完成后再发布。

确认 `j-langchain 1.0.19` 已能从目标远程仓库获取，不能仅凭本地 Maven 缓存判断发布完成。核实 Git origin 及 POM 仓库地址，再按流程合并、打标签和发布。Central 插件配置了自动发布，`deploy` 会上传并发布，仅在正式发布步骤执行。

## 本轮环境检查结果

Git origin 已统一为 `flower-trees/regnexe-agent`；Maven Central 的 j-langchain 1.0.19 POM 可下载。

验收测试已统一使用 `Vendor.DEEPSEEK` + `deepseek-v4-flash`，独立子 Agent 示例使用 `deepseek:deepseek-chat`，设置 `DEEPSEEK_KEY`。这两个模型在 DeepSeek 官方接口的最小请求均已成功。此前 `model_not_found` 来自阿里云接口，不代表 DeepSeek 官方接口不支持同名模型。

当前自动执行环境限制 Java 联网及 GPG 密钥目录访问，因此完整集成测试和签名需在具有相应权限的本机终端完成。网络错误后的 TIMEOUT 不能作为验收成功依据。
