# Agentic Scheduler — NVIDIA DGX Spark Agent Skills 参赛材料（草稿）

> 状态：预赛材料草稿，必须在提交前用官方报名/规则材料复核。  
> 截止：2026-09-29

## 项目一句话

Agentic Scheduler 是一个可以真正执行日程工作的本地优先智能调度器：用户用自然语言提出任务，Agent 通过受约束的 typed Tools 读取日程、提出计划预览，经用户确认后调用现有 Planner 和 Mutation 事务完成修改，并保留 MutationId/History 审计。

## DGX Spark 价值

DGX Spark 运行 Agent Gateway 连接的本地 vLLM 兼容模型。模型地址、模型名称和模型凭据留在 DGX 侧；Desktop 只配置 Gateway 地址和比赛环境注入的认证令牌。推理可以看到本次请求所需的日程事实，但 Gateway 不保存日程数据库。

## 演示流程

1. 用户询问当前日程和任务。
2. 用户要求创建或调整任务。
3. Agent 返回结构化 Tool proposal，而不是直接写数据库。
4. Desktop 展示确认内容；拒绝操作不会产生写入。
5. 用户确认后，现有应用服务和 Planner 执行事务。
6. UI 展示 PlanBranch 预览、应用结果、MutationId 和 History 记录。

## 技术边界

```text
模型 → typed Tool proposal → Desktop validation/permission
     → application service / Planner → Mutation + ChangeLog
```

模型不能调用 SQL、Shell、任意 HTTP 或任意 JSON 写入工具。主产品 `main`
仍然保持 Local-first/E2EE；比赛分支是独立的演示 trust model。

## 提交前必须补齐

- 官方规则/报名材料中的准确评分项和格式；
- 远程 DGX 地址、模型名称、vLLM 启动方式和健康检查证据；
- 运行截图、Tool trace、PlanBranch 预览/确认截图；
- 3–5 分钟中文演示脚本和失败恢复说明；
- 实际测试命令与结果，不得填写未运行的验证。
