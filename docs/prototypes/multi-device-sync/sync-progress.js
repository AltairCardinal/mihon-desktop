(function (root) {
  'use strict';
  const terminalStates = ['succeeded', 'partial', 'failed', 'blocked', 'cancelled'];
  const titles = { running: '同步中', pausing: '正在暂停', paused: '已暂停', network: '等待网络', system: '等待继续', retry: '等待重试', recovering: '正在核对进度', succeeded: '同步完成', partial: '部分完成', failed: '同步未完成', blocked: '同步需要处理', cancelled: '本次同步已取消' };
  const duration = seconds => seconds === 0 ? '正在收尾' : `约 ${Math.ceil(seconds)} 秒`;
  function createDisplay(clock = Date.now) {
    let key, since = 0, changes = [], locked = false, etaSince = null, etaScope;
    return fact => {
      const now = clock();
      const nextKey = [fact.action, fact.direction, fact.scope].join('|');
      if (key !== nextKey) {
        if (key !== undefined) changes.push(now);
        changes = changes.filter(time => now - time <= 2000);
        locked = locked || changes.length >= 3;
        key = nextKey; since = now;
      }
      if (locked && now - since >= 2000) { locked = false; changes = []; }
      const stable = !locked && now - since >= 800;
      const terminal = terminalStates.includes(fact.state);
      const active = fact.state === 'running';
      const age = Math.max(0, Math.floor((now - fact.lastProgressAt) / 1000));
      const canEstimate = active && age < 10 && Number.isFinite(fact.wholeEta);
      if (!canEstimate || etaScope !== nextKey) etaSince = null;
      etaScope = nextKey;
      if (canEstimate && etaSince === null) etaSince = now;
      let eta = canEstimate ? now - etaSince >= 2000 ? duration(fact.wholeEta) : '估算中' : '暂无法估算';
      if (!active) eta = terminal ? '—' : fact.state === 'recovering' ? '正在核对' : titles[fact.state];
      let explanation = '已完成的进度会保留';
      if (active && age >= 10) explanation = age >= 60 ? '已有一段时间没有新进展，可查看详情' : '正在等待当前步骤返回';
      const reasons = { paused: '已完成的进度会保留，继续需由你手动操作', pausing: '正在保存进度', network: '网络恢复后将按现有调度继续', system: '正在等待系统安排', retry: `服务暂时限制请求，${Math.max(0, Math.ceil((fact.nextRetryAt - now) / 1000))} 秒后自动重试`, recovering: '正在恢复已保存的进度', succeeded: fact.noWork ? '没有需要同步的数据' : '本次数据已完成同步', partial: '本次已结束，仍有数据需要处理', failed: fact.exhausted ? '已达到重试次数上限，进度已保留' : '本次同步未完成，进度已保留', blocked: 'GitHub 授权需要重新连接', cancelled: '已确认的结果会保留' };
      explanation = fact.reason || reasons[fact.state] || explanation;
      const elapsedSeconds = Math.max(0, Math.floor(((terminal ? fact.endedAt : now) - fact.startedAt) / 1000));
      const elapsed = `${String(Math.floor(elapsedSeconds / 60)).padStart(2, '0')}:${String(elapsedSeconds % 60).padStart(2, '0')}`;
      const percent = stable && !terminal && fact.state !== 'recovering' && ['接收', '上传'].includes(fact.action) && Number.isFinite(fact.percent) ? fact.percent : null;
      const specific = { 准备: '正在准备数据', 接收: '正在接收数据', 校验: '正在校验数据', 合并: '正在合并数据', 上传: '正在上传数据', 核对: '正在核对结果' };
      let action = active ? stable ? specific[fact.action] || '正在交换数据' : fact.action === '准备' ? '正在准备数据' : '正在交换数据' : terminal ? '本次运行已结束' : fact.state === 'recovering' ? '正在核对已保存进度' : '进度已保留';
      if (percent !== null && active) action += ` · 传输 ${percent}%`;
      return { ...fact, title: fact.state === 'succeeded' && fact.noWork ? '已是最新' : titles[fact.state], action, percent, elapsed, eta, explanation, active, terminal, age };
    };
  }
  const api = { createDisplay, terminalStates, duration };
  if (typeof module === 'object') module.exports = api;
  else root.MihonSyncProgress = api;
})(typeof window === 'object' ? window : globalThis);
