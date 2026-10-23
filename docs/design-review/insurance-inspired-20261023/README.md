# Huevia · 橙光设计提案

按用户指定的 [Ghani Pradita / Paperpillar 参考](https://dribbble.com/shots/26673273-Insurance-Mobile-App-UI-Design)制作的独立交互设计稿。参考原图的灰白底、橙红光感、半透明白色层叠卡片与炭黑胶囊按钮，应用于 Huevia 的预设、同步、相册与相机设置。

启动：`node docs/design-review/insurance-inspired-20261023/serve.mjs`

预览：[http://127.0.0.1:8786/](http://127.0.0.1:8786/)。只监听本机，只提供本目录；也可直接打开 index.html。

默认三屏展示预设库、相册、相机设置，每台手机可独立点击底部导航。顶部可切换单屏体验。预设分类、在线／我的、预设详情、模板更换、下载／导入确认、照片编辑、滤镜强度、曝光／对比度／饱和度、配方命名、相册选择与批量效果、同步与直播、连接管理和设置面板均有交互演示。

所有照片、名称、数量、连接、槽位、状态与色彩效果均为演示。相机、文件与相册不会被修改；本地选择的文件不会上传。没有改动 Android 源码、APK、后端或线上服务。原型状态仅保留在当前页面会话，刷新会重置。

照片复用项目已有 AI 概念摄影 atlas，来源与完整提示词见 assets/provenance.md、assets/photo-atlas.png.json 及 PNG 内嵌元数据。界面由 HTML/CSS/SVG 构建，没有把参考图当作可点击界面。

设计规格见 DESIGN.md，截图见 review/desktop.png、review/mobile.png 和 review/flows.png。检查范围和独立复核记录见 review/VALIDATION.md。
