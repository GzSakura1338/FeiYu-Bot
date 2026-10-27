'use strict';

const readline = require('readline');

class RpcError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

class Context {
  constructor(plugin, params) {
    this._plugin = plugin;
    this.data = params;
    this.type = params.type || '';
    this.session = params.session || '';
    this.groupId = params.groupId || '';
    this.userId = params.userId || '';
    this.sender = params.sender || '';
    this.owner = Boolean(params.owner);
    this.text = params.text || '';
    this.messageId = params.messageId || '';
    this.mentionedMe = Boolean(params.mentionedMe);
    this.backfilled = Boolean(params.backfilled);
    this.name = params.name || '';
    this.args = params.args || '';
    this.argv = params.argv || [];
    this.ctx = params.ctx;
  }

  reply(text, image) {
    const params = { ctx: this.ctx };
    if (text) params.text = text;
    if (image) params.image = image;
    return this._plugin.call('qq/reply', params);
  }
}

class Plugin {
  constructor() {
    this._out = process.stdout;
    console.log = (...args) => process.stderr.write(args.join(' ') + '\n');
    console.info = console.log;
    this._ids = 1;
    this._pending = new Map();
    this._commands = new Map();
    this._tools = new Map();
    this._events = new Map();
    this._intercept = null;
    this._schedules = new Map();
    this._prompt = null;
    this._filter = null;
    this._web = null;
    this._onInit = [];
    this._onSettings = [];
    this._onShutdown = [];
    this.settings = {};
    this.permissions = [];
    this.pluginId = process.env.FEIYU_PLUGIN_ID || '';
    this.dataDir = process.env.FEIYU_DATA_DIR || '';
    this.hostVersion = '';
    this.prefix = '#';
  }

  command(name, fn) {
    this._commands.set(name, fn);
    return this;
  }

  tool(name, description, properties, fn, options = {}) {
    const props = properties || {};
    const spec = {
      name,
      description,
      parameters: { type: 'object', properties: props, required: options.required || Object.keys(props) },
      mind: Boolean(options.mind),
    };
    if (options.timeout) spec.timeout = options.timeout;
    this._tools.set(name, { fn, spec });
    return this;
  }

  on(event, fn) {
    if (!this._events.has(event)) this._events.set(event, []);
    this._events.get(event).push(fn);
    return this;
  }

  intercept(fn) {
    this._intercept = fn;
    return this;
  }

  schedule(id, fn) {
    this._schedules.set(id, fn);
    return this;
  }

  prompt(fn) {
    this._prompt = fn;
    return this;
  }

  filter(fn) {
    this._filter = fn;
    return this;
  }

  web(fn) {
    this._web = fn;
    return this;
  }

  onInit(fn) {
    this._onInit.push(fn);
    return this;
  }

  onSettings(fn) {
    this._onSettings.push(fn);
    return this;
  }

  onShutdown(fn) {
    this._onShutdown.push(fn);
    return this;
  }

  call(method, params = {}, timeoutMs = 60000) {
    const id = this._ids++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this._pending.delete(id);
        reject(new RpcError(-32000, `${method} timed out`));
      }, timeoutMs);
      this._pending.set(id, { resolve, reject, timer });
      this._write({ jsonrpc: '2.0', id, method, params });
    });
  }

  notify(method, params = {}) {
    this._write({ jsonrpc: '2.0', method, params });
  }

  send({ groupId, userId, text, image } = {}) {
    const params = {};
    if (groupId) params.groupId = String(groupId);
    if (userId) params.userId = String(userId);
    if (text) params.text = text;
    if (image) params.image = image;
    return this.call('qq/send', params);
  }

  qq(action, params) {
    return this.call("qq/call", { action, params: params || {} });
  }

  history({ groupId, userId, count = 20 } = {}) {
    const params = { count };
    if (groupId) params.groupId = String(groupId);
    if (userId) params.userId = String(userId);
    return this.call('qq/history', params);
  }

  groups() {
    return this.call('qq/groups');
  }

  members(groupId) {
    return this.call('qq/members', { groupId: String(groupId) });
  }

  recall(about) {
    return this.call('memory/recall', { about });
  }

  remember(about, text) {
    return this.call('memory/add', { about, text });
  }

  llm({ prompt, messages, system, maxTokens } = {}) {
    const params = {};
    if (prompt !== undefined) params.prompt = prompt;
    if (messages !== undefined) params.messages = messages;
    if (system) params.system = system;
    if (maxTokens) params.maxTokens = maxTokens;
    return this.call('llm/chat', params, 180000);
  }

  async kvGet(key, fallback = null) {
    const result = await this.call('kv/get', { key });
    return result.value === null || result.value === undefined ? fallback : result.value;
  }

  kvSet(key, value) {
    return this.call('kv/set', { key, value });
  }

  kvDelete(key) {
    return this.call('kv/delete', { key });
  }

  kvList(prefix = '') {
    return this.call('kv/list', { prefix });
  }

  log(text, level = 'info') {
    this.notify('log', { level, text: String(text) });
  }

  notifyWeb(text) {
    return this.call('notify', { text });
  }

  toolsChanged() {
    this.notify('tools/changed');
  }

  run() {
    const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
    rl.on('line', line => this._receive(line));
    rl.on('close', () => this._shutdown());
  }

  _write(message) {
    this._out.write(JSON.stringify(message) + '\n');
  }

  _receive(line) {
    const text = line.trim();
    if (!text) return;
    let message;
    try {
      message = JSON.parse(text);
    } catch {
      return;
    }
    if (!message || typeof message !== 'object' || Array.isArray(message)) return;
    if (message.method) {
      if (message.method === 'shutdown') {
        this._shutdown();
        return;
      }
      this._serve(message);
      return;
    }
    const pending = this._pending.get(message.id);
    if (!pending) return;
    this._pending.delete(message.id);
    clearTimeout(pending.timer);
    if (message.error) pending.reject(new RpcError(message.error.code ?? -32000, message.error.message ?? 'host error'));
    else pending.resolve(message.result);
  }

  async _shutdown() {
    for (const fn of this._onShutdown) {
      try {
        await fn();
      } catch (err) {
        process.stderr.write(String(err && err.stack || err) + '\n');
      }
    }
    process.exit(0);
  }

  async _serve(message) {
    const id = message.id;
    const isRequest = id !== undefined && id !== null;
    try {
      const result = await this._handle(message.method, message.params || {}, isRequest);
      if (isRequest) this._write({ jsonrpc: '2.0', id, result: result === undefined ? null : result });
    } catch (err) {
      process.stderr.write(String(err && err.stack || err) + '\n');
      if (isRequest) {
        const code = err instanceof RpcError ? err.code : -32000;
        this._write({ jsonrpc: '2.0', id, error: { code, message: String(err && err.message || err) } });
      }
    }
  }

  _specs() {
    return [...this._tools.values()].map(t => t.spec);
  }

  async _handle(method, params, isRequest) {
    switch (method) {
      case 'initialize':
        this.settings = params.settings || {};
        this.permissions = params.permissions || [];
        this.hostVersion = params.hostVersion || '';
        this.prefix = params.prefix || '#';
        this.dataDir = params.dataDir || this.dataDir;
        for (const fn of this._onInit) await fn();
        return { tools: this._specs() };
      case 'tools/list':
        return { tools: this._specs() };
      case 'command/run': {
        const fn = this._commands.get(params.name);
        if (!fn) return { handled: false };
        const result = await fn(new Context(this, params));
        return { handled: result !== false };
      }
      case 'event': {
        const ctx = new Context(this, params);
        if (isRequest && this._intercept) return verdict(await this._intercept(ctx));
        for (const fn of [...(this._events.get(ctx.type) || []), ...(this._events.get('*') || [])]) await fn(ctx);
        return { action: 'pass' };
      }
      case 'tool/call': {
        const tool = this._tools.get(params.name);
        if (!tool) throw new RpcError(-32601, `unknown tool ${params.name}`);
        try {
          const result = await tool.fn(params.arguments || {});
          if (result && typeof result === 'object' && 'content' in result) return result;
          const text = typeof result === 'string' ? result : JSON.stringify(result);
          return { content: [{ type: 'text', text }] };
        } catch (err) {
          return { content: [{ type: 'text', text: String(err && err.message || err) }], isError: true };
        }
      }
      case 'schedule/fire': {
        const fn = this._schedules.get(params.id);
        if (fn) await fn();
        return null;
      }
      case 'prompt/contribute':
        return { text: this._prompt ? (await this._prompt(params.session || '', params.people || [])) || '' : '' };
      case 'filter/outgoing':
        return this._filter ? verdict(await this._filter(params.session || '', params.text || '')) : { action: 'pass' };
      case 'settings/changed':
        this.settings = params.settings || {};
        for (const fn of this._onSettings) await fn(this.settings);
        return null;
      case 'web/request': {
        if (!this._web) return { status: 404, body: 'this plugin has no web API' };
        const result = await this._web(params.method || 'GET', params.path || '/', params.query || '', params.body || '');
        if (result && typeof result === 'object' && 'status' in result) return result;
        const body = typeof result === 'string' ? result : JSON.stringify(result);
        return { status: 200, body, contentType: typeof result === 'string' ? 'text/plain; charset=utf-8' : 'application/json' };
      }
      default:
        throw new RpcError(-32601, `unknown method ${method}`);
    }
  }
}

function verdict(result) {
  if (result === undefined || result === null || result === true || result === 'pass') return { action: 'pass' };
  if (result === false || result === 'drop') return { action: 'drop' };
  if (result === 'hide') return { action: 'hide' };
  if (Array.isArray(result) && result[0] === 'rewrite') return { action: 'rewrite', text: result[1] };
  if (typeof result === 'string') return { action: 'rewrite', text: result };
  return { action: 'pass' };
}

module.exports = { Plugin, Context, RpcError };
