const toggle = document.getElementById('theme-toggle');

toggle.addEventListener('click', () => {
  const light = document.body.classList.toggle('theme-light');
  document.body.classList.toggle('theme-dark', !light);
  toggle.textContent = light ? '切换深色' : '切换浅色';
  document.querySelectorAll('iframe').forEach(frame => {
    frame.contentWindow.postMessage({
      type: 'trial-theme',
      theme: light ? 'light' : 'dark',
    }, '*');
  });
});
