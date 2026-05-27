# 手势操作功能设计文档

## 概述

为 PyOnPhone Android App 添加三种触摸手势操作：双指缩放字体、可拖拽分割线、滑动手势（撤销/重做/切换文件）。

## 实现方案

采用混合方案（方案 C）：
- **可拖拽分割线**：Android Java 层实现（原生 View 拖拽）
- **双指缩放**：JavaScript 层实现（直接调 Monaco/xterm API）
- **滑动手势**：JavaScript 层实现（直接调 Monaco 编辑器 API）

## 功能 1：双指缩放字体

**实现位置**：editor.html、terminal.html 的 JavaScript 层

**工作原理**：
- 监听 `touchstart`、`touchmove`、`touchend` 事件
- 检测两根手指触摸时，计算初始距离和当前距离的比值
- 缩放比例变化超过阈值时，调整 Monaco 的 `editor.updateOptions({ fontSize })` 或 xterm 的 `term.options.fontSize`
- 字体大小范围：10px ~ 32px
- 单指触摸时不做任何处理，避免干扰正常滚动和编辑

**与现有功能的关系**：
- Monaco 的 `mouseWheelZoom: true` 是桌面 Ctrl+滚轮缩放，与触摸缩放不冲突
- xterm.js 无内置缩放，通过 `term.options.fontSize` 直接设置
- 缩放后自动调用 `fitAddon.fit()` 重新适配终端容器大小

**视觉反馈**：
- 缩放时在屏幕中央显示临时的字体大小提示（如 "16px"），500ms 后消失

## 功能 2：滑动手势操作

**实现位置**：editor.html 的 JavaScript 层（终端不做滑动手势）

**手势映射**：

| 手势 | 条件 | 动作 |
|------|------|------|
| 快速左滑 | 水平距离 > 50px，速度 > 300px/s，垂直偏移 < 水平的 1/2 | 撤销 (Undo) |
| 快速右滑 | 同上 | 重做 (Redo) |
| 快速上滑 | 垂直距离 > 80px，速度 > 300px/s，水平偏移 < 垂直的 1/2 | 切换到上一个文件 |
| 快速下滑 | 同上 | 切换到下一个文件 |

**区分滑动 vs 滚动**：
- Monaco 编辑器单指拖动 = 滚动代码
- 只有**快速、方向明确**的滑动才触发操作，慢速或斜向的触摸交给 Monaco 处理滚动
- 实现：`touchstart` 记录起点和时间，`touchend` 计算距离、方向、速度，满足条件才触发

**文件切换**：
- 通过 JS bridge 调用 Android 端的文件切换逻辑
- Android 端暴露 `window.Android.switchToNextFile()` 和 `window.Android.switchToPrevFile()`
- EditorFragment 实现这两个方法，遍历文件树找到当前文件的上/下一个

**视觉反馈**：
- 撤销/重做时在编辑器顶部显示短暂 Toast 提示（"已撤销" / "已重做"）
- 文件切换时显示文件名提示

## 功能 3：可拖拽分割线

**实现位置**：fragment_editor.xml 布局 + EditorFragment.java

**当前状态**：编辑器和终端之间是一个静态的 2dp View，上下各占 50% 权重。

**改造方案**：
- 静态 divider 替换为可触摸的自定义 View（高度 24dp 方便手指操作）
- EditorFragment 中为 divider 设置 `OnTouchListener`
- 拖动时动态调整 editorWebView 和 terminalWebView 的 `LayoutParams.weight`
- 权重范围限制：编辑器最少占 20%，终端最少占 15%

**视觉设计**：
- 分割线中间放水平拖拽指示条（3 个小圆点或短横线）
- 拖拽时分割线变色（主题强调色），松手后恢复默认颜色

**与布局切换按钮的关系**：
- 布局切换按钮（全屏编辑器 / 全屏终端 / 分屏）保留
- 切换到分屏模式时，恢复用户上次拖拽的分割比例
- 切换到全屏模式时，记住分割比例但不显示分割线

## 涉及文件

| 文件 | 改动 |
|------|------|
| `android/app/src/main/assets/editor.html` | 添加双指缩放、滑动手势的 JS 代码 |
| `android/app/src/main/assets/terminal.html` | 添加双指缩放的 JS 代码 |
| `android/app/src/main/java/com/pyonphone/app/EditorFragment.java` | 分割线拖拽逻辑、文件切换 bridge 方法 |
| `android/app/src/main/res/layout/fragment_editor.xml` | 分割线 View 改造 |

## 边界情况

- **缩放与滚动冲突**：两根手指时 Monaco 不会处理为滚动，无冲突
- **滑动与滚动冲突**：通过速度和方向阈值区分，慢速拖动仍交给 Monaco
- **分割线拖动与 WebView 触摸**：分割线是独立 View，不经过 WebView，无冲突
- **文件切换边界**：第一个文件上滑 / 最后一个文件下滑时，不循环，操作无效
- **文件切换顺序**：按文件树 API 返回的顺序（目录在前、文件在后，各自按字母排序）
