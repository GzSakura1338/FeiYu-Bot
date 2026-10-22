import { setIcon } from './icons.js';

export function openDialog(dialog, { guard } = {}) {
  return new Promise(resolve => {
    dialog.returnValue = '';
    dialog.showModal();

    let pressedOnScrim = false;
    let asking = false;
    const dismiss = async () => {
      if (asking) return;
      if (guard?.()) {
        asking = true;
        dialog.classList.add('shake');
        const discard = await confirmDialog({
          icon: 'warning',
          title: '放弃未保存的修改？',
          text: '填写的内容还没有保存，关闭后会丢失。',
          confirm: '放弃',
        });
        dialog.classList.remove('shake');
        asking = false;
        if (!discard) return;
      }
      closeDialog(dialog, 'cancel');
    };
    const onPointerDown = e => {
      pressedOnScrim = e.target === dialog;
    };
    const onClick = e => {
      if (e.target === dialog && pressedOnScrim) dismiss();
      pressedOnScrim = false;
    };
    const onCancel = e => {
      e.preventDefault();
      dismiss();
    };
    dialog.addEventListener('pointerdown', onPointerDown);
    dialog.addEventListener('click', onClick);
    dialog.addEventListener('cancel', onCancel);
    dialog.addEventListener('close', () => {
      dialog.removeEventListener('pointerdown', onPointerDown);
      dialog.removeEventListener('click', onClick);
      dialog.removeEventListener('cancel', onCancel);
      resolve(dialog.returnValue || 'cancel');
    }, { once: true });
  });
}

export function closeDialog(dialog, value = '') {
  if (!dialog.open || dialog.classList.contains('closing')) return;
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const finish = () => {
    dialog.classList.remove('closing');
    dialog.close(value);
  };
  if (reduce) return finish();
  dialog.classList.add('closing');
  const onEnd = e => {
    if (e.target !== dialog) return;
    dialog.removeEventListener('animationend', onEnd);
    finish();
  };
  dialog.addEventListener('animationend', onEnd);
}

export function enhanceDialog(dialog) {
  dialog.addEventListener('submit', e => {
    if (e.defaultPrevented) return;
    e.preventDefault();
    closeDialog(dialog, e.submitter?.value ?? '');
  });
}

let confirmReady = false;

export async function confirmDialog({ title, text, confirm = '确定', icon = 'help' }) {
  const dialog = document.getElementById('confirm-dialog');
  if (!confirmReady) {
    enhanceDialog(dialog);
    confirmReady = true;
  }
  dialog.querySelector('#confirm-title').textContent = title;
  dialog.querySelector('#confirm-text').textContent = text;
  dialog.querySelector('#confirm-ok').textContent = confirm;
  setIcon(dialog.querySelector('#confirm-icon'), icon);
  return (await openDialog(dialog)) === 'confirm';
}
