export const $ = (sel, root = document) => root.querySelector(sel);
export const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

export function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null) node.textContent = text;
  return node;
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

export async function poll(fn, ms) {
  for (;;) {
    try {
      await fn();
    } catch (e) {
      console.error(e);
    }
    await sleep(ms);
  }
}
