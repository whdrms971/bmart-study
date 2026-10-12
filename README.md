# B마트 팀 통합 대시보드

**웹사이트:** https://whdrms971.github.io/bmart-study/

5개 도메인의 팀원 조사 자료와 PART 1~12 공통 규칙·도메인 연결 분석을 함께 모아 보는 GitHub Pages 대시보드입니다.

## 사용 방법

GitHub Pages 사이트에서 왼쪽 **팀원별 도메인 자료** 메뉴를 누르고 조사 자료를 선택하면 됩니다. 자료는 Markdown 파일로 저장되어 있으므로 GitHub에서 수정하고 **Commit changes**하면 사이트에 반영됩니다.

### 1. 팀원 자료 내용 수정

1. [domains 폴더](./domains)에서 해당 `.md` 파일을 엽니다.
2. GitHub 연필 아이콘을 눌러 내용을 수정합니다.
3. Commit changes를 누릅니다. 배포 이후 팀원이 같은 사이트 주소로 최신 내용을 확인할 수 있습니다.

현재 각 도메인 파일은 **조사 자료 취합용 빈 템플릿**입니다. 실제 팀원 조사 내용이 업로드되기 전까지는 확정된 설계가 아닙니다.

### 2. 목차 이름 변경·순서 변경·삭제

[dashboard-content.js](./dashboard-content.js)에서:
- `menus` 배열: 왼쪽 **전체 메뉴**의 이름(`label`)·제목(`title`)·설명(`description`)·순서 변경; 항목을 지우면 목차에서 숨깁니다.
- `domainDocs` 배열: **팀원 문서 목록**의 이름·담당자(`owner`)·순서 변경; 항목을 지우면 해당 문서를 대시보드에서 숨깁니다.
- 원래 메뉴 `id`는 기존 화면 기능 연결에 사용하므로 유지하는 편이 좋습니다.

목차에서 삭제해도 GitHub의 실제 문서가 삭제되는 것은 아닙니다. 실제 파일을 삭제하려면 GitHub에서 해당 파일의 **Delete file** 기능을 사용한 뒤 `domainDocs` 목록에서도 제거하세요.

### 3. 새 문서 추가

1. `domains/` 폴더에 새 Markdown 파일을 생성합니다(예: `07_shipping_policy.md`).
2. [dashboard-content.js](./dashboard-content.js)의 `domainDocs` 배열에 아래 항목을 추가합니다.

```js
{ id: "shipping", label: "배송 추가 정책", file: "domains/07_shipping_policy.md", owner: "미정" }
```

### 4. 공통 규칙·정책 설명 수정

기존 58개 규칙과 30개 정책은 원본 근거를 유지하기 위해 `index.html`에 보존되어 있습니다. 화면에서 일부 설명만 교체하려면 `dashboard-content.js`의 `ruleEdits` 또는 `policyEdits`에 변경 내용을 작성합니다.

```js
ruleEdits: { "1-1": { title: "수정된 규칙 제목" } },
policyEdits: { "M03": { title: "수정된 정책 제목" } },
```

원본 목록 자체를 구조적으로 제거하려면 연결 규칙과 통계에 영향을 주므로 별도 검토 후 `index.html`의 데이터 및 해당 뷰를 함께 변경해야 합니다.

### 5. 주의할 점

- **미정 정책 화면의 저장 버튼은 아직 개인 브라우저에만 저장됩니다.** 팀 전체 공동 기록은 GitHub에 회의록을 커밋해 관리하세요.
- 팀원 모두가 GitHub에서 문서를 고치려면 해당 저장소 쓰기 권한이 필요합니다. 쓰기 권한이 없는 팀원은 Fork/PR 또는 담당자에게 변경을 전달하는 방식으로 참여할 수 있습니다.
- 공개 저장소이므로 공개되면 안 되는 자료, 개인정보, API 비밀키 등은 넣지 마세요.
- MD 미리보기는 제목·문단·목록·기본 표·링크 등 일부 문법만 지원합니다. 복잡한 Markdown은 원문 GitHub에서 확인하세요.
- 실제 B마트 서비스 개발 완료나 통합 검증 성공을 의미하지 않습니다.

## 파일 구조

```text
index.html             전체 시스템, 5개 도메인 연결, PART 1~12, 58개 규칙, 30개 정책
dashboard-content.js   메뉴·문서 목록·설명 수정 지점
team-docs.js/css       팀원 Markdown 파일 표시
domains/               5개 도메인 + 공통 연결용 Markdown 템플릿
README.md              사용 설명서
```
