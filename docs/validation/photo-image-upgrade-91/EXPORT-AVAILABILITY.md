# 导出设置的成品与手机相册回执

本页记录独立审查第 4 条的修复组件，不声明已经完成设备验证。

新增 `PhotoExportAvailability` 负责只读状态映射；`PhotoExportDialog` 负责异步查询代次及默认操作。调用方仍持有冻结编辑、后台执行器与照片查看路由。

## 接入

在 `PhotoExportDialog.show(...)` 返回后设置 provider；不要在主线程运行 `check(...)`：

```java
dialog.setAvailabilityProvider((spec, target, done) -> io.execute(() -> {
    List<PhotoPresetBatch.Edit> requested =
            PhotoExportAvailability.withSpec(frozenEdits, spec);
    PhotoExportAvailability.Result state = PhotoExportAvailability.check(
            batch, new PhotoLiveGallery(applicationContext), requested, target);
    done.complete(state); // 可从 worker 返回，Dialog 自己提交到主线程。
}), state -> {
    // PHONE_SAVED 的 entry.uri 才是本次核对过的手机相册 URI。
    // entry.photo 是校验过的 App 成品。沿用照片查看/结果选择路由。
});
```

`withSpec` 保留源对象、草稿及其 owner、保存版本的预期源 SHA，且保留已经绑定的执行账号，包括以空串表示的访客账号。未绑定的旧调用方不会在查询时偷偷绑定当前账号。查询对象不携带保存位置、额外副本或执行完成标志；保存位置和新副本仍是点击主操作后单独冻结的发布选择。

调用方还应沿用它自己的页面、源身份和账号有效性检查，不能把已经失效的页面重新绑定到当前照片后继续接受旧设置。此组件只保护它本身的异步结果代次。

## 状态与操作

| 查找/回执结果 | 显示语义 | 默认主操作 |
| --- | --- | --- |
| 没有规范成品 | 尚未生成此规格的成品 | 导出照片 |
| App 成品有效，位置为 App 内 | 此规格的成品已保存在 App 内 | 查看已保存照片 |
| 手机回执经字节与归属核对仍有效 | 已核对此规格的照片仍在手机相册 | 查看已保存照片 |
| App 成品有效，手机核对明确缺失 | 已生成，可直接保存；手机相册暂无此照片 | 重新保存到手机相册 |
| 既有请求未完成 | 上次保存尚未完成 | 继续保存到手机相册 |
| 查询异常、不可核对或缺少可信 URI | 暂无法核对已保存照片 | 核对并继续保存 |
| 明确勾选另存新副本 | 新副本使用本次时间 | 另存新副本 |

批量显示各类张数，只有所有项目都在所选位置有效存在时，主操作才成为“查看已保存照片”。部分成功、未知或缺失仍进入原发布器逐项去重。未知不转成缺失，不隐式变成新副本。

查询 key 使用每张实际最终宽高、质量、色彩/编码/元数据身份和保存位置，未使用档位名称；分享与同样实际尺寸、质量的自定义设置复用同一状态。规格或保存位置变化、非法输入、关闭面板会使旧回调失效。查询中的主操作暂不可用，12 秒未返回时降为“未知”，保留原回执去重；超时回调不能覆盖后来完成的核对。关闭时移除定时回调。

## 已执行的组件验证

- `tests/export-availability-java/run.ps1`：71 项检查通过，覆盖状态矩阵、未知/缺失区分、无可信 URI、读取异常、App 位置不访问手机回执、只读结果不可变、规格实际身份一致以及冻结账号/输入的保留。
- 使用 Android 35 `android.jar`、Java 17，独立编译本组件、Dialog 与本轮 `PhotoPresetBatch`：通过。只有既有 Android 弃用 API 提示。

这些 host 检查使用边界替身，不证明真实 MediaStore 权限、删除或发布行为已运行。设备端仍需分别确认：有效已保存后重开、删除手机副本后重开、回执不可用、规格/位置快速切换时旧请求迟到、已保存默认查看和明确新副本，以及批量混合状态。
