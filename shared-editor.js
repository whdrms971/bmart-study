/*
 * B마트 공통 연결 대시보드 - 공동 편집 추가 기능
 * 기존 58개 규칙과 30개 정책의 원문은 읽기 전용 근거로 유지합니다.
 * 이 파일은 페이지 제목·목차·추가 본문·정책 결정·담당 작업을 DB에서 관리합니다.
 */
(() => {
  'use strict';
  const config = window.BMART_SUPABASE || {};
  const configured = !!(config.url && config.anonKey && window.supabase?.createClient);
  const client = configured ? window.supabase.createClient(config.url, config.anonKey) : null;
  const coreIDs = ['overview', 'matrix', 'rules', 'policies', 'handoff'];
  const root = document.querySelector('.top-actions');
  const nav = document.querySelector('#nav');
  const viewsParent = document.querySelector('.main');
  const originalGo = go;
  const originalRenderPolicyEditor = renderPolicyEditor;
  const fallback = coreIDs.map((id, i) => ({
    id, sort_order: i * 10, title: document.querySelector(`#nav [data-tab="${id}"]`)?.innerText?.trim() || id,
    description: pageTitles[id][1], body: '', is_core: true, is_visible: true, version: 1
  }));
  const store = { member: null, user: null, pages: [...fallback], tasks: [], policyVersions: {}, channel: null, syncing: false, refreshJob: null };
  let editorDialog = null;
  let status = null;
  let editButton = null;
  let loginButton = null;

  function safe(value) { return escapeHTML(value == null ? '' : String(value)); }
  function canEdit() { return !!(configured && store.user && store.member?.active && ['admin','editor'].includes(store.member.role)); }
  function showError(error) { toast(error?.message || String(error || '처리 중 오류가 발생했습니다.')); }
  function updateStatus(message, kind = '') { status.textContent = message; status.className = 'team-status ' + kind; }
  function make(tag, props = {}) { const node = document.createElement(tag); Object.assign(node, props); return node; }

  const toolbar = make('div', { className: 'team-bar' });
  status = make('span', { className: 'team-status' });
  loginButton = make('button', { className: 'btn', type: 'button', textContent: '팀원 로그인' });
  editButton = make('button', { className: 'btn primary', type: 'button', textContent: '목차·본문 편집' });
  toolbar.append(status, loginButton, editButton);
  root.append(toolbar);
  editButton.hidden = true;
  loginButton.addEventListener('click', () => store.user ? logout() : loginDialog());
  editButton.addEventListener('click', () => pageListDialog());

  const warning = make('div', { className: 'team-message' });
  warning.hidden = true;
  viewsParent.querySelector('.topline').after(warning);
  if (!configured) {
    updateStatus('공동 저장 미연결', 'warn');
    warning.textContent = '현재는 기존 HTML 보기 및 개인 브라우저 정책 기록만 가능합니다. 팀 공동 편집을 사용하려면 Supabase 설정이 필요합니다.';
    warning.hidden = false;
    loginButton.hidden = true;
  } else updateStatus('팀원 로그인 후 공동 편집', 'warn');

  function shellDialog(heading, subtext, body, buttons = []) {
    if (editorDialog) editorDialog.remove();
    const modal = make('div', { className: 'team-modal' });
    const dialog = make('div', { className: 'team-dialog', role: 'dialog', ariaModal: 'true', tabIndex: -1 });
    const head = make('h2', { textContent: heading });
    const sub = make('p', { textContent: subtext });
    const inside = make('div');
    if (typeof body === 'string') inside.innerHTML = body;
    else inside.append(body);
    const actions = make('div', { className: 'tools' });
    const dismiss = make('button', { className: 'btn', textContent: '닫기', type: 'button' });
    dismiss.onclick = () => modal.remove();
    actions.append(dismiss);
    for (const b of buttons) {
      const el = make('button', { className: b.danger ? 'btn danger' : 'btn primary', type: 'button', textContent: b.label });
      el.onclick = async () => {
        el.disabled = true;
        try { await b.run(modal, inside); }
        catch (err) { showError(err); }
        finally { el.disabled = false; }
      };
      actions.append(el);
    }
    dialog.append(head, sub, inside, actions);
    modal.append(dialog);
    modal.addEventListener('click', e => { if (e.target === modal) modal.remove(); });
    document.body.append(modal);
    editorDialog = modal;
    dialog.focus();
    const esc = e => { if (e.key === 'Escape') { modal.remove(); window.removeEventListener('keydown', esc); } };
    window.addEventListener('keydown', esc);
    return modal;
  }

  function loginDialog() {
    const box = make('div', { className: 'team-signin-fields' });
    box.innerHTML = `<label class="field">이메일<input id="team-email" type="email" autocomplete="username" placeholder="초대받은 팀원 이메일"></label>
      <label class="field">비밀번호<input id="team-password" type="password" autocomplete="current-password"></label>`;
    const modal = shellDialog('팀원 로그인', 'Supabase에 등록된 팀원 계정만 공동 데이터를 편집할 수 있습니다.', box, [{ label: '로그인', run: async () => {
      const email = box.querySelector('#team-email').value.trim();
      const password = box.querySelector('#team-password').value;
      if (!email || !password) throw new Error('이메일과 비밀번호를 입력하세요.');
      const { data, error } = await client.auth.signInWithPassword({ email, password });
      if (error) throw error;
      modal.remove();
      await sessionChanged(data.user);
    }}]);
  }
  async function logout() {
    if (!client) return;
    const { error } = await client.auth.signOut();
    if (error) return showError(error);
    await sessionChanged(null);
  }

  function ensureView(page) {
    let section = document.getElementById(page.id);
    if (!section) {
      section = make('section', { id: page.id, className: 'view team-custom-view' });
      section.innerHTML = `<div class="section-title"><div><h2></h2><p></p></div></div><div class="panel team-custom-body"></div>`;
      viewsParent.insertBefore(section, viewsParent.querySelector('.footer'));
    }
    if (!page.is_core) {
      section.querySelector('.section-title h2').textContent = page.title;
      section.querySelector('.section-title p').textContent = page.description || '';
    }
    if (page.id === 'team-work') renderTasks();
    let note = section.querySelector(':scope > .team-note');
    if (page.body?.trim()) {
      if (!note) {
        note = make('div', { className: 'team-note' });
        section.querySelector('.section-title')?.after(note) || section.prepend(note);
      }
      note.textContent = '';
      const title = make('h3', { textContent: '팀 공통 편집 내용' });
      const body = make('div', { className: 'team-note-body', textContent: page.body });
      note.append(title, body);
    } else if (note) note.remove();
    if (!page.is_core && page.id !== 'team-work') {
      const content = section.querySelector('.team-custom-body');
      content.textContent = page.body?.trim() ? '' : '아직 작성된 본문이 없습니다. 편집 권한이 있는 팀원은 「목차·본문 편집」에서 내용을 추가할 수 있습니다.';
      if (page.body?.trim()) content.hidden = true;
      else content.hidden = false;
    }
  }

  function drawPages() {
    // 원본 58개 규칙·30개 정책은 건드리지 않고 동적 목차만 재구성합니다.
    const visible = [...store.pages].filter(p => p.is_visible).sort((a,b) => a.sort_order - b.sort_order || a.id.localeCompare(b.id));
    nav.textContent = '';
    for (const p of visible) {
      if (!/^[a-z][a-z0-9-]{1,60}$/.test(p.id)) continue;
      ensureView(p);
      const button = make('button', { type: 'button', className: state.tab === p.id ? 'active' : '' });
      button.dataset.tab = p.id;
      const mark = make('span', { className: 'nav-icon', textContent: p.is_core ? ({overview:'◈',matrix:'▦',rules:'☷',policies:'✓',handoff:'↗'}[p.id] || '☷') : '▤' });
      const text = make('span', { className: 'nav-label', textContent: p.title });
      button.append(mark, text);
      button.onclick = () => go(p.id);
      nav.append(button);
      pageTitles[p.id] = [p.title, p.description || '팀원들이 공동으로 관리하는 업무 페이지입니다.'];
    }
    document.querySelectorAll('.team-custom-view').forEach(el => {
      if (!visible.some(p => p.id === el.id)) el.remove();
    });
    if (!visible.some(p => p.id === state.tab)) go(visible[0]?.id || 'overview');
    else {
      document.getElementById('pageTitle').textContent = pageTitles[state.tab]?.[0] || '';
      document.getElementById('pageDesc').textContent = pageTitles[state.tab]?.[1] || '';
    }
  }

  // 기존 go()를 호출하는 검색·매트릭스 링크도 동적으로 만든 목차와 함께 작동합니다.
  go = function(teamPage) {
    const target = store.pages.find(p => p.id === teamPage && p.is_visible);
    if (!target) return;
    if (!document.getElementById(teamPage)) ensureView(target);
    if (teamPage === 'team-work') renderTasks();
    originalGo(teamPage);
  };

  function field(label, key, val, kind = 'input') {
    const wrap = make('label', { className: 'field' });
    wrap.textContent = label;
    const input = make(kind === 'textarea' ? 'textarea' : 'input', { value: val || '' });
    input.dataset.field = key;
    if (kind === 'textarea') input.rows = 7;
    if (key === 'title') input.maxLength = 90;
    if (key === 'description') input.maxLength = 400;
    wrap.append(input);
    return wrap;
  }

  function pageListDialog() {
    if (!canEdit()) return toast('편집 권한이 필요합니다.');
    const box = make('div', { className: 'team-editor-list' });
    const list = [...store.pages].sort((a,b) => a.sort_order - b.sort_order);
    list.forEach((p,i) => {
      const row = make('div', { className: 'team-editor-row' });
      const heading = make('strong', { textContent: `${p.title}${p.is_visible ? '' : ' (숨김)'}` });
      const edit = make('button', { className: 'btn', textContent: '편집' });
      const up = make('button', { className: 'btn', textContent: '↑', disabled: i === 0 });
      const down = make('button', { className: 'btn', textContent: '↓', disabled: i === list.length - 1 });
      edit.onclick = () => pageEditDialog(p);
      up.onclick = () => reorder(p, list[i-1]);
      down.onclick = () => reorder(p, list[i+1]);
      row.append(heading, up, down, edit);
      box.append(row);
    });
    shellDialog('목차·본문 편집', '제목 변경·본문 추가·순서 변경은 저장 후 팀원 모두에게 반영됩니다.', box,
      [{ label: '새 목차 추가', run: async () => pageEditDialog(null) }]);
  }

  function pageEditDialog(page) {
    if (!canEdit()) return;
    const isNew = !page;
    const container = make('div');
    container.append(field('목차 제목', 'title', page?.title || ''), field('메뉴 설명', 'description', page?.description || ''), field('추가 본문 (원본 분석 문서는 유지됩니다)', 'body', page?.body || '', 'textarea'));
    if (page?.id !== 'overview') {
      const show = make('label', { className: 'field' });
      show.innerHTML = '<span>목차 표시</span>';
      const checkbox = make('input', { type: 'checkbox', checked: page ? !!page.is_visible : true });
      checkbox.dataset.field = 'is_visible';
      show.append(checkbox);
      container.append(show);
    }
    const buttons = [{ label: isNew ? '목차 추가' : '변경 내용 저장', run: async modal => {
      const values = Object.fromEntries([...container.querySelectorAll('[data-field]')].map(el => [el.dataset.field, el.type === 'checkbox' ? el.checked : el.value.trim()]));
      if (!values.title) throw new Error('목차 제목을 입력하세요.');
      const payload = { title: values.title, description: values.description, body: values.body, is_visible: values.is_visible ?? true };
      if (isNew) {
        const id = 'page-' + crypto.randomUUID();
        const max = Math.max(...store.pages.map(x=>x.sort_order), 0);
        const {error} = await client.from('dashboard_pages').insert({ ...payload, id, sort_order: max+10, is_core:false, version:1 });
        if (error) throw error;
      } else await updateVersioned('dashboard_pages','id',page.id,page.version,payload);
      modal.remove(); await refreshAll(); toast(isNew ? '새 목차가 공유 저장되었습니다.' : '목차·본문이 공유 저장되었습니다.');
    }}];
    if (page && !page.is_core) buttons.unshift({ label:'이 목차 삭제', danger:true, run:async modal => {
      if (!confirm(`「${page.title}」 목차를 삭제하시겠습니까?`)) return;
      const {data,error} = await client.from('dashboard_pages').delete().eq('id',page.id).eq('version',page.version).select('id').maybeSingle();
      if (error) throw error;
      if (!data) return versionConflict();
      modal.remove(); await refreshAll(); toast('목차를 삭제했습니다.');
    }});
    shellDialog(isNew ? '새 목차 추가' : `목차 편집 · ${page.title}`, '기존 공통 규칙 58개와 정책 30건의 원문은 별도로 유지됩니다.',container,buttons);
  }

  async function reorder(a,b) {
    if (!canEdit() || !a || !b) return;
    try {
      // 각 행에 버전 조건을 적용합니다. 오류 시 다시 읽어 화면 상태를 복원합니다.
      await updateVersioned('dashboard_pages','id',a.id,a.version,{sort_order:b.sort_order});
      await updateVersioned('dashboard_pages','id',b.id,b.version,{sort_order:a.sort_order});
      await refreshAll(); pageListDialog(); toast('목차 순서를 저장했습니다.');
    } catch (e) { await refreshAll(); showError(e); pageListDialog(); }
  }
  function versionConflict() { throw new Error('다른 팀원이 먼저 수정했습니다. 최신 내용을 불러온 뒤 다시 수정해 주세요.'); }
  async function updateVersioned(table, idField, id, version, payload) {
    if (!canEdit()) throw new Error('편집 권한이 없습니다.');
    const { data,error } = await client.from(table).update({ ...payload, version:version+1 }).eq(idField,id).eq('version',version).select('*').maybeSingle();
    if (error) throw error;
    if (!data) versionConflict();
    return data;
  }

  function renderTasks() {
    const host = document.getElementById('team-work')?.querySelector('.team-custom-body');
    if (!host) return;
    host.hidden = false;
    const done = store.tasks.filter(t => t.status === '완료').length;
    const total = store.tasks.length;
    const percent = total ? Math.round(done / total * 100) : 0;
    host.innerHTML = `<div class="section-title"><div><h2>6개 설계·검증 작업</h2><p>실제 구현과 검증 완료는 구분해 기록합니다.</p></div><span class="team-tag">${done}/${total} 완료 · ${percent}%</span></div>
      <div class="team-progress"><div class="team-progress-fill" style="width:${percent}%"></div></div><div id="team-task-actions" class="team-actions"></div><div class="team-tasks" id="team-tasks-list"></div>`;
    const action = host.querySelector('#team-task-actions');
    if (canEdit()) {
      const add = make('button', { className:'btn primary',textContent:'새 작업 추가' });
      add.onclick=()=>taskDialog(null);action.append(add);
    }
    const list = host.querySelector('#team-tasks-list');
    if (!total) { list.innerHTML='<div class="team-empty">공유 작업이 아직 없습니다. 데이터베이스 초기화 여부를 확인하세요.</div>'; return; }
    for (const t of store.tasks) {
      const row = make('div', { className:'team-task-row' });
      row.innerHTML = `<div><div class="team-task-title">${safe(t.title)}</div><div class="team-task-meta">${safe(t.owner||'담당자 미정')} · ${safe(t.status)} · ${safe(t.priority||'P1')}</div></div>`;
      const actions = make('div', { className:'team-actions' });
      if (canEdit()) {
        const edit = make('button', {className:'btn',textContent:'수정'});edit.onclick=()=>taskDialog(t);
        const sel = make('select');
        ['미착수','진행 중','검토 중','완료','보류'].forEach(s=>sel.add(new Option(s,s)));
        sel.value=t.status;
        sel.onchange=async()=>{
          sel.disabled=true;
          try { await updateVersioned('dashboard_tasks','id',t.id,t.version,{status:sel.value});await refreshAll();toast('작업 상태를 공유 저장했습니다.'); }
          catch(e){showError(e);await refreshAll();}
        };
        actions.append(sel,edit);
      }
      row.append(actions);list.append(row);
    }
  }
  function taskDialog(task) {
    if(!canEdit())return;
    const container=make('div');
    container.append(field('작업 제목','title',task?.title||''),field('담당자','owner',task?.owner||''));
    const selectWrap=make('label',{className:'field',textContent:'진행 상태'});
    const sel=make('select');sel.dataset.field='status';
    ['미착수','진행 중','검토 중','완료','보류'].forEach(s=>sel.add(new Option(s,s)));
    sel.value=task?.status||'미착수';selectWrap.append(sel);container.append(selectWrap);
    const buttons=[{label:'저장',run:async modal=>{
      const title=container.querySelector('[data-field="title"]').value.trim();
      const owner=container.querySelector('[data-field="owner"]').value.trim();
      const status=sel.value;
      if(!title)throw Error('작업 제목을 입력하세요.');
      if(task) await updateVersioned('dashboard_tasks','id',task.id,task.version,{title,owner,status});
      else { const {error}=await client.from('dashboard_tasks').insert({title,owner,status,sort_order:store.tasks.length*10+10});if(error)throw error; }
      modal.remove();await refreshAll();toast('작업 내용이 공유 저장되었습니다.');
    }}];
    if(task)buttons.unshift({label:'작업 삭제',danger:true,run:async modal=>{
      if(!confirm('이 작업을 삭제하시겠습니까?'))return;
      const {data,error}=await client.from('dashboard_tasks').delete().eq('id',task.id).eq('version',task.version).select('id').maybeSingle();
      if(error)throw error;if(!data)versionConflict();modal.remove();await refreshAll();
    }});
    shellDialog(task?'작업 수정':'작업 추가','완료된 작업만 진행률에 반영됩니다.',container,buttons);
  }

  // 기존 정책 편집기의 저장 버튼을 공유 저장 기능으로 교체합니다.
  renderPolicyEditor = function() {
    originalRenderPolicyEditor();
    const btn=document.getElementById('saveDecision');
    const clear=document.getElementById('clearDecision');
    if(!configured) return; // DB 미설정 시 원래 로컬 편집 기능만 제공합니다.
    const help=document.createElement('p');help.className='policy-note';
    help.textContent=canEdit()?'수정 내용은 팀 공용 DB에 저장되며 다른 팀원에게 반영됩니다.':'팀원 로그인 및 편집 권한이 있어야 정책을 공동 수정할 수 있습니다.';
    document.getElementById('policyEditor').append(help);
    // 원본 이벤트가 먼저 로컬 저장을 수행하지 않도록 버튼을 교체합니다.
    const saveCopy=btn.cloneNode(true);btn.replaceWith(saveCopy);
    const clearCopy=clear.cloneNode(true);clear.replaceWith(clearCopy);
    if(!canEdit()) {saveCopy.disabled=true;clearCopy.disabled=true;return;}
    saveCopy.onclick=async()=>{
      saveCopy.disabled=true;
      const id=state.policy,version=store.policyVersions[id];
      try {
        if(!version)throw new Error('정책 동기화가 필요합니다. 새로고침 후 다시 시도하세요.');
        const next={decision_state:document.getElementById('editState').value, owner:document.getElementById('editOwner').value.trim(),
          deadline:document.getElementById('editDeadline').value||null, note:document.getElementById('editNote').value.trim()};
        await updateVersioned('dashboard_policies','policy_id',id,version,next);
        await refreshAll();toast(id+' 정책을 공유 저장했습니다.');
      }catch(err){showError(err);await refreshAll();}finally{saveCopy.disabled=false;}
    };
    clearCopy.onclick=async()=>{
      const id=state.policy;
      if(!confirm(id+'의 공용 정책 기록을 초기화하시겠습니까?'))return;
      try{await updateVersioned('dashboard_policies','policy_id',id,store.policyVersions[id],{decision_state:'미정',owner:'',deadline:null,note:''});await refreshAll();toast('공유 기록이 초기화되었습니다.');}
      catch(err){showError(err);await refreshAll();}
    };
  };

  async function refreshAll() {
    if(!configured || !store.member?.active || store.syncing) return;
    store.syncing=true;
    try {
      const [pages, policies, tasks] = await Promise.all([
        client.from('dashboard_pages').select('*').order('sort_order'),
        client.from('dashboard_policies').select('*'),
        client.from('dashboard_tasks').select('*').order('sort_order')
      ]);
      for(const r of [pages,policies,tasks]) if(r.error) throw r.error;
      store.pages=pages.data.length?pages.data:[...fallback];
      store.tasks=tasks.data||[];
      saved={};store.policyVersions={};
      for(const p of policies.data||[]) {
        saved[p.policy_id]={state:p.decision_state,owner:p.owner||'',deadline:p.deadline||'',note:p.note||''};
        store.policyVersions[p.policy_id]=p.version;
      }
      drawPages();renderPolicies();renderTasks();
      updateStatus(`${store.member.display_name||store.user.email} · 공동 저장 연결`, 'live');
    } catch(e) { updateStatus('동기화 오류 · 설정 확인', 'warn'); showError(e); }
    finally {store.syncing=false;}
  }
  function subscribeChanges() {
    if(store.channel)client.removeChannel(store.channel);
    if(!store.member?.active)return;
    store.channel=client.channel('bmart-dashboard-shared-v1')
      .on('postgres_changes',{event:'*',schema:'public',table:'dashboard_pages'}, queueRefresh)
      .on('postgres_changes',{event:'*',schema:'public',table:'dashboard_policies'}, queueRefresh)
      .on('postgres_changes',{event:'*',schema:'public',table:'dashboard_tasks'}, queueRefresh)
      .subscribe();
  }
  function queueRefresh() {
    // 동일한 순간의 여러 변경 알림을 하나의 조회로 합칩니다.
    clearTimeout(store.refreshJob);
    store.refreshJob=setTimeout(async()=>{if(!document.querySelector('.team-modal'))await refreshAll();},400);
  }
  async function sessionChanged(user) {
    store.user=user;
    if(store.channel){client.removeChannel(store.channel);store.channel=null;}
    store.member=null;store.tasks=[];store.pages=[...fallback];
    editButton.hidden=true;
    if(!user) {
      loginButton.textContent='팀원 로그인';updateStatus('팀원 로그인 후 공동 편집','warn');
      // 로그아웃 시 공동 데이터를 브라우저에 남기지 않습니다.
      saved={};store.policyVersions={};drawPages();renderPolicies();
      return;
    }
    const {data,error}=await client.from('dashboard_members').select('role,active,display_name').eq('user_id',user.id).maybeSingle();
    if(error){showError(error);updateStatus('팀원 권한 조회 오류','warn');return;}
    if(!data?.active){updateStatus('접근 권한 없음 · 관리자에게 문의','warn');warning.hidden=false;
      warning.textContent='로그인 계정이 팀원 목록에 등록되지 않았습니다. 관리자가 dashboard_members에 사용자 ID를 등록해야 합니다.';
      loginButton.textContent='로그아웃';return;}
    warning.hidden=true;
    store.member=data;
    loginButton.textContent='로그아웃';
    editButton.hidden=!canEdit();
    await refreshAll();subscribeChanges();
  }
  if(configured) {
    client.auth.getSession().then(({data,error})=>error?showError(error):sessionChanged(data.session?.user||null));
    client.auth.onAuthStateChange((event,session)=>{
      // 로그아웃·로그인 직후 동기화는 별도의 비동기 작업으로 처리합니다.
      if(event==='SIGNED_OUT') sessionChanged(null);
      if(event==='SIGNED_IN' && session?.user?.id!==store.user?.id) sessionChanged(session.user);
    });
  }
  drawPages();
})();