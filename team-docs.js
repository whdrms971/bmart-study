/* B마트 팀 자료 뷰어. 별도 서버나 데이터베이스를 사용하지 않습니다. */
document.addEventListener("DOMContentLoaded", function () {
  const cfg = window.BMART_CONTENT || {};
  const docs = Array.isArray(cfg.domainDocs) ? cfg.domainDocs : [];
  const safeFile = /^domains\/[A-Za-z0-9_-]+\.md$/;
  const safeId = /^[a-z][a-z0-9_-]*$/;

  // 원본 자료는 index.html에 남기고 화면 표시 내용만 교체합니다.
  if (typeof DB !== "undefined") {
    for (const [id,patch] of Object.entries(cfg.ruleEdits || {})) {
      const item = DB.rules.find(r => r.id === id);
      if (!item || !patch || typeof patch !== "object") continue;
      ["title","when","inputs","result","auth","common","owned","gap"].forEach(k => {
        if (typeof patch[k] === "string") item[k] = patch[k];
      });
    }
    for (const [id,patch] of Object.entries(cfg.policyEdits || {})) {
      const item = DB.policies.find(p => p.id === id);
      if (!item || !patch || typeof patch !== "object") continue;
      ["title","output"].forEach(k => {
        if (typeof patch[k] === "string") item[k] = patch[k];
      });
    }
  }

  function element(tag,text) {
    const el = document.createElement(tag);
    if(text !== undefined) el.textContent = String(text);
    return el;
  }
  function appendInline(parent, text) {
    const source = String(text || "");
    const pattern = /\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)|\*\*([^*]+)\*\*/g;
    let cursor = 0, token;
    while ((token = pattern.exec(source))) {
      if (token.index > cursor) parent.appendChild(document.createTextNode(source.slice(cursor,token.index)));
      let node;
      if (token[1] && token[2]) {
        node = element("a",token[1]);
        node.href = token[2];
        node.target = "_blank";
        node.rel = "noopener noreferrer";
      } else {
        node = element("strong",token[3]);
      }
      parent.appendChild(node);
      cursor = pattern.lastIndex;
    }
    if (cursor < source.length) parent.appendChild(document.createTextNode(source.slice(cursor)));
  }
  function rich(tag, parent, line) {
    const node = element(tag);
    appendInline(node,line);
    parent.appendChild(node);
    return node;
  }

  // Markdown 간단 렌더링: 제목, 문단, 목록, 인용, 표, 굵은 글씨, https 링크.
  // 삽입 HTML은 실행되지 않도록 모두 textContent를 사용합니다.
  function showMarkdown(source, target) {
    target.replaceChildren();
    const lines = String(source).replace(/\r/g,"").split("\n");
    let list = null, listType = "";
    for (let i=0;i<lines.length;i++) {
      const line = lines[i].trim();
      if (!line) { list=null;listType="";continue; }
      const heading = line.match(/^(#{1,5})\s+(.+)$/);
      if (heading) {
        list=null;listType="";
        rich("h"+Math.min(5,heading[1].length+1),target,heading[2]);
        continue;
      }
      if (/^>\s?/.test(line)) {
        const quote = element("blockquote");
        rich("p",quote,line.replace(/^>\s?/,""));
        target.appendChild(quote);
        list=null;listType="";continue;
      }
      if (line.startsWith("|") && line.endsWith("|") &&
          i+1<lines.length && /^\|?\s*[:\-\s|]+\|?\s*$/.test(lines[i+1].trim())) {
        list=null;listType="";
        const table=element("table");
        const header=element("tr");
        for (const cell of line.slice(1,-1).split("|")) rich("th",header,cell.trim());
        const thead=element("thead");thead.appendChild(header);table.appendChild(thead);
        const tbody=element("tbody");i+=2;
        while (i<lines.length && lines[i].trim().startsWith("|") && lines[i].trim().endsWith("|")) {
          const row=element("tr");
          for (const cell of lines[i].trim().slice(1,-1).split("|")) rich("td",row,cell.trim());
          tbody.appendChild(row);i++;
        }
        i--;table.appendChild(tbody);target.appendChild(table);continue;
      }
      const bullet=line.match(/^[-*]\s+(.+)$/);
      const number=line.match(/^\d+\.\s+(.+)$/);
      if (bullet || number) {
        const type=bullet?"ul":"ol";
        if(listType!==type) { list=element(type);target.appendChild(list);listType=type; }
        rich("li",list,(bullet||number)[1]);
        continue;
      }
      if (/^---+$/.test(line)) {
        list=null;listType="";target.appendChild(element("hr"));continue;
      }
      list=null;listType="";
      rich("p",target,line);
    }
  }

  const section=element("section");section.id="team";section.className="view";
  const heading=element("div");heading.className="section-title";
  const headingBox=element("div");
  headingBox.append(element("h2","팀원별 도메인 자료"),element("p","팀원 조사 원문을 GitHub에서 수정하면 대시보드에도 최신 내용이 반영됩니다."));
  const configLink=element("a","목차·문서 목록 수정 ↗");
  configLink.className="btn";
  configLink.href="https://github.com/whdrms971/bmart-study/edit/main/dashboard-content.js";
  configLink.target="_blank";configLink.rel="noopener noreferrer";
  heading.append(headingBox,configLink);section.appendChild(heading);

  const layout=element("div");layout.className="team-doc-layout";
  const left=element("div");left.className="panel team-doc-list";
  left.appendChild(element("h3","자료 목차"));
  const right=element("div");right.className="panel team-doc-detail";
  const top=element("div");top.className="team-doc-top";
  const docTitle=element("h2","도메인 문서를 선택하세요");
  const edit=element("a","GitHub에서 내용 수정 ↗");edit.className="btn primary";
  edit.target="_blank";edit.rel="noopener noreferrer";edit.hidden=true;
  top.append(docTitle,edit);
  const info=element("p","팀원 조사 자료를 확인합니다.");info.className="panel-sub";
  const body=element("div");body.className="team-doc-body";
  right.append(top,info,body);layout.append(left,right);section.appendChild(layout);

  const footer=document.querySelector(".main > .footer");
  if (footer && footer.parentNode) footer.parentNode.insertBefore(section,footer);
  else document.querySelector(".main").appendChild(section);

  let selected="";
  async function show(doc) {
    selected=doc.id;
    left.querySelectorAll("button[data-doc]").forEach(btn=>btn.classList.toggle("active",btn.dataset.doc===selected));
    docTitle.textContent=doc.label||doc.id;
    edit.hidden=false;
    edit.href="https://github.com/whdrms971/bmart-study/edit/main/"+doc.file;
    info.textContent="담당자: "+(doc.owner||"미정")+" · 화면의 수정은 GitHub 커밋 이후 전체 팀에 반영됩니다.";
    body.textContent="문서를 불러오는 중...";
    try {
      const response=await fetch("./"+doc.file,{cache:"no-store"});
      if (!response.ok) throw new Error("HTTP "+response.status);
      const text=await response.text();
      if (selected!==doc.id) return;
      showMarkdown(text,body);
    } catch(error) {
      if(selected!==doc.id)return;
      body.textContent="문서를 불러오지 못했습니다. GitHub Pages 배포를 확인하거나 위 버튼으로 원문을 열어 주세요.";
    }
  }

  const valid=docs.filter(d=>d && safeId.test(d.id) && safeFile.test(d.file));
  if(!valid.length)left.appendChild(element("p","등록된 팀 문서가 없습니다. dashboard-content.js에서 추가해 주세요."));
  for(const doc of valid) {
    const button=element("button");button.type="button";button.className="team-doc-item";button.dataset.doc=doc.id;
    const label=element("strong",doc.label||doc.id);
    const owner=element("small","담당자: "+(doc.owner||"미정"));
    button.append(label,owner);
    button.addEventListener("click",()=>show(doc));
    left.appendChild(button);
  }
  if (valid.length)show(valid[0]);

  if (typeof renderOverview === "function" && 
      (Object.keys(cfg.ruleEdits||{}).length || Object.keys(cfg.policyEdits||{}).length)) {
    renderOverview();renderMatrix();renderRules();renderPolicies();renderHandoff();
  }
});
