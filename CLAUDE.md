# PyOnPhone

手机上编写和运行 Python 代码的 Android App。支持本地执行（Chaquopy）和远程执行（自有服务器），通过 Git 同步。

## 设计文档

完整设计 spec 在 `docs/superpowers/specs/2026-05-25-pyonphone-design.md`

## 当前进度

### 阶段一：Flask 服务器端

- [x] 搭建服务器骨架 + 文件管理 API（projects CRUD、文件读写、文件树）
- [x] 代码执行 API + WebSocket（run、pip、实时 stdout/stderr/stdin、virtualenv 隔离）
- [x] Git 操作 API（commit、push、pull、log、status、自动 commit）
- [x] 创建项目时自动建 virtualenv（目前只在 run/pip 时才创建）
- [x] REST API 加 CORS 头（Android WebView 跨域需要）

### 阶段二：Android 客户端

- [x] 项目结构搭建 + 主界面框架（底部 Tab 导航）
- [x] 项目列表页（CRUD + 与服务器通信）
- [x] 编辑页（Monaco Editor WebView + 终端 WebView + 文件树）
- [x] Git 页面 + 设置页
- [x] Chaquopy 本地执行集成
- [x] JGit 集成（本地 Git 操作）

## 项目结构

```
PyOnPhone/
  docs/
    superpowers/
      specs/2026-05-25-pyonphone-design.md
  server/                    # Flask 服务器（阶段一完成）
    app.py                   # 主入口（含全部 API + WebSocket）
    requirements.txt
    projects/                # 项目数据目录（运行时自动创建）
  android/                   # Android 客户端
    app/src/main/
      java/com/pyonphone/app/
        MainActivity.java    # 底部 Tab 导航
        ApiClient.java       # 服务器 API 客户端
        Project.java         # 项目数据模型
        ProjectsFragment.java    # 项目列表页
        ProjectsAdapter.java     # 列表适配器
        EditorFragment.java      # 编辑页（Monaco + 终端 + 文件树 + 本地/远程切换）
        FileTreeAdapter.java     # 文件树适配器
        GitFragment.java         # Git 管理页（本地/远程切换）
        CommitAdapter.java       # 提交历史适配器
        SettingsFragment.java    # 设置页
        PythonExecutor.java      # Chaquopy 本地 Python 执行器
        GitExecutor.java         # JGit 本地 Git 操作执行器
      res/
        layout/              # 布局文件
        menu/                # 底部导航菜单
        navigation/          # 导航图
        values/              # 主题、字符串
        values-night/        # 暗色主题
      assets/
        editor.html          # Monaco Editor WebView
        terminal.html        # xterm.js 终端 WebView
      AndroidManifest.xml
    build.gradle             # 模块级
  build.gradle               # 项目级
  settings.gradle
```

## 开发约定

- 服务器端：Python 3.9+，Flask + Flask-SocketIO
- Android 端：Java 11，Android SDK，OkHttp（WebSocket），Monaco Editor + xterm.js（CDN 加载）
- 每个项目 = 一个 Git 仓库 + 一个独立 virtualenv
- API 无认证，仅限内网个人使用
- 端口默认 5000
- 启动服务器：`cd server && python app.py`

## 已实现的 API

| 方法 | 路径 | 功能 |
|------|------|------|
| GET | /projects | 列出所有项目 |
| POST | /projects | 创建项目 |
| DELETE | /projects/{id} | 删除项目 |
| GET | /projects/{id}/tree | 文件树 |
| GET | /projects/{id}/files?path= | 读取文件 |
| PUT | /projects/{id}/files | 保存文件 |
| POST | /projects/{id}/files/mkdir | 创建目录 |
| DELETE | /projects/{id}/files | 删除文件 |
| POST | /projects/{id}/run | 执行脚本 |
| POST | /projects/{id}/pip | 安装包 |
| POST | /projects/{id}/git/commit | Git 提交 |
| POST | /projects/{id}/git/push | Git 推送 |
| POST | /projects/{id}/git/pull | Git 拉取 |
| GET | /projects/{id}/git/log | 提交历史 |
| GET | /projects/{id}/git/status | 文件变更 |

WebSocket 事件：`run`（执行）、`stdout`/`stderr`（输出）、`stdin`（输入）、`stop`（终止）、`exit`（退出码）

## 下一步

可选方向：
1. **真机测试**：在真实 Android 设备上测试完整功能
2. **Git 认证优化**：支持 HTTPS Token 认证、SSH 密钥自动加载
3. **更多手势操作**：双指缩放、滑动选择等

服务器启动命令：`cd server && python app.py`（端口 5000）

已知遗留：
- debug 模式下 use_reloader=False
- async_mode 用的 threading（gevent 有依赖问题）
