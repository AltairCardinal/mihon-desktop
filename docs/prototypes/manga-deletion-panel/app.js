(() => {
  const panel = document.querySelector('#panel');
  const picker = document.querySelector('#picker');
  const details = document.querySelector('#details');
  const modeInputs = [...document.querySelectorAll('input[name=mode]')];
  const labels = ['刚才读完的章节','倒数第二个已读章节','倒数第三个已读章节','倒数第四个已读章节','倒数第五个已读章节'];
  let saved = {mode:'on',slots:0,bookmarked:false};
  let draft = {...saved};
  const screen = new URLSearchParams(location.search).get('screen');
  document.body.dataset.screen = ['phone','tablet'].includes(screen) ? screen : 'desktop';
  function apply() {
    saved = {...draft};
    const mode = {on:'开启',off:'关闭',inherit:'跟随全局'}[saved.mode];
    document.querySelector('#saved-summary').textContent = mode + (saved.mode === 'on' ? ' · '+labels[saved.slots] : '');
    document.body.dataset.saved = JSON.stringify(saved);
    render();
  }
  function render() {
    modeInputs.forEach(input => input.checked = input.value === draft.mode);
    const enabled = draft.mode === 'on';
    details.classList.toggle('collapsed', !enabled);
    details.inert = !enabled;
    details.setAttribute('aria-hidden', String(!enabled));
    document.querySelector('#timing-value').textContent = labels[draft.slots];
    document.querySelector('#bookmark').setAttribute('aria-checked', String(draft.bookmarked));
  }
  function open() { draft = {...saved}; render(); panel.showModal(); document.querySelector('#panel-title').focus(); }
  function close() { draft = {...saved}; panel.close(); document.querySelector('#open').focus(); }
  function cancelPicker() { picker.close(); document.querySelector('#timing').focus(); }
  modeInputs.forEach(input => input.addEventListener('change', () => { draft.mode = input.value; apply(); }));
  document.querySelector('#bookmark').onclick = () => { draft.bookmarked = !draft.bookmarked; apply(); };
  document.querySelector('#open').onclick = open;
  document.querySelector('#close').onclick = close;
  panel.addEventListener('cancel', event => { event.preventDefault(); close(); });
  document.querySelector('#settings').onsubmit = event => event.preventDefault();
  document.querySelector('#timing').onclick = () => {
    document.querySelector('#timings').replaceChildren(...labels.map((text,index) => {
      const label = document.createElement('label'); label.className = 'mode-row';
      const input = document.createElement('input'); input.type='radio'; input.name='timing'; input.value=String(index); input.checked=draft.slots===index;
      const caption = document.createElement('span'); caption.textContent=text;
      input.onchange = () => { draft.slots=index; apply(); cancelPicker(); };
      label.append(input,caption); return label;
    }));
    picker.showModal(); picker.querySelector('input:checked').focus();
  };
  picker.addEventListener('cancel', event => { event.preventDefault(); cancelPicker(); });
  document.querySelector('#picker-cancel').onclick = cancelPicker;
  for (const [dialog,cancel] of [[panel,close],[picker,cancelPicker]]) {
    dialog.addEventListener('keydown',event=>{
      if(event.key!=='Tab') return;
      const stops=[...dialog.querySelectorAll('button,input')].filter(el=>
        !el.disabled && !el.closest('[inert]') && (el.type!=='radio'||el.checked));
      const first=stops[0], last=stops.at(-1);
      if(event.shiftKey && document.activeElement===first){event.preventDefault();last.focus();}
      else if(!event.shiftKey && document.activeElement===last){event.preventDefault();first.focus();}
    });
    dialog.addEventListener('click',event=>{
      const r=dialog.getBoundingClientRect();
      if(event.target===dialog && (event.clientX<r.left||event.clientX>r.right||event.clientY<r.top||event.clientY>r.bottom)) cancel();
    });
  }
  window.addEventListener('message',event=>{
    if(event.source===parent && event.data?.type==='theme' && ['light','dark'].includes(event.data.theme)) document.documentElement.dataset.theme=event.data.theme;
  });
  document.documentElement.dataset.theme = new URLSearchParams(location.search).get('theme')==='light'?'light':'dark';
  apply();
  open();
})();
