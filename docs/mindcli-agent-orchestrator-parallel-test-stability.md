# MindCLI `AgentOrchestratorTest` 并行测试稳定性说明

文档类型: 验证稳定性技术说明  
编写日期: 2026-08-10  
适用范围: `src/test/java/com/mindcli/agent/AgentOrchestratorTest.java`

## 1. 背景

`mvn test -Pquick` 在 `AgentOrchestratorTest.shouldRunIndependentStepsInParallel` 稳定失败，错误为并发峰值期望 2、实际 1。单独运行同一测试也复现。

## 2. 根因

根因包含两层:

- 测试中的 `DispatchingStubGLMClient` 按最后一条 user message 内容分发响应。Reviewer prompt 本身也包含“原始任务”和步骤描述“任务A/任务B”，因此 reviewer 匹配必须优先于 worker step 匹配。
- `AgentPool.acquire()` 先用 `availablePermits()` 选择候选，再调用阻塞式 `acquire()`。这两个动作不是原子的；两个并行线程可能同时看到 `worker-1` 有 permit，然后都选择 `worker-1`，第二个线程阻塞等待第一个释放，导致实际串行。

## 3. 修正方案

修正方案:

- planner prompt 仍优先匹配执行计划请求。
- reviewer prompt 的 `原始任务` 匹配放在 worker step 匹配之前。
- worker 的 `任务A` / `任务B` 匹配仍走并发屏障。
- `AgentPool.acquire()` 对候选 profile 使用原子 `tryAcquire()` 逐个尝试；如果所有候选当前都被占用，再阻塞等待第一个候选，保留原有背压语义。

## 4. 行为影响

该修改让已有“多个 worker profile 可并行执行”的设计真正生效，不改变 profile 选择规则、权限判断或 reviewer fail-closed 逻辑，也不降低并行断言强度。

## 5. 验证策略

```bash
mvn test "-Dtest=AgentOrchestratorTest#shouldRunIndependentStepsInParallel" -DskipTests=false
mvn test "-Dtest=AgentOrchestratorTest" -DskipTests=false
mvn test -Pquick
git diff --check
```
