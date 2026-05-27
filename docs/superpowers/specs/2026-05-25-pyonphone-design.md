# PyOnPhone 设计文档

## 概述

一款原生 Android App，让用户在手机上编写和运行 Python 代码。支持本地执行（Chaquopy）和远程执行（自有服务器），通过 Git 同步代码。面向宿舍断电时仍需写代码的场景。

## 技术选型

| 层面 | 技术 |
|------|------|
| Android 端 | Java, Android SDK |
| 代码编辑器 | Monaco Editor (WebView) |
| 终端 | xterm.js (WebView) |
| 本地 Python | Chaquopy |
| Git 客户端 | JGit |
| 服务器端 | Python, Flask, Flask-SocketIO, Gunicorn |
| 通信 | REST API + WebSocket |

## 整体架构

```
┌─────────────────────────┐          ┌─────────────────────────┐
│     Android App         │          │      服务器              │
│                         │          │                         │
│  Monaco Editor (WebView)│──REST───│  File Manager API       │
│  Terminal (WebView)     │──WS────│  Python Executor        │
│  Git Manager (JGit)     │──SSH───│  Git Server (裸仓库)     │
│  Local Python (Chaquopy)│          │                         │
└─────────────────────────┘          └─────────────────────────┘
```

## Android App 设计

### 页面结构

底部 Tab 导航，四个页面：

**1. 项目列表页（首页）**
- 显示服务器上的项目列表（每个 Git 仓库 = 一个项目）
- 支持新建项目、删除项目
- 显示最近编辑时间

**2. 编辑页（核心）**
- 左侧：可收起的文件树（文件夹展开结构）
- 右侧上半：Monaco Editor 代码编辑区
- 右侧下半：终端输出区
- 底部工具栏：运行、Git 同步、切换布局（分屏 / 全屏编辑器 / 全屏终端）

**3. Git 页面**
- 提交历史时间线
- commit、push、pull 操作
- 当前分支、文件变更状态

**4. 设置页**
- 服务器地址配置
- 本地 Python 开关
- 主题切换（亮色/暗色）
- 字体大小调整

### 编辑器功能

Monaco Editor 提供：
- Python 语法高亮
- 自动补全（基于 Python 语言服务）
- 错误提示（红色波浪线）
- 多光标编辑
- 代码折叠
- 搜索替换

### 布局模式

- **分屏模式**（默认）：编辑器占上半，终端占下半，可拖拽分割线调整比例
- **全屏编辑器**：编辑器占满屏幕，隐藏终端
- **全屏终端**：终端占满屏幕，隐藏编辑器

## 服务器端设计

### 技术栈

- Flask + Flask-SocketIO
- Gunicorn WSGI 服务器
- 每个项目独立 virtualenv

### API 设计

**文件管理**
- `GET /projects` — 列出所有项目
- `POST /projects` — 创建项目（初始化 Git 仓库）
- `GET /projects/{id}/files?path=xxx` — 读取文件内容
- `PUT /projects/{id}/files` — 保存文件
- `POST /projects/{id}/files/mkdir` — 创建文件夹
- `DELETE /projects/{id}/files` — 删除文件/文件夹
- `GET /projects/{id}/tree` — 获取文件树结构

**代码执行**
- `POST /projects/{id}/run` — 执行 Python 脚本
- `POST /projects/{id}/pip` — 安装 pip 包
- WebSocket 通道：实时 stdout/stderr 输出 + stdin 输入

**Git 操作**
- `POST /projects/{id}/git/commit` — 提交
- `POST /projects/{id}/git/push` — 推送
- `POST /projects/{id}/git/pull` — 拉取
- `GET /projects/{id}/git/log` — 提交历史
- `GET /projects/{id}/git/status` — 文件变更状态

### 执行环境

- 每个项目有独立的 virtualenv
- 支持 pip 安装第三方包
- 执行超时默认 30 秒
- stdout/stderr 通过 WebSocket 实时流式返回
- 支持 stdin 交互（终端输入）

## 通信与数据流

### 文件同步

1. 打开项目 → `GET /tree` 获取文件树
2. 点击文件 → `GET /files?path=xxx` 获取内容，加载到编辑器
3. 编辑后 → 防抖 1 秒自动保存（`PUT /files`）
4. 同时自动本地 commit（可配置）

### 代码执行

1. 点"运行" → 自动保存当前文件
2. `POST /run` 携带文件路径
3. 服务器在 virtualenv 中执行
4. stdout/stderr 通过 WebSocket 实时流回终端区
5. 终端区支持 stdin 输入

### Git 同步

1. 点"同步" → `POST /git/commit`（自动 add + commit）
2. `POST /git/push` 推送到服务器裸仓库
3. 冲突时返回提示，用户选择处理方式

### 本地执行

1. 点"本地运行" → Chaquopy 在手机上直接执行
2. 输出显示在同一个终端区
3. 仅支持 Python 标准库，不支持 pip

## 错误处理

**网络**
- 服务器不可达 → 提示连接失败，自动切换本地模式
- 请求超时 → 显示重试按钮
- 自动保存失败 → 状态栏"未同步"标记，恢复后自动重试

**执行**
- 代码运行错误 → 终端红色高亮显示 traceback
- 执行超时（30 秒）→ 自动终止，提示用户
- pip 安装失败 → 显示错误日志

**Git**
- Push 冲突 → 提示"服务器有新改动，是否强制推送？"
- 网络断开时 commit → 本地暂存，恢复后自动 push

**编辑器**
- 大文件（>1MB）→ 提示文件过大
- 文件被服务器修改 → 提示是否重新加载

## 安全说明

- 无用户认证，仅限个人使用
- 通信使用 HTTP（内网场景），如需公网访问建议套 HTTPS 反向代理（如 Nginx + Let's Encrypt）
- 服务器 API 建议通过防火墙限制访问 IP
- 执行环境在独立 virtualenv 中，项目间隔离

## 补充说明

### 项目创建流程

`POST /projects` 创建项目时：
1. 在服务器 projects 目录下创建项目文件夹
2. `git init` 初始化仓库
3. 创建默认 `main.py`（Hello World 模板）
4. 创建 `requirements.txt`（空文件）
5. 自动创建 virtualenv

### 自动 commit 范围

- 仅对通过 App 保存的文件自动 commit（即 `PUT /files` 触发）
- commit 消息格式：`auto: update {filename}`
- 用户手动 commit 时可填写自定义消息
- 自动 commit 默认开启，可在设置中关闭

### 服务器部署要求

- Python 3.9+
- Git 已安装
- 开放一个 HTTP 端口（默认 5000）
- 建议用 systemd 管理服务进程
