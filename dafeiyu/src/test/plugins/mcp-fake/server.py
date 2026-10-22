import json
import os
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PROTOCOL = os.environ.get("FAKE_PROTOCOL", "2025-06-18")
tools = [
    {"name": "echo", "description": "echo back text", "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}},
    {"name": "add", "description": "add two numbers", "inputSchema": {"type": "object", "properties": {"a": {"type": "number"}, "b": {"type": "number"}}, "required": ["a", "b"]}},
    {"name": "slow", "description": "sleep 3 seconds", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "image", "description": "return an image", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "fail", "description": "always fails", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "grow", "description": "add a tool and notify", "inputSchema": {"type": "object", "properties": {}}},
]
notify = None


def handle(message):
    method = message.get("method")
    params = message.get("params") or {}
    if method == "initialize":
        return {"protocolVersion": PROTOCOL, "capabilities": {"tools": {"listChanged": True}}, "serverInfo": {"name": "fake-mcp", "version": "1.0"}}
    if method == "tools/list":
        cursor = int(params.get("cursor") or 0)
        page = tools[cursor:cursor + 3]
        result = {"tools": page}
        if cursor + 3 < len(tools):
            result["nextCursor"] = str(cursor + 3)
        return result
    if method == "tools/call":
        name = params.get("name")
        args = params.get("arguments") or {}
        if name == "echo":
            return {"content": [{"type": "text", "text": args.get("text", "")}]}
        if name == "add":
            return {"content": [{"type": "text", "text": str(args["a"] + args["b"])}]}
        if name == "slow":
            time.sleep(3)
            return {"content": [{"type": "text", "text": "slept"}]}
        if name == "image":
            return {"content": [{"type": "image", "data": "iVBORw0KGgo=", "mimeType": "image/png"}, {"type": "text", "text": "a tiny image"}]}
        if name == "fail":
            return {"content": [{"type": "text", "text": "something went wrong"}], "isError": True}
        if name == "grow":
            tools.append({"name": "extra", "description": "added later", "inputSchema": {"type": "object", "properties": {}}})
            if notify:
                notify({"jsonrpc": "2.0", "method": "notifications/tools/list_changed"})
            return {"content": [{"type": "text", "text": "grew"}]}
        raise KeyError(name)
    if method == "ping":
        return {}
    raise KeyError(method)


def respond(message):
    if "id" not in message:
        return None
    try:
        return {"jsonrpc": "2.0", "id": message["id"], "result": handle(message)}
    except Exception as e:
        return {"jsonrpc": "2.0", "id": message["id"], "error": {"code": -32601, "message": f"unknown: {e}"}}


def stdio():
    global notify
    lock = threading.Lock()

    def write(obj):
        with lock:
            sys.stdout.write(json.dumps(obj) + "\n")
            sys.stdout.flush()

    notify = write
    print("fake mcp stdio server started", file=sys.stderr, flush=True)
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        message = json.loads(line)
        threading.Thread(target=lambda m=message: (r := respond(m)) and write(r), daemon=True).start()


class Handler(BaseHTTPRequestHandler):
    session = str(uuid.uuid4())

    def log_message(self, *args):
        pass

    def do_POST(self):
        if self.headers.get("X-Test-Key") != os.environ.get("FAKE_KEY", "secret-123"):
            self.send_response(401)
            self.end_headers()
            return
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
        reply = respond(body)
        if reply is None:
            self.send_response(202)
            self.end_headers()
            return
        data = json.dumps(reply).encode()
        if "text/event-stream" in self.headers.get("Accept", "") and body.get("method") == "tools/call":
            event = b"data: " + data + b"\n\n"
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Mcp-Session-Id", self.session)
            self.send_header("Content-Length", str(len(event)))
            self.end_headers()
            self.wfile.write(event)
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Mcp-Session-Id", self.session)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self.send_response(405)
        self.end_headers()

    def do_DELETE(self):
        self.send_response(200)
        self.end_headers()


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "http":
        ThreadingHTTPServer(("127.0.0.1", int(sys.argv[2])), Handler).serve_forever()
    else:
        stdio()
