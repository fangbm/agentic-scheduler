# DGX Spark 黑客松 · Web Demo 冲刺计划

> 日期：2026-09-28 · 预赛材料截止：2026-09-29（以官方通知和个人提交页面为最终准则）
>
> 分支：`hackathon/web-demo-sep29`，从既有 `hackathon/dgx-spark-agent-skill` 独立切出。**不会**合并未验收的 D9 PR #9，也不修改 main。

## 0. 交付目标

提交前做出可录屏的、真实标记运行模式的 Web 界面：展示 Today 日程、任务、Agent 输入与回复、结构化 Tool 操作预览、用户确认/拒绝及执行记录。

产品承诺分级：
- **UI/Sandbox 已实现**：静态页面与本地 Node bridge，无须 DGX 就能预览交互；示例任务和执行记录仅驻留本地内存，刷新/重启不承诺真实持久化。所有沙盒反馈须显著标注。
- **Gateway 可接入**：仅在本地 bridge 配置 `AGENT_GATEWAY_URL` + `AGENT_GATEWAY_TOKEN` 时，后端转发到旧比赛分支的 `POST /v1/agent/turn`。凭据不进入浏览器或 Git。
- **真实竞赛验收尚待完成**：DGX/vLLM 远程实测和基于既有 Kotlin Application/Planner/Room 的本地受信执行桥。这两项未通过前，不可将沙盒操作作为真实持久化/Planner 证据。

## 1. 参考资料与视觉方向

代码仓库当前**没有提交图片格式的概念板素材**；可查文字依据：`docs/ROADMAP_D5_D9.md` 中 D10 Visual/product reference。若最终参考图在聊天附件、Figma 或外部资源中，后续仅调整样式，不阻塞当前冲刺。

沿用文档约定：现代简洁、柔和留白、圆角分区、清晰的 Event/Task/FocusBlock 语义色、明暗主题、可见的 Agent 和独立的变更预览。此冲刺暂用仓库当前技术名 `Agentic Scheduler`；Temvio 公共品牌切换另受命名审查计划约束，不能在未经审查时当作最终公开商标。

首屏信息架构（Desktop-first，移动端自动堆叠）：
1. 左侧窄导航：Today、Calendar、Tasks、Agent、状态与运行模式；
2. 中央工作台：日期/日程时间轴、重点任务列表、空状态；
3. 右侧 Agent 面板：用户输入、消息、Tool 结构化追踪、醒目的变更预览和「确认 / 拒绝」。

视觉令牌：蓝紫 `#5956E9`、薄荷 `#2CBF91`，浅底 `#F5F7FC`，文字 `#17203B`，卡片 16–24px 圆角；深色模式使用同一语义色。尊重 `prefers-reduced-motion`；控件可使用键盘。

## 2. 时间盒与 P0/P1

| 顺序 | 优先级 | 交付物 | 可验证条件 |
|---|---|---|---|
| A | P0 | 静态 Web 首屏与响应式组件 | 在 `http://127.0.0.1:4173` 打开，窄屏/宽屏均可读 |
| B | P0 | 本地 Node bridge、示例日程及只读 API | 无 DGX 时标注「演示沙盒」，页面正常演示；不得假装真实模型 |
| C | P0 | Gateway 请求代理与 Tool 提案 | Gateway 凭据仅在 bridge 环境；真实请求失败明确报错、不自动伪造成功 |
| D | P0 | `task.create` 预览/确认/拒绝，内存演示执行 | 拒绝零写入，成功仅标注「沙盒操作」；其他写 Tool fail closed |
| E | P0 | DGX 端模型/网关真实连通与一次完整 trace | 保存真实 HTTP/Tool 结果（凭据遮盖） |
| F | P0 | 中文征文、录屏、架构图、运行手册 | 截止前先上传可交付版本，不把未测功能写成已完成 |
| G | P1 | Kotlin 本地只读/事务执行桥，真实 Planner Preview/Apply | 必须走 Application / Planner / Mutation，方可称为真实调度 |
| H | P1 | UI 美化、细节动画与扩展工具 | 不得阻塞 E/F |

如果 DGX 部署不稳：停止新增页面，优先保存有据可查的界面演示及本地可用流程，并如实注明模型/持久化验收状态。

## 3. 安全/架构界限

```text
浏览器 (展示层，无网关 Token、无模型密钥)
   ↓ 同源 API
Node web-demo bridge（仅绑定 127.0.0.1，单人演示，沙盒数据）
   ├─ 可选 → DGX Agent Gateway → vLLM 模型（受限 Tool proposal）
   └─ 沙盒 typed Tool 校验 → task.create 人工确认 → 沙盒内存数据
正式扩展：浏览器 → 本机 Kotlin bridge → Application/Planner/Room
```

本次 Web 端不提供通用 SQL、Shell、任意 URL Tool，不接受模型直接写任务；在完成受信 Kotlin bridge 前，不声称 Web 的内存任务就是产品权威数据。不向 Web 客户端提供 Gateway token，不允许 Node bridge 默认公网监听。记录同步/E2EE 和数据库加密不在此沙盒交付范围内。

## 4. 录屏脚本（约 2–3 分钟，可裁剪）

1. 展示首屏 Today 时间轴和任务概览；
2. 显示当前运行模式，说明沙盒 UI 与 DGX 实测证据的区别；
3. 向 Agent 输入「列出今天任务」，展示只读 Tool trace（如真实网关可用，用真实模型返回的 Tool call）；
4. 输入「创建一个准备演示的任务」，核对表单化预览，并示范先拒绝一次；
5. 再次提议后确认，展示任务卡和操作记录；如果仍是沙盒，屏幕始终保留「演示沙盒」标签；
6. 如 Kotlin 真正接入完成，独立展示真实 MutationId/History、Planner preview/apply；否则说明下一阶段计划，不混充证据。

## 5. 验收清单

- [ ] 宽屏和移动端页面均可用，明暗主题和键盘 Enter 发送可用
- [ ] 无 Gateway 配置时 UI 明确标注 sandbox，不对外宣称 DGX 推理成功
- [ ] Gateway 不可用返回明确错误、密钥不出现在浏览器响应/前端源码/日志
- [ ] task.create 必须先确认，拒绝后任务与操作记录不变
- [ ] 不支持的写 Tool 不执行；只读工具结果不变造业务数据
- [ ] 本地脚本/测试通过，记录实际执行命令和结果
- [ ] DGX 真实健康探测与模型 Tool call 证据（单独 Gate）
- [ ] Kotlin/Room 真写入与 Planner 证据（可作为下一阶段，不捏造）
- [ ] 实际提交要求和链接由赛事官方页面复核；提交前备份录屏和材料

## 6. 启动方式

见 `apps/web-demo/README.md`。要求 Node.js 20+；无需第三方 npm 依赖。
