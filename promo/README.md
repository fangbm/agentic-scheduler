# Agentic Scheduler 宣传片

当前成片：`artifacts/Agentic-Scheduler-promo.mp4`，90 秒，1920 × 1080，30 FPS。

## 故事线

| 时间 | 内容 |
| --- | --- |
| 00–09 秒 | 产品定位：把计划变成可执行的日常 |
| 09–20 秒 | Agenda / Day：区分日程事件、任务和专注时段 |
| 20–31 秒 | 确定性 Planner：PlanBranch 先预览，再应用或取消 |
| 31–42 秒 | 操作历史：记录变更并预览 Undo |
| 42–53 秒 | D8：客户端加密、服务器转发密文 |
| 53–67 秒 | D9 Agent：类型化工具、操作预览、用户确认 |
| 67–77 秒 | Agent 写入路径：校验、权限、事务和历史 |
| 77–84 秒 | 路线图：已完成、进行中和规划中里程碑 |
| 84–90 秒 | 品牌片尾 |

## 源文件与交付

- `promo.html`：固定 1920 × 1080 画布和确定性 `window.renderAt(timeInSeconds)`。
- `artifacts/Agentic-Scheduler-promo.mp4`：H.264 High、yuv420p、AAC 48 kHz、faststart。
- `artifacts/Agentic-Scheduler-promo.audio.md`：音频说明。
- `artifacts/Agentic-Scheduler-promo.render.json`：源文件和输出文件哈希、渲染参数。
- `artifacts/approved-stills/`：编码前批准的场景静帧。
- `artifacts/final-mp4-stills/`：从成片抽取并用于关键帧比对的静帧。

## 项目状态与画面说明

影片按 `docs/ROADMAP_D5_D9.md` 的 D5–D10 路线组织内容。D9 标为进行中，D9-02/03 标为后续阶段，D10 标为规划中。Agent 桌面画面是依据当前类型化工具、权限、确认和审计契约制作的演示 UI，不代表 D9 或 Desktop 可视化验收已经完成。示例日历、任务和预览数据仅用于表达交互。

音轨是渲染脚本生成的临时氛围占位音轨，因此这是供评审的成片版本，不能当作已完成授权的公开发行音轨。公开发布前请替换为已授权音乐，或明确批准使用这条占位音轨。

## 验证

- `validate_video.ps1`：90.0 秒、1920 × 1080、30 FPS、2700 帧、H.264 High、yuv420p、AAC 48 kHz、faststart 均通过。
- 最后一帧可解码，没有持续 0.4 秒以上的黑帧空档。
- 10 个最终 MP4 检查帧与批准静帧 SSIM 为 0.9956–0.9986。
