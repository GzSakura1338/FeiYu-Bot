import { el } from './dom.js';
import { showSnackbar } from './snackbar.js';

const KEEP = '__keep__';

function labelFor(key, spec) {
  return spec.title || key;
}

function stringField(key, spec, value, onInput) {
  const secret = spec.secret === true;
  const input = el('input', 'body-large');
  input.type = secret ? 'password' : 'text';
  input.value = secret && value === KEEP ? '' : value ?? '';
  input.placeholder = secret && value === KEEP ? '已设置，留空就不改' : spec.placeholder ?? '';
  if (spec.maxLength) input.maxLength = spec.maxLength;
  let touched = false;
  input.addEventListener('input', () => {
    touched = true;
    onInput();
  });
  return { input, read: () => (secret && !touched ? KEEP : input.value) };
}

function enumField(key, spec, value, onInput) {
  const select = el('select', 'body-large');
  (spec.enum ?? []).forEach(option => {
    const node = el('option', '', String(option));
    node.value = String(option);
    if (String(value) === String(option)) node.selected = true;
    select.append(node);
  });
  select.addEventListener('change', onInput);
  const numeric = spec.type === 'number' || spec.type === 'integer';
  return { input: select, read: () => (numeric ? Number(select.value) : select.value) };
}

function numberField(key, spec, value, onInput) {
  const input = el('input', 'body-large');
  input.type = 'number';
  input.value = value ?? '';
  if (spec.minimum != null) input.min = spec.minimum;
  if (spec.maximum != null) input.max = spec.maximum;
  if (spec.type === 'integer') input.step = 1;
  input.addEventListener('input', onInput);
  return { input, read: () => (input.value === '' ? null : Number(input.value)) };
}

function booleanField(key, spec, value, onInput) {
  const toggle = el('button', 'switch');
  toggle.type = 'button';
  toggle.setAttribute('role', 'switch');
  toggle.setAttribute('aria-checked', String(Boolean(value)));
  toggle.append(el('span', 'switch-handle'));
  toggle.addEventListener('click', () => {
    toggle.setAttribute('aria-checked', String(toggle.getAttribute('aria-checked') !== 'true'));
    onInput();
  });
  return { input: toggle, read: () => toggle.getAttribute('aria-checked') === 'true', inline: true };
}

function arrayField(key, spec, value, onInput) {
  const area = el('textarea', 'body-large');
  area.rows = 3;
  area.value = (value ?? []).join('\n');
  area.placeholder = '一行一个';
  area.addEventListener('input', onInput);
  const numeric = spec.items?.type === 'number' || spec.items?.type === 'integer';
  return {
    input: area,
    read: () => area.value.split('\n').map(s => s.trim()).filter(Boolean).map(s => (numeric ? Number(s) : s)),
  };
}

export function buildSettingsForm(detail, onSave) {
  const schema = detail.settingsSchema ?? {};
  const values = detail.settings ?? {};
  const form = el('form', 'plugin-settings');
  const fields = {};
  const save = el('button', 'filled-button filled-button-medium label-large state-layer', '保存设置');
  save.type = 'submit';
  save.disabled = true;
  const markDirty = () => {
    save.disabled = false;
  };
  for (const [key, spec] of Object.entries(schema.properties ?? {})) {
    const value = values[key];
    const built = spec.enum ? enumField(key, spec, value, markDirty)
      : spec.type === 'boolean' ? booleanField(key, spec, value, markDirty)
      : spec.type === 'number' || spec.type === 'integer' ? numberField(key, spec, value, markDirty)
      : spec.type === 'array' ? arrayField(key, spec, value, markDirty)
      : stringField(key, spec, value, markDirty);
    fields[key] = built;
    if (built.inline) {
      const row = el('div', 'switch-field');
      const text = el('div');
      text.append(el('div', 'title-small', labelFor(key, spec)));
      if (spec.description) text.append(el('div', 'body-small switch-hint', spec.description));
      row.append(text, built.input);
      form.append(row);
    } else {
      const field = el('label', 'field');
      field.append(el('span', 'label-medium', labelFor(key, spec)), built.input);
      if (spec.description) field.append(el('span', 'body-small field-hint', spec.description));
      form.append(field);
    }
  }
  const actions = el('div', 'plugin-settings-actions');
  actions.append(save);
  form.append(actions);
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const out = {};
    for (const [key, field] of Object.entries(fields)) {
      const value = field.read();
      if (value !== null) out[key] = value;
    }
    save.disabled = true;
    try {
      await onSave(out);
    } catch (err) {
      save.disabled = false;
      showSnackbar(err.message, 'error');
    }
  });
  return form;
}
