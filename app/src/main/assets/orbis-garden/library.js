(function () {
  "use strict";

  const MODULE = "library";
  const VERSION = 1;
  const MAX_BOOK_BYTES = 32 * 1024 * 1024;
  const MAX_LIBRARY_BYTES = 64 * 1024 * 1024 - 8192;
  const MAX_BOOKS = 100;
  const encoder = new TextEncoder();
  const state = {
    revision: 0,
    data: emptyData(),
    route: { name: "shelf" },
    loaded: false,
    loading: false,
    saving: false,
    dirty: false,
    error: "",
    query: "",
    readerParagraph: 0,
    active: false,
    generation: 0,
  };
  let toastTimer = 0;
  let paragraphObserver = null;

  function emptyData() {
    return { version: VERSION, books: [] };
  }

  function isObject(value) {
    return value !== null && typeof value === "object" && !Array.isArray(value);
  }

  function requireValue(condition, code) {
    if (!condition) throw new Error(code);
  }

  function safeId(value) {
    requireValue(typeof value === "string" && /^[A-Za-z0-9_-]{1,80}$/.test(value), "书库数据中的标识无效");
    return value;
  }

  function finiteInteger(value, min, max, code) {
    requireValue(Number.isInteger(value) && value >= min && value <= max, code);
    return value;
  }

  function finiteNumber(value, min, max, code) {
    requireValue(Number.isFinite(value) && value >= min && value <= max, code);
    return value;
  }

  function normalizeData(raw) {
    if (isObject(raw) && Object.keys(raw).length === 0) return emptyData();
    requireValue(isObject(raw) && raw.version === VERSION && Array.isArray(raw.books), "书库数据版本不受支持");
    requireValue(encoder.encode(JSON.stringify(raw)).length <= MAX_LIBRARY_BYTES, "本机书库总量已达 64 MiB，请先导出备份并清理不再阅读的书；原书库未改动");
    requireValue(raw.books.length <= MAX_BOOKS, "书库中的书太多了");
    const ids = new Set();
    const books = raw.books.map((source) => {
      requireValue(isObject(source), "书籍数据无效");
      const id = safeId(source.id);
      requireValue(!ids.has(id), "书籍标识重复");
      ids.add(id);
      requireValue(typeof source.title === "string" && source.title.trim().length > 0 && source.title.length <= 200, "书名无效");
      requireValue(typeof source.text === "string" && encoder.encode(source.text).length <= MAX_BOOK_BYTES, "书籍正文过大或无效");
      requireValue(Array.isArray(source.chapters) && source.chapters.length >= 1 && source.chapters.length <= 5000, "章节数据无效");
      const chapters = source.chapters.map((chapter) => {
        requireValue(isObject(chapter) && typeof chapter.title === "string" && chapter.title.length <= 200, "章节标题无效");
        const start = finiteInteger(chapter.start, 0, source.text.length, "章节起点无效");
        const end = finiteInteger(chapter.end, start, source.text.length, "章节终点无效");
        return { title: chapter.title || "未命名章节", start, end };
      });
      for (let index = 1; index < chapters.length; index += 1) {
        requireValue(chapters[index - 1].end <= chapters[index].start, "章节范围重叠");
      }
      const progressSource = isObject(source.progress) ? source.progress : {};
      const progress = {
        chapter: finiteInteger(progressSource.chapter ?? 0, 0, chapters.length - 1, "阅读进度无效"),
        offset: finiteInteger(progressSource.offset ?? 0, 0, 1000000, "阅读进度无效"),
        updatedAt: finiteNumber(progressSource.updatedAt ?? 0, 0, Number.MAX_SAFE_INTEGER, "阅读时间无效"),
      };
      const fontSource = isObject(source.font) ? source.font : {};
      const font = {
        size: finiteNumber(fontSource.size ?? 18, 14, 30, "字号无效"),
        lineHeight: finiteNumber(fontSource.lineHeight ?? 1.9, 1.4, 2.5, "行距无效"),
      };
      const bookmarks = normalizeMarks(source.bookmarks, chapters.length, false);
      const notes = normalizeMarks(source.notes, chapters.length, true);
      return {
        id,
        title: source.title.trim(),
        text: source.text,
        chapters,
        progress,
        font,
        bookmarks,
        notes,
        importedAt: finiteNumber(source.importedAt ?? 0, 0, Number.MAX_SAFE_INTEGER, "导入时间无效"),
      };
    });
    return { version: VERSION, books };
  }

  function normalizeMarks(raw, chapterCount, withText) {
    if (raw === undefined) return [];
    requireValue(Array.isArray(raw) && raw.length <= (withText ? 2000 : 500), withText ? "笔记数据无效" : "书签数据无效");
    const ids = new Set();
    return raw.map((source) => {
      requireValue(isObject(source), withText ? "笔记数据无效" : "书签数据无效");
      const id = safeId(source.id);
      requireValue(!ids.has(id), withText ? "笔记标识重复" : "书签标识重复");
      ids.add(id);
      const mark = {
        id,
        chapter: finiteInteger(source.chapter, 0, chapterCount - 1, "章节位置无效"),
        offset: finiteInteger(source.offset, 0, 1000000, "段落位置无效"),
        createdAt: finiteNumber(source.createdAt, 0, Number.MAX_SAFE_INTEGER, "记录时间无效"),
      };
      if (withText) {
        requireValue(typeof source.text === "string" && source.text.trim().length > 0 && source.text.length <= 4000, "笔记正文无效");
        mark.text = source.text.trim();
        const author = source.author === undefined ? "human" : source.author;
        requireValue(author === "human" || author === "companion", "笔记作者无效");
        const fallbackName = author === "human" ? "我" : "伙伴";
        const authorName = source.authorName === undefined ? fallbackName : source.authorName;
        requireValue(typeof authorName === "string" && authorName.trim().length > 0 && authorName.length <= 32 &&
          !/[\r\n\u0000-\u001f\u007f]/.test(authorName), "笔记作者无效");
        mark.author = author;
        mark.authorName = authorName.trim();
      } else {
        requireValue(typeof source.label === "string" && source.label.length <= 240, "书签摘要无效");
        mark.label = source.label;
      }
      return mark;
    });
  }

  function parseResponse(value) {
    if (typeof value === "string") return JSON.parse(value);
    return value;
  }

  async function request(action, payload) {
    requireValue(typeof window.gardenRequest === "function", "本机书库桥接尚未准备好");
    return parseResponse(await window.gardenRequest(action, payload));
  }

  function validateSnapshot(raw, minimumRevision) {
    requireValue(isObject(raw), "本机书库返回格式无效");
    const revision = finiteInteger(raw.revision, minimumRevision, Number.MAX_SAFE_INTEGER, "本机书库版本无效");
    return { revision, data: normalizeData(raw.data) };
  }

  async function load() {
    if (state.loading) return;
    const generation = ++state.generation;
    state.loading = true;
    state.error = "";
    renderLoading();
    try {
      const snapshot = validateSnapshot(await request("extrasLoad", { module: MODULE }), 0);
      if (!state.active || generation !== state.generation) return;
      state.revision = snapshot.revision;
      state.data = snapshot.data;
      state.loaded = true;
      state.dirty = false;
      state.error = "";
      state.route = { name: "shelf" };
    } catch (_) {
      if (!state.active || generation !== state.generation) return;
      state.loaded = false;
      state.error = "书库暂时无法读取。原数据不会被覆盖。";
    } finally {
      if (generation === state.generation) {
        state.loading = false;
        if (state.active) render();
      }
    }
  }

  async function open() {
    if (state.active && state.loaded) {
      render();
      return;
    }
    state.active = true;
    await load();
  }

  async function persist(successMessage) {
    if (state.saving) {
      toast("正在保存上一处修改");
      return false;
    }
    const generation = state.generation;
    const expectedRevision = state.revision;
    const data = state.data;
    state.saving = true;
    state.error = "";
    render();
    try {
      const snapshot = validateSnapshot(await request("extrasSave", {
        module: MODULE,
        expectedRevision,
        data,
      }), expectedRevision + 1);
      if (!state.active || generation !== state.generation) return false;
      state.revision = snapshot.revision;
      state.data = snapshot.data;
      state.dirty = false;
      state.error = "";
      if (successMessage) toast(successMessage);
      return true;
    } catch (_) {
      if (!state.active || generation !== state.generation) return false;
      state.dirty = true;
      state.error = "保存失败，当前草稿仍留在本页。请重试；若另一窗口已经修改，请先自行核对再重新载入。";
      return false;
    } finally {
      if (generation === state.generation) {
        state.saving = false;
        if (state.active) render();
      }
    }
  }

  async function change(mutator, successMessage) {
    if (state.saving) {
      toast("正在保存，请稍候");
      return false;
    }
    try {
      const draft = normalizeData(JSON.parse(JSON.stringify(state.data)));
      mutator(draft);
      state.data = normalizeData(draft);
      state.dirty = true;
      state.error = "";
      render();
      return await persist(successMessage);
    } catch (_) {
      state.error = "这次修改无法应用，原书库没有被覆盖。";
      render();
      return false;
    }
  }

  function makeId(prefix) {
    const suffix = window.crypto && typeof window.crypto.randomUUID === "function"
      ? window.crypto.randomUUID().replace(/-/g, "")
      : `${Date.now().toString(36)}${Math.random().toString(36).slice(2)}`;
    return `${prefix}_${suffix}`.slice(0, 80);
  }

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function button(label, className, onClick, ariaLabel) {
    const node = element("button", className, label);
    node.type = "button";
    if (ariaLabel) node.setAttribute("aria-label", ariaLabel);
    node.addEventListener("click", onClick);
    return node;
  }

  function page(title, subtitle) {
    requireValue(typeof window.gardenPage === "function", "花园页面桥接尚未准备好");
    const main = window.gardenPage(title, subtitle);
    requireValue(main instanceof HTMLElement, "花园页面容器无效");
    main.replaceChildren();
    main.classList.add("reading-page");
    return main;
  }

  function header(title, subtitle, onBack, actions) {
    const head = element("header", "reading-header");
    head.append(button("←", "reading-head-btn", onBack, "返回"));
    const center = element("div", "reading-header__center");
    center.append(element("span", "reading-header__title", title));
    center.append(element("span", "reading-header__sub", subtitle));
    head.append(center);
    const actionBox = element("div", "reading-header__actions");
    (actions || []).forEach((item) => actionBox.append(item));
    head.append(actionBox);
    return head;
  }

  function renderPersistenceBanner(main) {
    if (!state.error && !state.dirty) return;
    const box = element("section", "reading-error");
    box.setAttribute("role", "status");
    box.append(element("div", "", state.error || "有一份尚未落盘的本页草稿。"));
    const actions = element("div", "reading-compose-actions");
    if (state.dirty) {
      actions.append(button("重试保存", "reading-small-btn reading-small-btn--primary", () => persist("已保存")));
      actions.append(button("舍弃草稿并重载", "reading-small-btn", async () => {
        if (!window.confirm("重新载入会舍弃本页尚未保存的草稿。确定继续吗？")) return;
        await load();
      }));
    } else {
      actions.append(button("重新读取", "reading-small-btn reading-small-btn--primary", load));
    }
    box.append(actions);
    main.append(box);
  }

  function renderLoading() {
    if (!state.active) return;
    try {
      const main = page("藏书阁", "读取本机书库");
      const loader = element("section", "reading-loader");
      const book = element("div", "reading-loader__book");
      book.append(element("span", "reading-loader__page"), element("span", "reading-loader__page"), element("span", "reading-loader__page"));
      loader.append(book, element("div", "", "正在翻开本机书架……"));
      main.append(loader);
    } catch (_) { /* Host may still be constructing the shell. */ }
  }

  function render() {
    if (!state.active) return;
    disconnectObserver();
    if (state.loading) return renderLoading();
    if (!state.loaded) return renderLoadError();
    if (state.route.name === "chapters") return renderChapters(state.route.bookId);
    if (state.route.name === "reader") return renderReader(state.route.bookId, state.route.chapter);
    if (state.route.name === "highlights") return renderHighlights(state.route.bookId || null);
    return renderShelf();
  }

  function renderLoadError() {
    const main = page("藏书阁", "本机书库");
    main.append(header("藏书阁", "本机书库", navigateHome, []));
    const error = element("section", "reading-error");
    error.append(element("span", "reading-empty__symbol", "✦"));
    error.append(element("div", "", state.error || "书库暂时无法读取。"));
    error.append(button("重新读取", "reading-retry-btn", load));
    main.append(error);
  }

  function navigateHome() {
    if (!requestLeave()) return;
    if (typeof window.gardenNavigateHome === "function") window.gardenNavigateHome();
  }

  function requestLeave() {
    if (!state.active) {
      removeLibraryOverlays();
      return true;
    }
    if (state.dirty && !window.confirm("还有未保存的本页草稿。离开会明确舍弃这份草稿，确定继续吗？")) return false;
    if (state.dirty) {
      state.dirty = false;
      state.loaded = false;
      state.error = "";
    }
    state.active = false;
    state.generation += 1;
    state.loading = false;
    state.saving = false;
    disconnectObserver();
    removeLibraryOverlays();
    return true;
  }

  function removeLibraryOverlays() {
    document.querySelectorAll('[data-library-overlay="true"]').forEach((node) => node.remove());
  }

  function isActive() {
    return state.active;
  }

  function renderShelf() {
    const main = page("藏书阁", "本机阅读 · 不自动上传");
    const markCount = state.data.books.reduce((sum, book) => sum + book.bookmarks.length + book.notes.length, 0);
    const marksButton = button(`摘记 ${markCount}`, `reading-cards-btn${markCount ? " reading-cards-btn--has-cards" : ""}`, () => {
      state.route = { name: "highlights" };
      render();
    });
    const importButton = button("导入", "reading-head-btn", importBook, "导入 TXT 或 Markdown 书籍");
    const backupButton = button("备份", "reading-head-btn", showBackupGuide, "导出或合并导入藏书阁备份");
    main.append(header("藏书阁", `本机 ${state.data.books.length} 本`, navigateHome, [marksButton, backupButton, importButton]));
    renderPersistenceBanner(main);

    const search = element("label", "reading-search");
    const input = element("input", "");
    input.type = "search";
    input.placeholder = "搜索书名或正文";
    input.value = state.query;
    input.setAttribute("aria-label", "搜索本机书库");
    const searchButton = button("搜索", "", () => {
      state.query = input.value.trim().slice(0, 100);
      render();
    });
    input.addEventListener("keydown", (event) => {
      if (event.key === "Enter") searchButton.click();
    });
    search.append(input, searchButton);
    main.append(search);

    const shell = element("section", "reading-shelf-shell");
    const intro = element("div", "reading-shelf-intro");
    const introText = element("div", "");
    introText.append(element("div", "reading-shelf-intro__eyebrow", "LOCAL LIBRARY"));
    introText.append(element("div", "reading-shelf-intro__text", "书、进度、书签和笔记都只保存在本机。"));
    intro.append(introText, element("div", "reading-status reading-status--live", state.saving ? "正在保存" : `本机版本 ${state.revision}`));
    shell.append(intro);

    const query = state.query.toLocaleLowerCase();
    const books = state.data.books.filter((book) => !query || book.title.toLocaleLowerCase().includes(query) || book.text.toLocaleLowerCase().includes(query));
    const grid = element("div", "reading-grid");
    if (!books.length) {
      const empty = element("div", "reading-empty");
      empty.append(element("span", "reading-empty__symbol", "⌁"));
      empty.append(element("div", "", state.data.books.length ? "没有找到匹配内容。" : "书架还是空的。导入一份 TXT 或 Markdown，第一本书才会出现。"));
      if (!state.data.books.length) empty.append(button("导入第一本书", "reading-retry-btn", importBook));
      grid.append(empty);
    } else {
      books.forEach((book, index) => grid.append(bookCard(book, index)));
    }
    shell.append(grid);

    const management = element("div", "reading-moment-section");
    management.append(element("h2", "reading-section-title", "存储说明"));
    management.append(element("div", "reading-moment-card", "藏书阁书籍、进度、书签和批注使用本页的专用备份；花园管理页的记录备份不包含这些书。藏书阁不会读取聊天、工作区、旧 VPS 或云端文件。"));
    management.append(button("导出或导入藏书阁", "reading-retry-btn", showBackupGuide));
    shell.append(management);
    main.append(shell);
  }

  function bookCard(book, index) {
    const card = button("", "reading-book-card", () => {
      state.route = { name: "chapters", bookId: book.id };
      render();
    }, `打开《${book.title}》`);
    const cover = element("div", "reading-book-cover");
    const palettes = [
      ["#c98a76", "#74526f"], ["#8cb4d6", "#505b85"], ["#8fa680", "#4d6b5c"], ["#d7a96f", "#805a4e"],
    ];
    const palette = palettes[index % palettes.length];
    cover.style.setProperty("--cover-a", palette[0]);
    cover.style.setProperty("--cover-b", palette[1]);
    cover.dataset.symbol = ["✦", "☾", "⌁", "❦"][index % 4];
    cover.append(element("span", "reading-book-cover__orbit"), element("span", "reading-book-cover__title", book.title));
    const meta = element("div", "reading-book-meta");
    meta.append(element("div", "reading-book-meta__title", book.title));
    const progress = element("div", "reading-progress");
    const track = element("div", "reading-progress__track");
    const fill = element("div", "reading-progress__fill");
    fill.style.width = `${Math.round(((book.progress.chapter + 1) / book.chapters.length) * 100)}%`;
    track.append(fill);
    progress.append(track);
    const line = element("div", "reading-book-meta__line");
    line.append(element("span", "", `${book.chapters.length} 章`), element("span", "", `读到 ${book.progress.chapter + 1}`));
    meta.append(progress, line);
    card.append(cover, meta);
    return card;
  }

  async function importBook() {
    if (state.saving) return toast("正在保存，请稍候");
    try {
      const result = parseResponse(await request("importBook", {}));
      if (result == null || result.cancelled === true) return toast("已取消导入");
      requireValue(isObject(result) && typeof result.title === "string" && typeof result.text === "string", "导入结果无效");
      const title = result.title.trim().slice(0, 200);
      requireValue(title.length > 0, "文件没有可用书名");
      requireValue(result.text.trim().length > 0, "文件内容为空");
      requireValue(encoder.encode(result.text).length <= MAX_BOOK_BYTES, "书籍超过 32 MiB");
      const chapters = splitChapters(result.text);
      const book = {
        id: makeId("book"),
        title,
        text: result.text,
        chapters,
        progress: { chapter: 0, offset: 0, updatedAt: 0 },
        font: { size: 18, lineHeight: 1.9 },
        bookmarks: [],
        notes: [],
        importedAt: Date.now(),
      };
      const saved = await change((draft) => {
        requireValue(draft.books.length < MAX_BOOKS, "书库已满");
        draft.books.unshift(book);
      }, "书籍已导入本机");
      if (saved) {
        state.route = { name: "chapters", bookId: book.id };
        render();
      }
    } catch (error) {
      state.error = error instanceof Error ? error.message : "导入失败，原书库没有变化。";
      render();
    }
  }

  function mergeLibraryData(currentRaw, incomingRaw) {
    const current = normalizeData(currentRaw);
    const incoming = normalizeData(incomingRaw);
    const byId = new Map(current.books.map((book) => [book.id, book]));
    const additions = [];
    let identical = 0;
    incoming.books.forEach((book) => {
      const existing = byId.get(book.id);
      if (!existing) {
        additions.push(book);
        byId.set(book.id, book);
        return;
      }
      requireValue(JSON.stringify(existing) === JSON.stringify(book),
        "备份中有与本机同标识但内容不同的书籍；为防覆盖，本次没有导入任何内容。");
      identical += 1;
    });
    const data = normalizeData({ version: VERSION, books: current.books.concat(additions) });
    return { data, added: additions.length, identical };
  }

  async function exportLibrary() {
    try {
      const result = parseResponse(await request("exportLibrary", { data: normalizeData(state.data) }));
      if (result && result.cancelled === true) return toast("已取消导出");
      toast("藏书阁备份已导出");
    } catch (_) {
      toast("导出没有完成，书库未发生变化");
    }
  }

  async function importLibraryBackup() {
    if (state.dirty) return toast("先重试保存或明确舍弃当前草稿，再导入备份");
    try {
      const result = parseResponse(await request("importLibrary", {}));
      if (result == null || result.cancelled === true) return toast("已取消导入");
      requireValue(isObject(result) && isObject(result.data), "藏书阁备份格式无效");
      const preview = mergeLibraryData(state.data, result.data);
      if (!preview.added) return toast(preview.identical ? "备份中的书已经都在本机" : "备份里没有书籍");
      if (!window.confirm(`备份将新增 ${preview.added} 本书；已有书籍不会被覆盖。确定合并吗？`)) return;
      await change((draft) => {
        const merged = mergeLibraryData(draft, result.data);
        requireValue(merged.added === preview.added, "书库在确认期间发生变化，请重新导入并核对");
        draft.books = merged.data.books;
      }, `已合并导入 ${preview.added} 本书`);
    } catch (error) {
      state.error = error instanceof Error ? error.message : "导入失败，原书库没有变化。";
      render();
    }
  }

  function showBackupGuide() {
    const overlay = element("div", "reading-cards-overlay reading-cards-overlay--open");
    overlay.dataset.libraryOverlay = "true";
    overlay.setAttribute("role", "dialog");
    overlay.setAttribute("aria-modal", "true");
    overlay.setAttribute("aria-label", "藏书阁备份");
    const sheet = element("section", "reading-cards-sheet");
    const head = element("div", "reading-cards-sheet__head");
    const title = element("div", "");
    title.append(element("span", "reading-cards-sheet__eyebrow", "LOCAL LIBRARY BACKUP"));
    title.append(element("span", "reading-cards-sheet__title", "藏书阁自己的备份"));
    const close = button("×", "reading-cards-close", () => overlay.remove(), "关闭");
    head.append(title, close);
    const body = element("div", "reading-cards-body");
    body.append(element("div", "reading-moment-card", "导出文件只包含本机藏书阁的书籍、阅读进度、书签和批注，不包含聊天、模型配置、凭据或旧 VPS 数据。"));
    body.append(element("div", "reading-moment-card", "导入只做合并：新书可加入；相同书籍会跳过；同一标识却内容不同会整次拒绝，绝不覆盖本机书籍。确认前不会写入。"));
    const actions = element("div", "reading-compose-actions");
    actions.append(button("导出备份", "reading-small-btn", async () => { overlay.remove(); await exportLibrary(); }));
    actions.append(button("合并导入", "reading-small-btn reading-small-btn--primary", async () => { overlay.remove(); await importLibraryBackup(); }));
    body.append(actions);
    sheet.append(head, body);
    overlay.append(sheet);
    overlay.addEventListener("click", (event) => { if (event.target === overlay) overlay.remove(); });
    document.body.append(overlay);
    close.focus();
  }

  function splitChapters(text) {
    const headings = [];
    const expression = /^(?:#{1,3}\s+(.+)|\s*(第[^\r\n]{1,30}[章回节卷](?:[^\r\n]{0,60})?))\s*$/gm;
    let match;
    while ((match = expression.exec(text)) !== null && headings.length < 4999) {
      headings.push({ index: match.index, bodyStart: expression.lastIndex, title: (match[1] || match[2] || "").trim().slice(0, 200) });
    }
    if (!headings.length) return [{ title: "全文", start: 0, end: text.length }];
    const chapters = [];
    if (text.slice(0, headings[0].index).trim()) chapters.push({ title: "开篇", start: 0, end: headings[0].index });
    headings.forEach((heading, index) => {
      chapters.push({
        title: heading.title || `第 ${index + 1} 章`,
        start: heading.bodyStart,
        end: index + 1 < headings.length ? headings[index + 1].index : text.length,
      });
    });
    return chapters.filter((chapter) => chapter.end >= chapter.start);
  }

  function findBook(bookId) {
    return state.data.books.find((book) => book.id === bookId) || null;
  }

  function renderChapters(bookId) {
    const book = findBook(bookId);
    if (!book) {
      state.route = { name: "shelf" };
      return renderShelf();
    }
    const main = page(book.title, "章节与本机进度");
    const deleteButton = button("删除", "reading-head-btn", () => deleteBook(book), `删除《${book.title}》`);
    const aiButton = button("共读", "reading-cards-btn", () => showAiGuide(book, book.progress.chapter));
    main.append(header(book.title, `${book.chapters.length} 章 · 本机书籍`, () => {
      state.route = { name: "shelf" };
      render();
    }, [aiButton, deleteButton]));
    renderPersistenceBanner(main);

    const list = element("section", "reading-chapter-list");
    book.chapters.forEach((chapter, index) => {
      const row = button("", "reading-chapter-item", () => openReader(book, index, index === book.progress.chapter ? book.progress.offset : 0), `阅读${chapter.title}`);
      row.append(element("span", `reading-chapter-dot${index <= book.progress.chapter ? " reading-chapter-dot--read" : ""}`));
      const body = element("span", "reading-chapter-main");
      body.append(element("span", "reading-chapter-index", `CHAPTER ${String(index + 1).padStart(2, "0")}`));
      body.append(element("span", "reading-chapter-title", chapter.title));
      row.append(body);
      if (index === book.progress.chapter) row.append(element("span", "reading-chapter-badge", "上次"));
      else row.append(element("span", "reading-chapter-badge", `${notesFor(book, index).length}`));
      list.append(row);
    });
    main.append(list);

    const bar = element("div", "reading-bottom-bar");
    bar.append(button("继续上次阅读", "reading-continue-btn", () => openReader(book, book.progress.chapter, book.progress.offset)));
    main.append(bar);
    main.classList.add("reading-page--with-bar");
  }

  async function deleteBook(book) {
    if (!window.confirm(`确定删除《${book.title}》吗？\n正文、进度、书签和本机笔记会一起删除，且不能撤销。`)) return;
    const saved = await change((draft) => {
      draft.books = draft.books.filter((item) => item.id !== book.id);
    }, "书籍已从本机删除");
    if (saved) {
      state.route = { name: "shelf" };
      render();
    }
  }

  function chapterParagraphs(book, chapterIndex) {
    const chapter = book.chapters[chapterIndex];
    const raw = book.text.slice(chapter.start, chapter.end).trim();
    if (!raw) return ["（这一章没有正文）"];
    const blocks = raw.split(/\r?\n\s*\r?\n+/).map((part) => part.trim()).filter(Boolean);
    return blocks.length ? blocks : [raw];
  }

  function openReader(book, chapter, offset) {
    if (state.saving) return toast("正在保存，请稍候");
    state.route = { name: "reader", bookId: book.id, chapter };
    state.readerParagraph = Math.max(0, offset || 0);
    book.progress = { chapter, offset: state.readerParagraph, updatedAt: Date.now() };
    state.dirty = true;
    render();
    persist("");
  }

  function renderReader(bookId, chapterIndex) {
    const book = findBook(bookId);
    if (!book || !Number.isInteger(chapterIndex) || chapterIndex < 0 || chapterIndex >= book.chapters.length) {
      state.route = { name: "shelf" };
      return renderShelf();
    }
    const chapter = book.chapters[chapterIndex];
    const paragraphs = chapterParagraphs(book, chapterIndex);
    state.readerParagraph = Math.min(state.readerParagraph, Math.max(0, paragraphs.length - 1));
    const main = page(chapter.title, book.title);
    const marksButton = button("摘记", "reading-cards-btn", () => {
      state.route = { name: "highlights", bookId: book.id };
      render();
    });
    const aiButton = button("AI 共读", "reading-cards-btn", () => showAiGuide(book, chapterIndex));
    main.append(header(chapter.title, book.title, () => {
      state.route = { name: "chapters", bookId: book.id };
      render();
    }, [marksButton, aiButton]));
    renderPersistenceBanner(main);

    const tools = element("div", "reading-reader-tools");
    tools.append(button("A−", "", () => adjustFont(book, -1), "减小字号"));
    tools.append(element("span", "", `${book.font.size}px · ${book.font.lineHeight.toFixed(1)}倍行距`));
    tools.append(button("A+", "", () => adjustFont(book, 1), "增大字号"));
    tools.append(button("↕", "", () => adjustLineHeight(book), "切换行距"));
    tools.append(button("☆", "", () => toggleBookmark(book, chapterIndex, paragraphs), "在当前位置切换书签"));
    tools.append(button("记住这里", "", () => rememberProgress(book, chapterIndex), "保存当前阅读位置"));
    main.append(tools);

    const reader = element("article", "reading-reader-card");
    reader.style.setProperty("--reading-font-size", `${book.font.size}px`);
    paragraphs.forEach((text, offset) => {
      const wrap = element("section", "reading-paragraph-wrap");
      wrap.dataset.paragraph = String(offset);
      const paragraph = element("p", notesAt(book, chapterIndex, offset).length ? "reading-paragraph reading-paragraph--annotated" : "reading-paragraph", text);
      paragraph.style.lineHeight = String(book.font.lineHeight);
      wrap.append(paragraph);
      wrap.append(button("✎", "reading-annotation-mark", () => addNote(book, chapterIndex, offset, text), `为第 ${offset + 1} 段添加笔记`));
      notesAt(book, chapterIndex, offset).forEach((note) => {
        const noteButton = button("", "reading-inline-note", () => removeNote(book, note));
        noteButton.append(element("span", "reading-inline-note__author",
          note.author === "companion" ? `${note.authorName} · AI 批注` : note.authorName));
        noteButton.append(element("span", "reading-inline-note__text", note.text));
        noteButton.append(element("span", "reading-inline-note__count", "删除"));
        wrap.append(noteButton);
      });
      reader.append(wrap);
    });
    main.append(reader);

    const nav = element("nav", "reading-reader-nav");
    const previous = button("上一章", "", () => openReader(book, chapterIndex - 1, 0));
    previous.disabled = chapterIndex === 0;
    const next = button(chapterIndex === book.chapters.length - 1 ? "已经读到末尾" : "下一章", "", () => openReader(book, chapterIndex + 1, 0));
    next.disabled = chapterIndex === book.chapters.length - 1;
    nav.append(previous, next);
    main.append(nav, element("div", "reading-finish-sentinel", "✦  ·  ✦"));
    observeParagraphs(reader);
    requestAnimationFrame(() => {
      const target = reader.querySelector(`[data-paragraph="${Math.min(book.progress.offset, paragraphs.length - 1)}"]`);
      if (target instanceof HTMLElement) target.scrollIntoView({ block: "center" });
    });
  }

  function observeParagraphs(reader) {
    if (!("IntersectionObserver" in window)) return;
    paragraphObserver = new IntersectionObserver((entries) => {
      const visible = entries.filter((entry) => entry.isIntersecting).sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0];
      if (visible && visible.target instanceof HTMLElement) {
        state.readerParagraph = Number.parseInt(visible.target.dataset.paragraph || "0", 10) || 0;
      }
    }, { root: null, threshold: [0.25, 0.6] });
    reader.querySelectorAll("[data-paragraph]").forEach((node) => paragraphObserver.observe(node));
  }

  function disconnectObserver() {
    if (paragraphObserver) paragraphObserver.disconnect();
    paragraphObserver = null;
  }

  function adjustFont(book, delta) {
    const size = Math.max(14, Math.min(30, book.font.size + delta));
    if (size === book.font.size) return;
    change((draft) => { findDraftBook(draft, book.id).font.size = size; }, "字号已保存");
  }

  function adjustLineHeight(book) {
    const options = [1.6, 1.9, 2.2];
    const current = options.findIndex((value) => Math.abs(value - book.font.lineHeight) < 0.05);
    const next = options[(current + 1 + options.length) % options.length];
    change((draft) => { findDraftBook(draft, book.id).font.lineHeight = next; }, "行距已保存");
  }

  function rememberProgress(book, chapter) {
    change((draft) => {
      findDraftBook(draft, book.id).progress = { chapter, offset: state.readerParagraph, updatedAt: Date.now() };
    }, "阅读位置已记住");
  }

  function toggleBookmark(book, chapter, paragraphs) {
    const offset = state.readerParagraph;
    const existing = book.bookmarks.find((mark) => mark.chapter === chapter && mark.offset === offset);
    change((draft) => {
      const target = findDraftBook(draft, book.id);
      if (existing) target.bookmarks = target.bookmarks.filter((mark) => mark.id !== existing.id);
      else target.bookmarks.push({
        id: makeId("mark"), chapter, offset,
        label: (paragraphs[offset] || book.chapters[chapter].title).slice(0, 240),
        createdAt: Date.now(),
      });
    }, existing ? "书签已移除" : "书签已保存");
  }

  function addNote(book, chapter, offset, passage) {
    const value = window.prompt(`为这一段写本机笔记：\n${passage.slice(0, 120)}`, "");
    if (value == null) return;
    const text = value.trim();
    if (!text) return toast("空笔记没有保存");
    if (text.length > 4000) return toast("笔记最多 4000 字");
    change((draft) => {
      findDraftBook(draft, book.id).notes.push({
        id: makeId("note"), chapter, offset, text, createdAt: Date.now(), author: "human", authorName: "我",
      });
    }, "笔记已保存在本机");
  }

  function removeNote(book, note) {
    if (!window.confirm("删除这条本机笔记吗？")) return;
    change((draft) => {
      const target = findDraftBook(draft, book.id);
      target.notes = target.notes.filter((item) => item.id !== note.id);
    }, "笔记已删除");
  }

  function findDraftBook(draft, bookId) {
    const book = draft.books.find((item) => item.id === bookId);
    requireValue(book, "书籍已经不存在");
    return book;
  }

  function notesAt(book, chapter, offset) {
    return book.notes.filter((note) => note.chapter === chapter && note.offset === offset);
  }

  function notesFor(book, chapter) {
    return book.notes.filter((note) => note.chapter === chapter);
  }

  function renderHighlights(bookId) {
    const selected = bookId ? findBook(bookId) : null;
    const books = selected ? [selected] : state.data.books;
    const main = page("摘记", selected ? selected.title : "全部本机书签与笔记");
    main.append(header("摘记", selected ? selected.title : "全部书籍", () => {
      state.route = selected ? { name: "reader", bookId: selected.id, chapter: selected.progress.chapter } : { name: "shelf" };
      render();
    }, []));
    renderPersistenceBanner(main);
    const summary = element("p", "reading-cards-summary", "这里仅显示本机书签和笔记；没有任何内容会自动发给 AI。点击卡片可回到原章节。" );
    main.append(summary);
    const list = element("section", "reading-card-list");
    let count = 0;
    books.forEach((book) => {
      book.bookmarks.forEach((mark) => {
        count += 1;
        list.append(highlightCard(book, mark.chapter, mark.offset, "书签", mark.label));
      });
      book.notes.forEach((note) => {
        count += 1;
        list.append(highlightCard(book, note.chapter, note.offset,
          note.author === "companion" ? `${note.authorName} · AI 批注` : `${note.authorName}的笔记`, note.text));
      });
    });
    if (!count) list.append(element("div", "reading-cards-empty", "还没有书签或笔记。阅读时点亮书签，或在段落旁写下想法。"));
    main.append(list);
  }

  function highlightCard(book, chapter, offset, type, text) {
    const card = button("", "reading-trace-card", () => openReader(book, chapter, offset));
    card.append(element("div", "reading-trace-card__keep", type));
    card.append(element("div", "reading-trace-card__title", book.title));
    card.append(element("div", "reading-trace-card__sub", book.chapters[chapter]?.title || "章节"));
    card.append(element("div", "reading-trace-card__quote", text));
    card.append(element("div", "reading-trace-card__foot", `第 ${chapter + 1} 章 · 第 ${offset + 1} 段`));
    return card;
  }

  function currentReadingContext() {
    if (state.route.name !== "reader") return null;
    const book = findBook(state.route.bookId);
    if (!book) return null;
    const chapterIndex = state.route.chapter;
    const paragraphs = chapterParagraphs(book, chapterIndex);
    const paragraphIndex = Math.max(0, Math.min(state.readerParagraph, paragraphs.length - 1));
    return {
      bookId: book.id,
      title: book.title,
      chapterIndex,
      chapterTitle: book.chapters[chapterIndex].title,
      paragraphIndex,
      excerpt: paragraphs[paragraphIndex].slice(0, 2000),
    };
  }

  function showAiGuide(book, chapter) {
    const context = state.route.name === "reader" ? currentReadingContext() : {
      bookId: book.id,
      title: book.title,
      chapterIndex: chapter,
      chapterTitle: book.chapters[chapter]?.title || "章节",
      paragraphIndex: 0,
      excerpt: "",
    };
    const overlay = element("div", "reading-cards-overlay reading-cards-overlay--open");
    overlay.dataset.libraryOverlay = "true";
    overlay.setAttribute("role", "dialog");
    overlay.setAttribute("aria-modal", "true");
    overlay.setAttribute("aria-label", "AI 共读说明");
    const sheet = element("section", "reading-cards-sheet");
    const head = element("div", "reading-cards-sheet__head");
    const title = element("div", "");
    title.append(element("span", "reading-cards-sheet__eyebrow", "AI CO-READING"));
    title.append(element("span", "reading-cards-sheet__title", "带着这一页去聊天"));
    const close = button("×", "reading-cards-close", () => overlay.remove(), "关闭");
    head.append(title, close);
    const body = element("div", "reading-cards-body");
    body.append(element("div", "reading-moment-card", "藏书阁与共读记录都保存在本机，不连接旧 VPS。导入书籍不会自动发送给模型；本机书目和章节读取工具无需云端授权即可使用，空书库返回空。当助手调用读取工具时，指定章节片段会作为工具结果发送给当前选择的模型供应商，单次最多 32 KiB。写批注仍需开启相应工具并确认授权。"));
    body.append(element("div", "reading-moment-card", "AI 批注会用伙伴名称清楚署名，写入按宿主当前授权策略批准，并用书库版本校验防止覆盖并发修改；它不会冒充你的笔记，也不会改动你自己的阅读进度。这里不会假装已经把内容送进聊天。"));
    body.append(element("div", "reading-moment-card", `准备位置：《${context.title}》· ${context.chapterTitle}${context.paragraphIndex ? ` · 第 ${context.paragraphIndex + 1} 段` : ""}`));
    const actions = element("div", "reading-compose-actions");
    actions.append(button("留在书里", "reading-small-btn", () => overlay.remove()));
    actions.append(button("返回聊天", "reading-small-btn reading-small-btn--primary", () => {
      if (!requestLeave()) return;
      overlay.remove();
      if (typeof window.gardenRequest === "function") window.gardenRequest("chat").catch(() => {});
    }));
    body.append(actions);
    sheet.append(head, body);
    overlay.append(sheet);
    overlay.addEventListener("click", (event) => { if (event.target === overlay) overlay.remove(); });
    document.body.append(overlay);
    close.focus();
  }

  function toast(message) {
    let node = document.querySelector(".reading-toast[data-library-toast]");
    if (!(node instanceof HTMLElement)) {
      node = element("div", "reading-toast");
      node.dataset.libraryToast = "true";
      node.setAttribute("role", "status");
      document.body.append(node);
    }
    node.textContent = String(message);
    node.classList.add("reading-toast--show");
    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(() => node.classList.remove("reading-toast--show"), 2400);
  }

  const libraryApi = {
    open,
    currentReadingContext,
    requestLeave,
    isActive,
  };
  if (window.__ORBIS_LIBRARY_TEST__ === true) libraryApi.test = Object.freeze({ normalizeData, mergeLibraryData });
  window.GardenLibrary = Object.freeze(libraryApi);
})();
