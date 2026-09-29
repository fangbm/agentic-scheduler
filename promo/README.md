# Temvio 宣传片

当前成片：`artifacts/Temvio-promo.mp4`，60 秒，1920 × 1080，30 FPS。按用户提供的 TermPop 参考片重新剪辑，采用更紧凑的段落、更连续的鼠标操作和页面状态反馈，并复用参考片中的完整音轨。

## 故事线

| 时间 | 内容 |
| --- | --- |
| 00–04 秒 | Temvio 品牌露出与产品主张 |
| 04–14 秒 | Agenda / Day：鼠标新建任务、逐字输入、保存并加入待办 |
| 14–24 秒 | 确定性 Planner：点击生成预览，展示 PlanBranch，再应用提案 |
| 24–33 秒 | 操作历史：定位最近操作、查看补偿预览并确认撤销 |
| 33–41 秒 | D8：加密信封从 Android 中继到 Desktop，展示验证与解密 |
| 41–55 秒 | D9 Agent（进行中）：输入请求、提交类型化工具调用、审阅并确认 |
| 55–60 秒 | Temvio 品牌片尾 |

## 源文件与交付

- `promo.html`：固定 1920 × 1080 画布和确定性 `window.renderAt(timeInSeconds)`。
- `assets/temvio-logo.png`：用户提供的原始组合图，作为品牌源稿保留。
- `assets/temvio-icon-transparent.png` 与 `assets/temvio-wordmark-transparent.png`：从组合图拆分出的透明底日历图标和 Temvio 字标，片头、产品界面和片尾分别组合使用。
- `artifacts/Temvio-promo.mp4`：H.264 High、yuv420p、AAC 48 kHz、faststart。
- `artifacts/Temvio-promo.audio.md`：音频说明。
- `artifacts/Temvio-promo.render.json`：源文件和输出文件哈希、渲染参数。
- `tools/create_bgm.py`：生成确定性的原创 60 秒立体声合成配乐，可用于本地备用；当前交付片使用用户提供的参考片完整音轨。
- `artifacts/approved-stills/`：编码前批准的场景静帧。
- `artifacts/final-mp4-stills/`：从成片抽取并用于关键帧比对的静帧。

## 项目状态与画面说明

影片按 `docs/ROADMAP_D5_D9.md` 的 D5–D10 路线组织内容。D9 标为进行中，D9-02/03 标为后续阶段，D10 标为规划中。Agent 桌面画面是依据当前类型化工具、权限、确认和审计契约制作的演示 UI，不代表 D9 或 Desktop 可视化验收已经完成。示例日历、任务和预览数据仅用于表达交互。

Temvio 是项目当前选定的工作品牌，本片是本地评审稿。仓库重命名和对外发布仍须遵循 `docs/TEMVIO_REBRAND_PLAN.md` 的命名核查门槛；本片不代表商标或市场可用性已获确认。

音轨从用户提供的 `D:\codex\TermLens\artifacts\termpop-promo-v4-60s-16x9.mp4` 直接复用，包含该参考片混合在内的完整音轨（AAC 48 kHz 立体声）。这是为匹配参考片的本地评审稿复用；公开或商业发布的音乐权利未确认，发布前须完成授权核查。

## 验证

- `validate_video.ps1`：60.0 秒、1920 × 1080、30 FPS、1800 帧、H.264 High、yuv420p、AAC 48 kHz 立体声、faststart 均通过；最终成片视频流保留原编码，音轨来自参考片。
- 最后一帧可解码，没有持续 0.4 秒以上的黑帧空档。
- 17 个最终 MP4 检查帧与批准静帧 SSIM 记录在 `artifacts/validation.json`。
