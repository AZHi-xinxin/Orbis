(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  let data = null, screen = 'select', mode = 'NORMAL', viewingArchive = false, rulesReturn = 'select';
  let priorActive = null, renderedCatalog = '', renderedHistory = '', renderedTranscript = '';
  const modeLabel = value => ({ EASY: '轻松', NORMAL: '正常', HARD: '困难' }[value] || value);
  const playerLabel = value => value === 'HUMAN' ? '你' : '伙伴';
  const closed = session => !session || session.revealed || session.abandoned;
  function send(action, params = {}) {
    if (window.OrbisSoupBridge && typeof window.OrbisSoupBridge.postMessage === 'function')
      window.OrbisSoupBridge.postMessage(JSON.stringify({ action, params }));
  }
  function text(tag, value, className = '') { const node = document.createElement(tag); node.textContent = value; node.className = className; return node; }
  function button(label, action, className = 'ts-btn ts-btn--ghost') {
    const node = text('button', label, className); node.dataset.action = action; return node;
  }
  function show(next) {
    screen = next;
    for (const section of document.querySelectorAll('.ts-screen')) section.classList.toggle('ts-screen--active', section.id === `screen-${next}`);
    window.scrollTo(0, 0);
  }
  function current() { return viewingArchive ? data?.archive : data?.active; }
  function back() {
    if (screen === 'rules') show(rulesReturn);
    else if (screen === 'mode') show('select');
    else if (viewingArchive) { viewingArchive = false; renderGame(); show(data?.active ? 'game' : 'select'); }
    else send('close');
  }
  window.soupBack = back;
  $('back').onclick = back;
  $('host').onclick = () => send('host');
  $('open-turtle').onclick = () => { rulesReturn = 'select'; show('rules'); };
  $('rules-back').onclick = () => show(rulesReturn);
  $('accept-rules').onclick = () => show(rulesReturn === 'game' ? 'game' : 'mode');
  $('game-rules').onclick = () => { rulesReturn = 'game'; show('rules'); };
  $('new-game').onclick = () => { viewingArchive = false; show('mode'); };
  $('return-current').onclick = () => { viewingArchive = false; renderGame(); show(data?.active ? 'game' : 'select'); };
  document.addEventListener('click', event => {
    const target = event.target.closest('button[data-action]');
    if (target && !target.disabled) send(target.dataset.action);
  });
  for (const node of document.querySelectorAll('[data-mode]')) node.onclick = () => {
    mode = node.dataset.mode;
    for (const item of document.querySelectorAll('[data-mode]')) {
      const selected = item.dataset.mode === mode;
      item.classList.toggle('ts-mode--selected', selected); item.setAttribute('aria-pressed', String(selected));
    }
  };
  $('puzzle').onchange = () => { $('puzzle-note').textContent = data?.puzzles.find(item => item.id === $('puzzle').value)?.note || ''; };
  $('start').onclick = () => {
    if (!data || data.busy || data.blocked || !closed(data.active)) return;
    const chosen = $('puzzle').value || data.puzzles[Math.floor(Math.random() * data.puzzles.length)]?.id;
    if (chosen) { $('start').disabled = true; send('start', { puzzle: chosen, mode }); }
  };
  function renderCatalog() {
    const signature = JSON.stringify(data.puzzles);
    if (signature !== renderedCatalog) {
      renderedCatalog = signature;
      $('puzzle').replaceChildren(new Option('随机抽取一题', ''));
      for (const item of data.puzzles) $('puzzle').append(new Option(`${item.title} · ${item.difficulty}`, item.id));
    }
    $('host-note').textContent = data.host_configured ? '独立主持已选好 · 每次调用仍会再次确认' : '可以先本机开局；首次提问前请在右上角选好独立主持。';
    $('start').disabled = data.busy || data.blocked || !closed(data.active);
    const historySig = JSON.stringify(data.history);
    if (historySig !== renderedHistory) {
      renderedHistory = historySig; $('history').replaceChildren();
      for (const item of data.history) {
        const node = text('button', `${item.title} · ${item.mode} · ${item.revealed ? '已揭底' : '已结束'}`);
        node.onclick = () => {
          if (data.archive?.session_id === item.id) { viewingArchive = true; renderGame(); show('game'); }
          else send('archive', { session: item.id });
        }; $('history').append(node);
      }
    }
    $('history-section').hidden = data.history.length === 0;
  }
  function renderGame() {
    const session = current(); if (!session) return;
    const ended = closed(session), controls = !viewingArchive && !ended && !data.busy && !data.blocked && !session.pending && !session.pending_proposal;
    $('case-label').textContent = viewingArchive ? 'A PAST STORY' : 'CURRENT CASE';
    $('game-title').textContent = session.title; $('game-mode').textContent = modeLabel(session.mode);
    $('game-difficulty').textContent = session.difficulty; $('face').textContent = session.soup_face;
    $('game-status').textContent = session.abandoned ? '已放弃 · 记录保留' : session.revealed ? '本局已揭底' : `轮到${playerLabel(session.current_player)}提问`;
    $('human-left').textContent = session.human_questions_left ?? '∞'; $('partner-left').textContent = session.teammate_questions_left ?? '∞';
    $('hint-left').textContent = `${session.hints_total - session.hints_used} / ${session.hints_total}`;
    $('human-count').classList.toggle('ts-count--active', !ended && session.current_player === 'HUMAN');
    $('partner-count').classList.toggle('ts-count--active', !ended && session.current_player === 'TEAMMATE');
    $('calls').textContent = `主持请求 ${session.calls_used} / ${session.call_limit}`;
    for (const node of $('game-actions').querySelectorAll('[data-action]')) {
      node.disabled = !controls || (node.dataset.action === 'ask' && (session.current_player !== 'HUMAN' || session.human_questions_left === 0)) ||
        (node.dataset.action === 'hint' && session.hints_used >= session.hints_total) ||
        (node.dataset.action === 'submit' && session.submissions.some(item => item.player === 'HUMAN'));
    }
    $('take-turn').hidden = !controls || session.current_player !== 'TEAMMATE' || session.human_questions_left === 0;
    $('round-controls').hidden = viewingArchive || ended;
    for (const node of $('round-controls').querySelectorAll('button')) node.disabled = data.busy || data.blocked || session.pending?.state === 'RUNNING';
    $('new-game').hidden = viewingArchive || !ended; $('return-current').hidden = !viewingArchive;
    const pending = session.pending; $('pending').hidden = !pending || viewingArchive; $('pending').replaceChildren();
    if (pending && !viewingArchive) {
      $('pending').append(text('p', pending.state === 'RUNNING' ? '主持正在处理这次请求，不会重复发送。' : '上次结果未确认，可能已产生费用。不会自动重试。'));
      $('pending').append(text('p', `${playerLabel(pending.player)}：${pending.text}`));
      if (pending.state === 'UNKNOWN') { const retry = button('核对后手动重试…', 'retry'); retry.disabled = data.busy || data.blocked; $('pending').append(retry); }
    }
    const proposal = session.pending_proposal; $('proposal').hidden = !proposal || viewingArchive; $('proposal').replaceChildren();
    if (proposal && !viewingArchive) {
      $('proposal').append(text('strong', proposal.action === 'ASK' ? '伙伴有一个问题' : '伙伴准备提交的推理'), text('p', proposal.text), text('p', '尚未调用主持，也未扣次数。请你核对后决定。'));
      const accept = button('核对并确认发送…', 'accept_proposal', 'ts-btn'), decline = button('不采用', 'decline_proposal');
      accept.disabled = decline.disabled = data.busy || data.blocked; $('proposal').append(accept, decline);
    }
    const transcriptKey = JSON.stringify([session.session_id, session.questions, session.revealed_hints, session.submissions, session.revealed, session.soup_bottom]);
    if (transcriptKey === renderedTranscript) return;
    renderedTranscript = transcriptKey;
    const log = $('log'), scroll = log.scrollTop; log.replaceChildren();
    for (const item of session.questions) {
      const row = text('div', '', 'ts-log-item'), main = text('div', '', 'ts-log-main');
      main.append(text('b', playerLabel(item.player)), text('p', item.question));
      const verdict = text('span', item.answer, 'ts-answer'); verdict.dataset.answer = item.answer; main.append(verdict);
      row.append(text('span', item.player === 'HUMAN' ? '我' : '伴', 'ts-log-avatar'), main); log.append(row);
    }
    session.revealed_hints.forEach((hint, index) => {
      const row = text('div', '', 'ts-log-item'), main = text('div', '', 'ts-log-main');
      main.append(text('b', `阶梯提示 ${index + 1}`), text('p', hint)); row.append(text('span', '✦', 'ts-log-avatar'), main); log.append(row);
    });
    if (!log.children.length) log.append(text('p', '故事已摆在桌上。\n从第一个问题开始吧。', 'soup-empty'));
    log.scrollTop = scroll; $('log-count').textContent = `${session.questions.length + session.revealed_hints.length} 条`;
    $('scores').replaceChildren();
    for (const score of session.submissions) {
      const card = text('article', '', 'soup-score'); card.append(text('h2', `${playerLabel(score.player)}的推理 · ${score.rank}`));
      const orb = text('div', '', 'ts-score-orb'); orb.append(text('b', score.score), text('small', '本机加权评分')); card.append(orb);
      for (const [key, label] of [['key_plot', '关键情节'], ['logic', '逻辑'], ['detail', '细节']]) {
        const row = text('div', '', 'ts-score-row'), labels = text('div', ''); labels.append(text('span', label), text('span', `${score[key]} / 100`));
        const bar = document.createElement('progress'); bar.max = 100; bar.value = score[key]; bar.setAttribute('aria-label', label); row.append(labels, bar); card.append(row);
      }
      const details = text('details', ''); details.append(text('summary', '查看提交的完整推理'), text('p', score.submitted_answer)); card.append(details);
      card.append(text('p', session.revealed ? score.comment || '' : '主持评语在揭底后显示，避免提前透露答案。')); $('scores').append(card);
    }
    $('bottom').hidden = !session.revealed;
    $('bottom').replaceChildren(); if (session.revealed) $('bottom').append(text('b', '原汤底'), text('p', session.soup_bottom || ''));
  }
  window.soupReceive = next => {
    const wasReady = data !== null, previousArchive = data?.archive?.session_id;
    data = next;
    $('notice').textContent = data.notice; $('notice').hidden = !data.notice;
    $('blocked').hidden = !data.blocked; renderCatalog();
    const activeId = data.active?.session_id || null;
    if (!wasReady || activeId !== priorActive) { viewingArchive = false; if (activeId) show('game'); }
    else if (data.archive && data.archive.session_id !== previousArchive) { viewingArchive = true; show('game'); }
    priorActive = activeId; renderGame();
  };
  for (const item of document.querySelectorAll('[data-mode]')) item.setAttribute('aria-pressed', String(item.dataset.mode === mode));
  send('ready');
})();
