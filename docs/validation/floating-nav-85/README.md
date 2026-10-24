# 85 / 0.17.1-beta · 恢复手机悬浮底栏

用户要求“底部图标还是弄成以前那种悬浮式”。仅恢复手机底部导航的悬浮圆角材质，保留 84 的暖色、四个入口、图标／文字和真实业务；宽度 ≥600dp 的现有侧栏继续使用。没有修改相机协议、照片处理或保存逻辑。

最终包：[`huevia-0.17.1-beta-debug.apk`](../../../dist/huevia-0.17.1-beta-debug.apk)，3,532,397 字节，SHA-256 `f9e193d0fdae1603e57ed933b1656e316216effbce930d8f24f9cbf2bb8dce48`。包名及签名配置保持原有值。**本轮电脑未识别到用户手机，85 尚未覆盖安装到实体手机。** 84 的实机记录仅属于 84。

手机悬浮栏最大宽 360dp、两侧至少 24dp、底部安全区内留 14dp；基础高 76dp，随字号增长。复用 GlassBar 的条件式采样／不透明回退，阴影改用现有暖灰。同步调整预设和相册的底部留位与加号；侧栏模式保留原有横屏空间。

## 最终确认

复用既有原生 `local-album` 回归，未改历史测试源码；[run-matrix.ps1](run-matrix.ps1) 记录调用方式，[matrix.json](matrix.json) 记录最终包与 191 项测试输入在执行前后稳定。该稳定记录不是可复现构建证明。

| 模式 | 结果 | 重复断言执行 |
| --- | --- | ---: |
| 手机 | PASS | 364 |
| 实际字号 1.3 | PASS | 364 |
| 横屏 | PASS | 365 |
| 深色 | PASS | 364 |
| 平板 | PASS | 364 |

共 1,821 次重复断言执行，不是独立用例数。105 张原生状态截图及每模式 report/restoration 在 [orange-ui-85/local-album](../orange-ui-85/local-album/)，全部来自上述最终 SHA。预设、同步、相册和设置的导航继续可达；编辑器保持隐藏根导航。

补充 [440×380dp 紧凑窗口](compact-narrow.png) 检查记录在 [compact-narrow.json](compact-narrow.json)：四个导航目标为 82×63dp，最小高于 48dp；加号与任何导航目标无交叠。该补充仅验证导航／加号几何，不声称该极小窗口通过完整图库套件。临时尺寸、密度、字号和旋转均已恢复。

独立初轮 [FINISH-REVIEW](FINISH-REVIEW.md) 的 M1 横屏授权按钮裁切和 M2 紧凑窄窗加号覆盖底栏已一批修正。[FIX-VERDICT](FIX-VERDICT.md) 将两项关闭，结论为本次悬浮导航变更 PASS / SHIP，限上述修正及直接回归。初轮候选 `0a38…` 的失败矩阵和图片保留在 [first-candidate-0a38](first-candidate-0a38/)，不作为最终通过证据。

没有新增产品栅格资源，没有修改 Web 管理后台或详细原型。当前设计规则已同步到 [DESIGN.md](../../../DESIGN.md) 及其 sidecar。原生截图来自任务专用只读模拟器，不认证实体手机、真实相机、实时网络、FPS、显示标定或 TalkBack。本轮没有上传／上架版本。模拟器关闭记录见 [emulator-cleanup.json](emulator-cleanup.json)。
