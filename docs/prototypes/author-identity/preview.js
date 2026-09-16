(() => {
  const el = id => document.getElementById(id);
  const send = message => el('preview-' + el('target').value).contentWindow.postMessage({ channel: 'author-demo', ...message }, '*');
  el('apply-scenario').onclick = () => send({ scenario: el('scenario').value });
  el('reset').onclick = () => send({ scenario: 'history' });
  el('theme').onchange = () => {
    document.body.className = 'preview theme-' + el('theme').value;
    for (const platform of ['windows', 'android']) el('preview-' + platform).contentWindow.postMessage({ channel: 'author-demo', theme: el('theme').value }, '*');
  };
  el('narrow').onchange = () => el('preview-android').style.width = el('narrow').checked ? '320px' : '390px';
})();
