"""FeiYu Bot plugin SDK for Python (standard library only).

See docs/插件开发.md for the protocol and every method.
"""
import concurrent.futures
import inspect
import itertools
import json
import os
import sys
import threading
import traceback

__all__ = ["Plugin", "Context", "RpcError"]


class RpcError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code
        self.message = message


class Context:
    """A message, command or event the plugin can reply to."""

    def __init__(self, plugin, params):
        self._plugin = plugin
        self.data = params
        self.type = params.get("type", "")
        self.session = params.get("session", "")
        self.group_id = params.get("groupId", "")
        self.user_id = params.get("userId", "")
        self.sender = params.get("sender", "")
        self.owner = params.get("owner", False)
        self.text = params.get("text", "")
        self.message_id = params.get("messageId", "")
        self.mentioned_me = params.get("mentionedMe", False)
        self.backfilled = params.get("backfilled", False)
        self.name = params.get("name", "")
        self.args = params.get("args", "")
        self.argv = params.get("argv", [])
        self.ctx = params.get("ctx")

    def reply(self, text=None, image=None):
        """Reply to the message that triggered this (needs the qq.reply permission)."""
        params = {"ctx": self.ctx}
        if text:
            params["text"] = text
        if image:
            params["image"] = image
        return self._plugin.call("qq/reply", params)


class Plugin:
    def __init__(self, workers=16):
        self._out = sys.stdout.buffer
        sys.stdout = sys.stderr
        self._lock = threading.Lock()
        self._ids = itertools.count(1)
        self._pending = {}
        self._pool = concurrent.futures.ThreadPoolExecutor(max_workers=workers)
        self._commands = {}
        self._tools = {}
        self._events = {}
        self._intercept = None
        self._schedules = {}
        self._prompt = None
        self._filter = None
        self._web = None
        self._on_init = []
        self._on_settings = []
        self._on_shutdown = []
        self.settings = {}
        self.permissions = []
        self.plugin_id = os.environ.get("FEIYU_PLUGIN_ID", "")
        self.data_dir = os.environ.get("FEIYU_DATA_DIR", "")
        self.host_version = ""
        self.prefix = "#"

    def command(self, name):
        def wrap(fn):
            self._commands[name] = fn
            return fn
        return wrap

    def tool(self, name, description, properties=None, required=None, mind=False, timeout=None):
        def wrap(fn):
            props = properties or {}
            schema = {"type": "object", "properties": props,
                      "required": list(props.keys()) if required is None else list(required)}
            spec = {"name": name, "description": description, "parameters": schema, "mind": mind}
            if timeout:
                spec["timeout"] = timeout
            self._tools[name] = (fn, spec)
            return fn
        return wrap

    def on(self, event):
        def wrap(fn):
            self._events.setdefault(event, []).append(fn)
            return fn
        return wrap

    def intercept(self, fn):
        self._intercept = fn
        return fn

    def schedule(self, schedule_id):
        def wrap(fn):
            self._schedules[schedule_id] = fn
            return fn
        return wrap

    def prompt(self, fn):
        self._prompt = fn
        return fn

    def filter(self, fn):
        self._filter = fn
        return fn

    def web(self, fn):
        self._web = fn
        return fn

    def on_init(self, fn):
        self._on_init.append(fn)
        return fn

    def on_settings(self, fn):
        self._on_settings.append(fn)
        return fn

    def on_shutdown(self, fn):
        self._on_shutdown.append(fn)
        return fn

    def call(self, method, params=None, timeout=60):
        request_id = next(self._ids)
        future = concurrent.futures.Future()
        self._pending[request_id] = future
        self._write({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params or {}})
        try:
            return future.result(timeout=timeout)
        finally:
            self._pending.pop(request_id, None)

    def notify(self, method, params=None):
        self._write({"jsonrpc": "2.0", "method": method, "params": params or {}})

    def send(self, group_id=None, user_id=None, text=None, image=None):
        params = {}
        if group_id:
            params["groupId"] = str(group_id)
        if user_id:
            params["userId"] = str(user_id)
        if text:
            params["text"] = text
        if image:
            params["image"] = image
        return self.call("qq/send", params)

    def history(self, group_id=None, user_id=None, count=20):
        params = {"count": count}
        if group_id:
            params["groupId"] = str(group_id)
        if user_id:
            params["userId"] = str(user_id)
        return self.call("qq/history", params)

    def groups(self):
        return self.call("qq/groups")

    def members(self, group_id):
        return self.call("qq/members", {"groupId": str(group_id)})

    def recall(self, about):
        return self.call("memory/recall", {"about": about})

    def remember(self, about, text):
        return self.call("memory/add", {"about": about, "text": text})

    def llm(self, prompt=None, messages=None, system=None, max_tokens=None, timeout=180):
        params = {}
        if prompt is not None:
            params["prompt"] = prompt
        if messages is not None:
            params["messages"] = messages
        if system:
            params["system"] = system
        if max_tokens:
            params["maxTokens"] = max_tokens
        return self.call("llm/chat", params, timeout=timeout)

    def kv_get(self, key, default=None):
        value = self.call("kv/get", {"key": key}).get("value")
        return default if value is None else value

    def kv_set(self, key, value):
        return self.call("kv/set", {"key": key, "value": value})

    def kv_delete(self, key):
        return self.call("kv/delete", {"key": key})

    def kv_list(self, prefix=""):
        return self.call("kv/list", {"prefix": prefix})

    def log(self, text, level="info"):
        self.notify("log", {"level": level, "text": str(text)})

    def notify_web(self, text):
        return self.call("notify", {"text": text})

    def tools_changed(self):
        self.notify("tools/changed")

    def remove_tool(self, name):
        self._tools.pop(name, None)

    def run(self):
        stdin = sys.stdin.buffer
        for raw in stdin:
            line = raw.decode("utf-8", errors="replace").strip()
            if not line:
                continue
            try:
                message = json.loads(line)
            except ValueError:
                continue
            if not isinstance(message, dict):
                continue
            if "method" in message:
                if message["method"] == "shutdown":
                    self._shutdown()
                self._pool.submit(self._serve, message)
            elif "id" in message:
                future = self._pending.get(message["id"])
                if future is None:
                    continue
                if "error" in message:
                    error = message["error"] or {}
                    future.set_exception(RpcError(error.get("code", -32000), error.get("message", "host error")))
                else:
                    future.set_result(message.get("result"))
        self._shutdown()

    def _shutdown(self):
        for fn in self._on_shutdown:
            try:
                fn()
            except Exception:
                traceback.print_exc()
        sys.stderr.flush()
        os._exit(0)

    def _write(self, message):
        data = (json.dumps(message, ensure_ascii=False) + "\n").encode("utf-8")
        with self._lock:
            self._out.write(data)
            self._out.flush()

    def _serve(self, message):
        request_id = message.get("id")
        try:
            result = self._handle(message["method"], message.get("params") or {}, request_id is not None)
            if request_id is not None:
                self._write({"jsonrpc": "2.0", "id": request_id, "result": result})
        except RpcError as e:
            if request_id is not None:
                self._write({"jsonrpc": "2.0", "id": request_id, "error": {"code": e.code, "message": e.message}})
        except Exception as e:
            traceback.print_exc()
            if request_id is not None:
                self._write({"jsonrpc": "2.0", "id": request_id, "error": {"code": -32000, "message": f"{type(e).__name__}: {e}"}})

    def _tool_specs(self):
        return [spec for _, spec in self._tools.values()]

    def _handle(self, method, params, is_request):
        if method == "initialize":
            self.settings = params.get("settings") or {}
            self.permissions = params.get("permissions") or []
            self.host_version = params.get("hostVersion", "")
            self.prefix = params.get("prefix", "#")
            self.data_dir = params.get("dataDir", self.data_dir)
            for fn in self._on_init:
                fn()
            return {"tools": self._tool_specs()}
        if method == "tools/list":
            return {"tools": self._tool_specs()}
        if method == "command/run":
            fn = self._commands.get(params.get("name"))
            if fn is None:
                return {"handled": False}
            return {"handled": fn(Context(self, params)) is not False}
        if method == "event":
            ctx = Context(self, params)
            if is_request and self._intercept is not None:
                return _verdict(self._intercept(ctx))
            for fn in self._events.get(ctx.type, []) + self._events.get("*", []):
                fn(ctx)
            return {"action": "pass"}
        if method == "tool/call":
            name = params.get("name")
            if name not in self._tools:
                raise RpcError(-32601, f"unknown tool {name}")
            fn = self._tools[name][0]
            arguments = params.get("arguments") or {}
            try:
                result = fn(**arguments) if _takes_kwargs(fn, arguments) else fn(arguments)
            except Exception as e:
                return {"content": [{"type": "text", "text": f"{type(e).__name__}: {e}"}], "isError": True}
            if isinstance(result, dict) and "content" in result:
                return result
            text = result if isinstance(result, str) else json.dumps(result, ensure_ascii=False)
            return {"content": [{"type": "text", "text": text}]}
        if method == "schedule/fire":
            fn = self._schedules.get(params.get("id"))
            if fn is not None:
                fn()
            return None
        if method == "prompt/contribute":
            if self._prompt is None:
                return {"text": ""}
            return {"text": self._prompt(params.get("session", ""), params.get("people", [])) or ""}
        if method == "filter/outgoing":
            if self._filter is None:
                return {"action": "pass"}
            return _verdict(self._filter(params.get("session", ""), params.get("text", "")))
        if method == "settings/changed":
            self.settings = params.get("settings") or {}
            for fn in self._on_settings:
                fn(self.settings)
            return None
        if method == "web/request":
            if self._web is None:
                return {"status": 404, "body": "this plugin has no web API"}
            result = self._web(params.get("method", "GET"), params.get("path", "/"), params.get("query", ""), params.get("body", ""))
            if isinstance(result, dict) and "status" in result:
                return result
            body = result if isinstance(result, str) else json.dumps(result, ensure_ascii=False)
            return {"status": 200, "body": body,
                    "contentType": "text/plain; charset=utf-8" if isinstance(result, str) else "application/json"}
        raise RpcError(-32601, f"unknown method {method}")


def _verdict(result):
    if result is None or result is True or result == "pass":
        return {"action": "pass"}
    if result is False or result == "drop":
        return {"action": "drop"}
    if result == "hide":
        return {"action": "hide"}
    if isinstance(result, tuple) and len(result) == 2 and result[0] == "rewrite":
        return {"action": "rewrite", "text": result[1]}
    if isinstance(result, str):
        return {"action": "rewrite", "text": result}
    return {"action": "pass"}


def _takes_kwargs(fn, arguments):
    try:
        params = inspect.signature(fn).parameters
    except (TypeError, ValueError):
        return True
    if any(p.kind == inspect.Parameter.VAR_KEYWORD for p in params.values()):
        return True
    return all(key in params for key in arguments) and not (len(params) == 1 and "args" in params)
