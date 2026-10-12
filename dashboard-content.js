/* ============================================================
 * B마트 대시보드 — 목차 / 페이지 설명 / 회의 메모
 *
 * 팀에서 수정할 때는 아래 BMART_CONTENT 안의 값만 바꿉니다.
 * GitHub에서 수정 → Commit changes → Pages 재배포
 *
 * - menus 순서를 바꾸면 왼쪽 목차 순서가 바뀝니다.
 * - label: 메뉴 이름 / title: 페이지 위쪽 제목 / description: 소개 문구
 * - 원래 목차의 id(overview/matrix/rules/policies/handoff)는 바꾸지 마세요.
 * - 기존 58개 규칙·30개 정책 근거는 index.html에 그대로 보존됩니다.
 * ============================================================ */
window.BMART_CONTENT = {
  menus: [
    { id: "overview", label: "전체 개요", icon: "◈",
      title: "공통 규칙 연결 대시보드",
      description: "5개 도메인의 업무를 다시 쓰지 않고, PART 1~12가 어디에 적용되는지 한눈에 확인합니다." },
    { id: "matrix", label: "도메인 연결 매트릭스", icon: "▦",
      title: "도메인 연결 매트릭스",
      description: "도메인과 PART의 접점을 확인하고 관련 세부 규칙을 열어보세요." },
    { id: "rules", label: "공통 규칙 58개", icon: "☷",
      title: "PART 1~12 · 개별 공통 규칙",
      description: "58개 공통 규칙을 검색하고 입력·결과·권한·예외를 확인합니다." },
    { id: "policies", label: "팀 미정 정책 30건", icon: "✓",
      title: "미정 정책 · 팀 회의 결정판",
      description: "30개 정책 중 팀에서 결정해야 할 사항과 기록을 확인합니다." },
    { id: "handoff", label: "구현 책임·후속 작업", icon: "↗",
      title: "개발 연결·책임 분리",
      description: "공통 설계 기준과 도메인별 구현 책임을 연결합니다." }
  ],

  // 기존 페이지에 보충 설명을 표시하고 싶으면 아래처럼 작성합니다.
  // notes: { overview: "10월 12일 팀 회의에서 확인할 내용입니다." }
  notes: {},

  // 새 목차를 만들고 싶으면 배열에 항목을 추가합니다.
  // example:
  // extraPages: [
  //   {
  //     id: "meeting",
  //     label: "팀 회의 메모",
  //     icon: "✎",
  //     title: "팀 회의 메모",
  //     description: "협의할 사항을 기록합니다.",
  //     paragraphs: ["회의 내용 1", "회의 내용 2"],
  //     links: [{ label: "회의 자료", url: "https://github.com/whdrms971/bmart-study" }]
  //   }
  // ]
  extraPages: [],

  // 정책 상태를 팀 전체에 공유하려면 GitHub에서 여기를 수정합니다.
  // 키는 기존 정책 ID(M01~M30), 값은 결정 기록입니다.
  // 예:
  // policyDecisions: {
  //   M03: { state: "논의 중", owner: "미정", deadline: "", note: "정책 선택 전" }
  // }
  // state = "미정" | "논의 중" | "확정" | "제외"
  policyDecisions: {}
};

/* ============================================================
 * 아래는 화면 연결 코드입니다. 보통 수정할 필요가 없습니다.
 * ============================================================ */
document.addEventListener("DOMContentLoaded", () => {
  if (typeof go !== "function" || typeof pageTitles === "undefined") return;
  const cfg = window.BMART_CONTENT || {};
  const nav = document.getElementById("nav");
  const baseIds = new Set(["overview", "matrix", "rules", "policies", "handoff"]);
  const menus = [];
  const used = new Set();

  function addMenu(id, label, icon, title, description) {
    if (!id || used.has(id)) return;
    used.add(id);
    pageTitles[id] = [String(title || label || id), String(description || "")];
    menus.push({ id, label: String(label || id), icon: String(icon || "•") });
  }

  for (const item of cfg.menus || []) {
    if (!item || !baseIds.has(item.id)) continue;
    addMenu(item.id, item.label, item.icon, item.title, item.description);
  }

  for (const page of cfg.extraPages || []) {
    if (!page || !/^[a-z][a-z0-9_-]*$/.test(page.id) || baseIds.has(page.id) || document.getElementById(page.id)) continue;
    const section = document.createElement("section");
    section.className = "view";
    section.id = page.id;
    const inner = document.createElement("div");
    inner.className = "panel";
    const h = document.createElement("h2");
    h.textContent = String(page.title || page.label || page.id);
    inner.appendChild(h);
    for (const paragraph of page.paragraphs || []) {
      const p = document.createElement("p");
      p.textContent = String(paragraph);
      inner.appendChild(p);
    }
    for (const link of page.links || []) {
      if (!link || !/^https?:\/\//i.test(link.url || "")) continue;
      const p = document.createElement("p");
      const a = document.createElement("a");
      a.href = link.url;
      a.textContent = link.label || link.url;
      a.target = "_blank";
      a.rel = "noopener noreferrer";
      p.appendChild(a);
      inner.appendChild(p);
    }
    section.appendChild(inner);
    const footer = document.querySelector(".main > .footer");
    footer.parentNode.insertBefore(section, footer);
    addMenu(page.id, page.label, page.icon, page.title, page.description);
  }

  for (const [id, note] of Object.entries(cfg.notes || {})) {
    const section = document.getElementById(id);
    if (!section || !note) continue;
    const panel = document.createElement("div");
    panel.className = "panel";
    panel.style.marginTop = "16px";
    const heading = document.createElement("h3");
    heading.textContent = "추가 안내";
    const body = document.createElement("p");
    body.textContent = String(note);
    panel.append(heading, body);
    section.appendChild(panel);
  }

  if (menus.length && nav) {
    nav.replaceChildren();
    for (const item of menus) {
      const b = document.createElement("button");
      b.type = "button";
      b.dataset.tab = item.id;
      if (item.id === "overview") b.className = "active";
      const ico = document.createElement("span");
      ico.className = "nav-icon";
      ico.textContent = item.icon;
      const label = document.createElement("span");
      label.className = "nav-label";
      label.textContent = item.label;
      b.append(ico, label);
      b.addEventListener("click", () => go(item.id));
      nav.appendChild(b);
    }
    const selected = menus.some(x => x.id === state.tab) ? state.tab : menus[0].id;
    go(selected);
  }
});
