# 导出 PDF 中文字体

`GlucoPilotSans-Regular.ttf` 来源于 [Noto Sans SC](https://github.com/notofonts/noto-cjk/tree/main/Sans/Variable/TTF/Subset)，按 SIL Open Font License 1.1 使用，许可证随附 `OFL.txt`。

上游文件：`Sans/Variable/TTF/Subset/NotoSansSC-VF.ttf`。使用 fontTools 将 wght 固定为400，保留基本拉丁、中文、标点、全角字符与常用符号，移除可变字体轴，名称改为 GlucoPilot Sans。生成文件作为应用资源随包部署，运行时不依赖 Windows 字体或在线下载；PDF只嵌入本次使用到的字形。

无法显示的特殊字符以Unicode代码表示，避免用户输入的表情使整个导出失败；不改动原数据库内容。
