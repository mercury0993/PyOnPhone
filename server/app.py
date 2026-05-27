import os
import shutil
import sys
import uuid
import subprocess
import threading
from pathlib import Path

from flask import Flask, request, jsonify
from flask_socketio import SocketIO, emit

app = Flask(__name__)
socketio = SocketIO(app, cors_allowed_origins="*", async_mode="threading")

PROJECTS_DIR = Path(__file__).parent / "projects"
PROJECTS_DIR.mkdir(exist_ok=True)

# Track running processes per (project_id, sid)
_running_procs = {}


def venv_python(project_id):
    """Return the Python executable path inside the project's virtualenv."""
    return project_path(project_id) / ".venv" / ("Scripts/python.exe" if os.name == "nt" else "bin/python")


def ensure_venv(project_id):
    """Create virtualenv for a project if it doesn't exist."""
    venv_dir = project_path(project_id) / ".venv"
    if not venv_dir.exists():
        subprocess.run(
            [sys.executable, "-m", "venv", str(venv_dir)],
            check=True, capture_output=True,
        )


def project_path(project_id):
    return PROJECTS_DIR / project_id


def relative_to_project(project_id, rel_path):
    """Resolve a relative path within a project, preventing directory traversal."""
    base = project_path(project_id).resolve()
    target = (base / rel_path).resolve()
    if not str(target).startswith(str(base)):
        return None
    return target


@app.after_request
def add_cors_headers(response):
    response.headers["Access-Control-Allow-Origin"] = "*"
    response.headers["Access-Control-Allow-Headers"] = "Content-Type"
    response.headers["Access-Control-Allow-Methods"] = "GET, POST, PUT, DELETE, OPTIONS"
    return response


@app.get("/")
def index():
    return jsonify({"name": "PyOnPhone Server", "version": "1.0", "status": "running"})


# ── Project management ──────────────────────────────────────────────

@app.get("/projects")
def list_projects():
    projects = []
    for d in PROJECTS_DIR.iterdir():
        if d.is_dir() and (d / ".git").exists():
            projects.append({
                "id": d.name,
                "last_modified": d.stat().st_mtime,
            })
    projects.sort(key=lambda p: p["last_modified"], reverse=True)
    return jsonify(projects)


@app.post("/projects")
def create_project():
    data = request.get_json(silent=True) or {}
    project_id = data.get("name") or uuid.uuid4().hex[:8]
    pdir = project_path(project_id)

    if pdir.exists():
        return jsonify({"error": "项目已存在"}), 409

    pdir.mkdir(parents=True)
    subprocess.run(["git", "init"], cwd=str(pdir), check=True, capture_output=True)

    # default main.py
    (pdir / "main.py").write_text('print("Hello from PyOnPhone!")\n', encoding="utf-8")
    (pdir / "requirements.txt").write_text("", encoding="utf-8")
    (pdir / ".gitignore").write_text(".venv/\n__pycache__/\n", encoding="utf-8")

    subprocess.run(["git", "add", "."], cwd=str(pdir), check=True, capture_output=True)
    subprocess.run(
        ["git", "commit", "-m", "init: create project"],
        cwd=str(pdir), check=True, capture_output=True,
    )

    ensure_venv(project_id)

    return jsonify({"id": project_id}), 201


@app.delete("/projects/<project_id>")
def delete_project(project_id):
    pdir = project_path(project_id)
    if not pdir.exists():
        return jsonify({"error": "项目不存在"}), 404
    shutil.rmtree(str(pdir))
    return "", 204


# ── File management ─────────────────────────────────────────────────

@app.get("/projects/<project_id>/tree")
def file_tree(project_id):
    pdir = project_path(project_id)
    if not pdir.exists():
        return jsonify({"error": "项目不存在"}), 404

    def build_tree(directory):
        entries = []
        for item in sorted(directory.iterdir()):
            if item.name == ".git":
                continue
            node = {"name": item.name, "path": str(item.relative_to(pdir)).replace("\\", "/")}
            if item.is_dir():
                node["type"] = "directory"
                node["children"] = build_tree(item)
            else:
                node["type"] = "file"
                node["size"] = item.stat().st_size
            entries.append(node)
        return entries

    return jsonify({"tree": build_tree(pdir)})


@app.get("/projects/<project_id>/files")
def read_file(project_id):
    rel_path = request.args.get("path", "")
    target = relative_to_project(project_id, rel_path)
    if target is None or not target.is_file():
        return jsonify({"error": "文件不存在"}), 404

    if target.stat().st_size > 1_000_000:
        return jsonify({"error": "文件过大（>1MB）"}), 413

    return jsonify({"content": target.read_text(encoding="utf-8")})


@app.put("/projects/<project_id>/files")
def save_file(project_id):
    data = request.get_json(silent=True) or {}
    rel_path = data.get("path", "")
    content = data.get("content", "")

    target = relative_to_project(project_id, rel_path)
    if target is None:
        return jsonify({"error": "非法路径"}), 400

    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")

    # auto-commit
    auto = data.get("auto_commit", True)
    if auto:
        pdir = project_path(project_id)
        subprocess.run(["git", "add", rel_path], cwd=str(pdir), capture_output=True)
        subprocess.run(
            ["git", "commit", "-m", f"auto: update {rel_path}"],
            cwd=str(pdir), capture_output=True,
        )

    return jsonify({"ok": True})


@app.post("/projects/<project_id>/files/mkdir")
def mkdir(project_id):
    data = request.get_json(silent=True) or {}
    rel_path = data.get("path", "")

    target = relative_to_project(project_id, rel_path)
    if target is None:
        return jsonify({"error": "非法路径"}), 400

    target.mkdir(parents=True, exist_ok=True)
    return jsonify({"ok": True})


@app.delete("/projects/<project_id>/files")
def delete_file(project_id):
    data = request.get_json(silent=True) or {}
    rel_path = data.get("path", "")

    target = relative_to_project(project_id, rel_path)
    if target is None or not target.exists():
        return jsonify({"error": "文件不存在"}), 404

    if target.is_dir():
        shutil.rmtree(str(target))
    else:
        target.unlink()

    return "", 204


# ── Code execution ──────────────────────────────────────────────────

@app.post("/projects/<project_id>/run")
def run_script(project_id):
    data = request.get_json(silent=True) or {}
    rel_path = data.get("path", "main.py")
    target = relative_to_project(project_id, rel_path)

    if target is None or not target.is_file():
        return jsonify({"error": "文件不存在"}), 404

    pdir = project_path(project_id)
    ensure_venv(project_id)
    python = str(venv_python(project_id))

    proc = subprocess.Popen(
        [python, "-u", str(target)],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        stdin=subprocess.PIPE,
        cwd=str(pdir),
    )

    return jsonify({"pid": proc.pid})


@app.post("/projects/<project_id>/pip")
def pip_install(project_id):
    data = request.get_json(silent=True) or {}
    packages = data.get("packages", [])
    if not packages:
        return jsonify({"error": "未指定包名"}), 400

    ensure_venv(project_id)
    python = str(venv_python(project_id))

    result = subprocess.run(
        [python, "-m", "pip", "install"] + packages,
        capture_output=True, text=True, timeout=120,
    )

    return jsonify({
        "success": result.returncode == 0,
        "stdout": result.stdout,
        "stderr": result.stderr,
    })


# ── WebSocket: real-time execution ──────────────────────────────────

@socketio.on("run")
def handle_run(data):
    """Start a script, stream stdout/stderr back via WebSocket."""
    from flask_socketio import request as sio_request
    sid = sio_request.sid

    project_id = data.get("project_id", "")
    rel_path = data.get("path", "main.py")
    target = relative_to_project(project_id, rel_path)

    if target is None or not target.is_file():
        emit("stderr", {"data": f"Error: file not found: {rel_path}\n"})
        emit("exit", {"code": 1})
        return

    pdir = project_path(project_id)
    ensure_venv(project_id)
    python = str(venv_python(project_id))

    proc = subprocess.Popen(
        [python, "-u", str(target)],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        stdin=subprocess.PIPE,
        cwd=str(pdir),
    )

    _running_procs[(project_id, sid)] = proc

    def stream_output(pipe, event_name):
        try:
            for line in iter(pipe.readline, b""):
                socketio.emit(event_name, {"data": line.decode("utf-8", errors="replace")}, to=sid)
        finally:
            pipe.close()

    t_out = threading.Thread(target=stream_output, args=(proc.stdout, "stdout"), daemon=True)
    t_err = threading.Thread(target=stream_output, args=(proc.stderr, "stderr"), daemon=True)
    t_out.start()
    t_err.start()

    def wait_and_report():
        t_out.join()
        t_err.join()
        proc.wait()
        _running_procs.pop((project_id, sid), None)
        socketio.emit("exit", {"code": proc.returncode}, to=sid)

    threading.Thread(target=wait_and_report, daemon=True).start()


@socketio.on("stdin")
def handle_stdin(data):
    """Forward user input to the running process."""
    from flask_socketio import request as sio_request
    sid = sio_request.sid

    project_id = data.get("project_id", "")
    proc = _running_procs.get((project_id, sid))
    if proc and proc.stdin and proc.poll() is None:
        try:
            proc.stdin.write(data.get("data", "").encode("utf-8"))
            proc.stdin.flush()
        except (BrokenPipeError, OSError):
            pass


@socketio.on("stop")
def handle_stop(data):
    """Kill a running process."""
    from flask_socketio import request as sio_request
    sid = sio_request.sid

    project_id = data.get("project_id", "")
    proc = _running_procs.pop((project_id, sid), None)
    if proc and proc.poll() is None:
        proc.kill()


# ── Git operations ──────────────────────────────────────────────────

def _git(project_id, *args):
    """Run a git command in the project directory."""
    pdir = project_path(project_id)
    result = subprocess.run(
        ["git"] + list(args),
        cwd=str(pdir), capture_output=True, text=True,
    )
    return result


@app.post("/projects/<project_id>/git/commit")
def git_commit(project_id):
    data = request.get_json(silent=True) or {}
    message = data.get("message", "manual commit")
    add_all = data.get("add_all", True)

    pdir = project_path(project_id)
    if not pdir.exists():
        return jsonify({"error": "项目不存在"}), 404

    if add_all:
        _git(project_id, "add", ".")

    result = _git(project_id, "commit", "-m", message)
    if result.returncode != 0 and "nothing to commit" in result.stdout + result.stderr:
        return jsonify({"ok": True, "message": "没有变更需要提交"})

    return jsonify({
        "ok": result.returncode == 0,
        "stdout": result.stdout,
        "stderr": result.stderr,
    })


@app.post("/projects/<project_id>/git/push")
def git_push(project_id):
    pdir = project_path(project_id)
    if not pdir.exists():
        return jsonify({"error": "项目不存在"}), 404

    # first push: set upstream
    result = _git(project_id, "push", "-u", "origin", "main")
    if result.returncode != 0:
        # try without -u in case upstream is already set
        result = _git(project_id, "push")

    return jsonify({
        "ok": result.returncode == 0,
        "stdout": result.stdout,
        "stderr": result.stderr,
    })


@app.post("/projects/<project_id>/git/pull")
def git_pull(project_id):
    pdir = project_path(project_id)
    if not pdir.exists():
        return jsonify({"error": "项目不存在"}), 404

    result = _git(project_id, "pull")
    return jsonify({
        "ok": result.returncode == 0,
        "stdout": result.stdout,
        "stderr": result.stderr,
    })


@app.get("/projects/<project_id>/git/log")
def git_log(project_id):
    pdir = project_path(project_id)
    if not pdir.exists():
        return jsonify({"error": "项目不存在"}), 404

    result = _git(project_id, "log", "--format=%H|%s|%ai", "--no-decorate", "-20")
    if result.returncode != 0:
        return jsonify({"log": []})

    entries = []
    for line in result.stdout.strip().splitlines():
        parts = line.split("|", 2)
        if len(parts) == 3:
            entries.append({
                "hash": parts[0],
                "message": parts[1],
                "date": parts[2],
            })

    return jsonify({"log": entries})


@app.get("/projects/<project_id>/git/status")
def git_status(project_id):
    pdir = project_path(project_id)
    if not pdir.exists():
        return jsonify({"error": "项目不存在"}), 404

    result = _git(project_id, "status", "--porcelain")
    files = []
    for line in result.stdout.strip().splitlines():
        if line:
            files.append({"status": line[:2].strip(), "path": line[3:]})

    # current branch
    branch_result = _git(project_id, "branch", "--show-current")
    branch = branch_result.stdout.strip() or "main"

    return jsonify({"branch": branch, "files": files})


# ── Entry point ─────────────────────────────────────────────────────

if __name__ == "__main__":
    socketio.run(app, host="0.0.0.0", port=5000, debug=True, use_reloader=False)
