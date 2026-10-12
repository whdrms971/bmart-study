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
    { id: "team", label: "팀원별 도메인 자료", icon: "▤",
      title: "5개 도메인 자료 취합",
      description: "다른 팀원들의 조사 자료를 편집·삭제·추가하고 공통 규칙과 연결합니다." },
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

  // 팀원 도메인 문서입니다. 항목 수정/삭제/순서 변경은 아래 배열에서,
  // 실제 문서 내용 수정은 GitHub domains/*.md 파일에서 진행합니다.
  domainDocs: [
    { id: "customer", label: "고객 앱", file: "domains/01_customer.md", owner: "미정" },
    { id: "catalog", label: "상품·가격·혜택", file: "domains/02_catalog.md", owner: "미정" },
    { id: "inventory", label: "공급·재고·물류", file: "domains/03_inventory.md", owner: "미정" },
    { id: "orders", label: "주문·결제·환불", file: "domains/04_orders.md", owner: "미정" },
    { id: "delivery", label: "배송·고객지원", file: "domains/05_delivery.md", owner: "미정" },
    { id: "integration", label: "도메인 연결·공통 기준", file: "domains/06_integration.md", owner: "미정" }
  ],

  // 기존 공통 규칙/정책 내용 중 변경할 부분만 덮어씁니다.
  // 예시: ruleEdits: { "1-1": { title: "변경 제목", gap: "수정 사항" } }
  // 예시: policyEdits: { "M03": { title: "회의 결과 반영 제목" } }
  ruleEdits: {},
  policyEdits: {},

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

  // 기존 미정 정책의 회의 기록은 현재 각 브라우저에 저장됩니다.
  // 팀 전체에 확정 정책을 게시하려면 별도 회의 기록을 GitHub에 커밋하세요.

};

/* ============================================================
 * 아래는 화면 연결 코드입니다. 보통 수정할 필요가 없습니다.
 * ============================================================ */
document.addEventListener("DOMContentLoaded", () => {
  if (typeof go !== "function" || typeof pageTitles === "undefined") return;
  const cfg = window.BMART_CONTENT || {};
  const nav = document.getElementById("nav");
  const baseIds = new Set(["overview", "team", "matrix", "rules", "policies", "handoff"]);
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
