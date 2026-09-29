# Temvio 宣传片

当前成片：`artifacts/Temvio-promo.mp4`，约 63.43 秒，1920 × 1080，30 FPS。全片围绕 Agent 的一条真实能力链展开：读当前上下文、通过类型化工具提出写入、调用确定性 Planner、等待用户确认、查询 ChangeLog、按用户请求预览撤销，最后同步已提交的日历数据。

## 节奏与分镜

配乐选用 Mixkit 的《Close Up》（Michael Ramir C.）。对下载音频的节拍分析约为 106 BPM，节拍间隔约 0.566 秒，四拍一小节。视频按 28 小节剪辑；主要场景切点每 2 或 4 小节一次，按钮点击、工具返回、预览与确认落在节拍上。音乐约在开头 0.05 秒出现首个强拍；无需额外点击音效。

| 时间 | 小节 | Agent 主线 |
| --- | --- | --- |
| 00.00–04.58 | 2 | Temvio 亮相：Agent 统筹每天，确定性核心守住规则 |
| 04.58–13.63 | 4 | Agent 读取当天日历与开放任务 |
| 13.63–22.69 | 4 | Agent 提议创建 Task；用户核对字段并确认 |
| 22.69–31.75 | 4 | `planner.previewFullReplan` 生成 PlanBranch；重新校验后由用户应用 |
| 31.75–40.80 | 4 | `history.timeline` 查 ChangeLog；用户请求撤销后再确认 `history.undo` |
| 40.80–49.86 | 4 | 展示 Agent → 类型化工具 → Domain / Planner → 权限 → 事务与历史的路径 |
| 49.86–58.92 | 4 | 已提交的 Task / FocusBlock 经端到端加密同步到另一台设备 |
| 58.92–63.43 | 2 | Temvio 收尾：把计划交给 Agent，把决定留给自己 |

## 源文件与交付

- `promo.html`：固定 1920 × 1080 画布、确定性 `window.renderAt(timeInSeconds)`，节拍与场景时间写在 `promo-metadata` 中。
- `assets/temvio-logo.png`：用户提供的组合 Logo 源稿。
- `assets/temvio-icon-transparent.png` 与 `assets/temvio-wordmark-transparent.png`：从源稿拆分出的透明底图标与字标。
- `artifacts/Temvio-promo.mp4`：H.264 High、yuv420p、AAC 48 kHz、faststart。
- `artifacts/Temvio-promo.audio.md`：音乐来源、曲名、作者与授权范围。
- `artifacts/Temvio-promo.render.json`：渲染参数、BPM、源文件哈希与输出哈希。
- `tools/create_bgm.py`：本地备用的原创合成配乐生成器；本片不使用其音轨。
- `artifacts/approved-stills-agent-centered/` 与 `artifacts/final-mp4-stills-agent-centered/`：编码前关键画面与成片抽帧验证。
- `artifacts/validation-agent-centered.json`：最终 MP4 的编码、时长、空黑帧与 17 个视觉检查点校验。

## 项目状态与画面说明

影片按项目原则“Agentic Surface, Deterministic Core”讲述产品。所示调用名对应当前 D9 Tool：`calendar.list`、`task.list`、`task.create`、`planner.previewFullReplan`、`history.timeline` 与 `history.undo`。任务优先级、预计与剩余用时、无截止日期均明确写在演示请求中；规划预览不会提前改动活动日历；历史撤销由用户再次请求并确认。

D9-01 仍在进行中；Desktop 可视验收与更广泛的写入工具验收仍开放。Agent UI 是依据当前类型化工具、权限、预览和审计契约制作的演示画面，不代表 D9 验收已完成。示例任务、安排和时间仅用于讲解交互。D8 已提交的 Task / FocusBlock 业务数据可进入 E2EE 同步；本片没有声称 Agent 对话历史已经同步（D9-02 另行规划）。

Temvio 是项目当前选定的工作品牌。本片适用于本地评审；商标与公开命名核查门槛仍见 `docs/TEMVIO_REBRAND_PLAN.md`。

## 验证

- `validate_video.ps1` 对最终 MP4 检查分辨率、帧率、时长、编码、faststart、最后一帧解码、黑帧空档和静帧 SSIM。
- 同时抽查 Agent 请求、读取结果、写入预览与确认、PlanBranch、显式撤销请求、撤销确认，以及同步与片尾画面。
