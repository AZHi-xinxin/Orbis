package me.rerere.rikkahub.data.orbis

/** Source-only starting point. Merely reading this object never installs or launches a game. */
internal object OrbisMiniGameTemplates {
    const val ID = "tic-tac-toe-responsive-v1"

    val usage = """
        自包含单文件 HTML/JS 井字棋起手模板；默认只返回源码，不自动安装。
        可保留桥接与自适应布局后改写玩法，再将完整 html 交给 orbis_games_install。
        人类执 X；O 是本地简易程序，不是 AI 模型实时下棋。胜负以人类 X 为视角，move_count 统计双方实际落子总数。
        页面顶部对齐、正常文档流可滚动；棋盘按真实 innerWidth/innerHeight 扣除自身标题、说明和按钮高度，监听 resize。
        矮窗/大字体下先保留标题和可读控件，放不下时纵向滚动，不用固定 100vh 整页居中或裁掉顶部。
        终局只调用 OrbisGame.finish(JSON.stringify({result:'win'|'loss'|'draw',move_count:整数}))；返回 JSON 的 ok===true 才确认保存。
        失败不宣称成功，只允许重试同一终局结果；不在同一个宿主 session 清空棋盘写第二局。
        想重开时关闭当前游戏，回游戏库重新打开，由宿主建立新 session。无外联、CDN、storage 或其他原生权限。
    """.trimIndent()

    val html = """
        <!doctype html>
        <html lang="zh-CN">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>井字棋 · 本地程序对手</title>
        <style>
          :root { color-scheme: light dark; --gap: 12px; }
          * { box-sizing: border-box; }
          html { margin: 0; padding: 0; overflow-y: auto; }
          body { margin: 0; padding: 12px; font: 16px/1.45 system-ui,sans-serif;
            color: #292739; background: #faf6ed; overflow-wrap: anywhere; }
          main { width: 100%; max-width: 34rem; margin: 0 auto; display: flex;
            flex-direction: column; align-items: center; gap: var(--gap); }
          header,footer { width: 100%; min-width: 0; }
          h1 { font-size: 1.35rem; line-height: 1.25; margin: 0 0 6px; }
          p { margin: 6px 0; }
          .muted { color: #646078; font-size: .9rem; }
          #game-status { font-weight: 650; }
          #game-board { display: grid; grid-template-columns: repeat(3,minmax(0,1fr));
            grid-template-rows: repeat(3,minmax(0,1fr)); gap: 6px;
            width: min(100%,360px); aspect-ratio: 1; flex: none; }
          button { font: inherit; color: inherit; touch-action: manipulation;
            border: 1px solid #c4bbd2; background: #fffdf8; border-radius: 12px;
            cursor: pointer; white-space: normal; overflow-wrap: anywhere; }
          .cell { min-width: 0; min-height: 0; padding: 0;
            font: 700 var(--mark-size,36px)/1 system-ui,sans-serif; }
          .cell[data-mark="X"] { color: #735a89; }
          .cell[data-mark="O"] { color: #796116; }
          .cell:disabled { cursor: default; opacity: 1; }
          button:focus-visible { outline: 3px solid #8b74ad; outline-offset: 2px; }
          #game-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px; }
          #game-actions button { min-height: 44px; padding: 8px 12px; max-width: 100%; }
          [hidden] { display: none !important; }
          #result-receipt { font-size: .9rem; }
          @media (prefers-color-scheme: dark) {
            body { color: #eee8f5; background: #242235; }
            .muted { color: #c0b7cf; }
            button { background: #343045; border-color: #736481; }
            .cell[data-mark="X"] { color: #dfc6f4; }
            .cell[data-mark="O"] { color: #f0d986; }
          }
        </style>
        </head>
        <body>
        <main id="game-root">
          <header id="game-header">
            <h1 id="game-title">井字棋</h1>
            <p class="muted">你执 X；O 是本地简易程序，不是 AI 模型实时对战。</p>
            <p id="game-status" role="status" aria-live="polite">轮到你：选一个空格落 X。</p>
          </header>
          <div id="game-board" role="group" aria-label="井字棋棋盘"></div>
          <footer id="game-footer">
            <p class="muted">横、竖或斜线连成三个即获胜。小窗口放不下时可以向下滑动。</p>
            <p id="result-receipt" role="status" aria-live="polite" data-saved="false">本局尚未结束，暂未提交战绩。</p>
            <div id="game-actions">
              <button id="retry-result" type="button" hidden>重试保存本局结果</button>
              <button id="replay-guide" type="button">如何再来一局</button>
            </div>
            <p id="replay-note" class="muted" hidden>先关闭当前游戏，回游戏库重新打开，宿主才会建立新的一局。本页面不会清空或改写当前战绩。</p>
          </footer>
        </main>
        <script>
        (function () {
          'use strict';
          const board = Array(9).fill('');
          const lines = [[0,1,2],[3,4,5],[6,7,8],[0,3,6],[1,4,7],[2,5,8],[0,4,8],[2,4,6]];
          const boardElement = document.getElementById('game-board');
          const statusElement = document.getElementById('game-status');
          const receiptElement = document.getElementById('result-receipt');
          const retryButton = document.getElementById('retry-result');
          const buttons = [];
          let moves = 0, terminal = null, saved = false, submitting = false;

          // Content is top-aligned. When readable/tappable content needs more room,
          // grow the document and scroll instead of centering it above the viewport.
          let fitPending = false;
          function scheduleFit() {
            if (fitPending) return;
            fitPending = true;
            requestAnimationFrame(function () { fitPending = false; fitBoard(); });
          }
          function fitBoard() {
            const root = document.getElementById('game-root');
            const header = document.getElementById('game-header');
            const footer = document.getElementById('game-footer');
            const style = getComputedStyle(document.body);
            const rootStyle = getComputedStyle(root);
            const gap = parseFloat(rootStyle.rowGap) || 12;
            const paddingX = (parseFloat(style.paddingLeft)||0) + (parseFloat(style.paddingRight)||0);
            const paddingY = (parseFloat(style.paddingTop)||0) + (parseFloat(style.paddingBottom)||0);
            const viewportWidth = Math.max(1, window.innerWidth);
            const viewportHeight = Math.max(1, window.innerHeight);
            const visibleHeight = window.visualViewport ? Math.min(viewportHeight, window.visualViewport.height) : viewportHeight;
            const widthBudget = Math.max(1, Math.min(root.clientWidth, viewportWidth - paddingX));
            const heightBudget = visibleHeight - paddingY - header.getBoundingClientRect().height - footer.getBoundingClientRect().height - 2 * gap;
            // 44px cells + gaps when width permits. Height shortage becomes scroll.
            const readableMinimum = Math.min(widthBudget, 144);
            const size = Math.floor(Math.max(1, Math.min(widthBudget, 360, Math.max(readableMinimum, heightBudget))));
            boardElement.style.width = size + 'px';
            boardElement.style.height = size + 'px';
            boardElement.style.setProperty('--mark-size', Math.max(16, Math.floor(size / 7)) + 'px');
          }

          function paint() {
            buttons.forEach(function (button, index) {
              button.textContent = board[index] || '·';
              button.dataset.mark = board[index];
              button.disabled = !!terminal || board[index] !== '';
              button.setAttribute('aria-label', '第 ' + (index + 1) + ' 格，' + (board[index] || '空格，落 X'));
            });
          }
          function winner(mark) { return lines.some(function (line) { return line.every(function (i) { return board[i] === mark; }); }); }
          function completingMove(mark) {
            for (let i = 0; i < 9; i++) {
              if (board[i]) continue;
              board[i] = mark;
              const wins = winner(mark);
              board[i] = '';
              if (wins) return i;
            }
            return -1;
          }
          // Deterministic, local rules: win, block, center, corner, remaining cell.
          // This is intentionally not a remote model call.
          function chooseLocalMove() {
            let choice = completingMove('O');
            if (choice < 0) choice = completingMove('X');
            if (choice >= 0) return choice;
            return [4,0,2,6,8,1,3,5,7].find(function (i) { return board[i] === ''; });
          }
          function submitResult() {
            if (!terminal || saved || submitting) return;
            submitting = true;
            retryButton.disabled = true;
            receiptElement.textContent = '正在保存本局结果…';
            try {
              if (!window.OrbisGame || typeof window.OrbisGame.finish !== 'function') throw new Error('bridge_unavailable');
              const receipt = JSON.parse(window.OrbisGame.finish(JSON.stringify(terminal)));
              if (!receipt || receipt.ok !== true) throw new Error('not_saved');
              saved = true;
              receiptElement.dataset.saved = 'true';
              receiptElement.textContent = '本局结果已保存（双方共 ' + terminal.move_count + ' 手）。';
              retryButton.hidden = true;
            } catch (_) {
              receiptElement.dataset.saved = 'false';
              receiptElement.textContent = '本局已结束，但尚未确认保存。可重试同一结果；不会开启新的一局。';
              retryButton.hidden = false;
            } finally {
              submitting = false;
              retryButton.disabled = false;
              scheduleFit();
            }
          }
          function finishIfNeeded() {
            let result = null;
            if (winner('X')) result = 'win';
            else if (winner('O')) result = 'loss';
            else if (moves === 9) result = 'draw';
            if (!result) return false;
            terminal = Object.freeze({result: result, move_count: moves});
            statusElement.textContent = result === 'win' ? '你赢了！X 连成三个。' : result === 'loss' ? '本地程序赢了，O 连成三个。' : '和棋，棋盘已满。';
            receiptElement.dataset.result = result;
            receiptElement.dataset.moveCount = String(moves);
            paint();
            submitResult();
            return true;
          }
          function humanMove(index) {
            if (terminal || board[index]) return;
            board[index] = 'X'; moves++;
            if (finishIfNeeded()) return;
            const localMove = chooseLocalMove();
            if (localMove !== undefined) { board[localMove] = 'O'; moves++; }
            if (finishIfNeeded()) return;
            statusElement.textContent = '轮到你：选一个空格落 X。';
            paint();
          }
          for (let index = 0; index < 9; index++) {
            const button = document.createElement('button');
            button.type = 'button'; button.className = 'cell'; button.id = 'cell-' + index;
            button.addEventListener('click', function () { humanMove(index); });
            buttons.push(button); boardElement.appendChild(button);
          }
          retryButton.addEventListener('click', submitResult);
          document.getElementById('replay-guide').addEventListener('click', function () {
            document.getElementById('replay-note').hidden = false;
            scheduleFit();
          });
          window.addEventListener('resize', scheduleFit);
          if (window.visualViewport) window.visualViewport.addEventListener('resize', scheduleFit);
          if (typeof ResizeObserver === 'function') {
            const observer = new ResizeObserver(scheduleFit);
            observer.observe(document.getElementById('game-header'));
            observer.observe(document.getElementById('game-footer'));
          }
          paint(); scheduleFit();
        })();
        </script>
        </body>
        </html>
    """.trimIndent()
}
