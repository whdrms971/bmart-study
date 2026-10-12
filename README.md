# B마트 공통 규칙 연결 대시보드

5개 도메인 × PART 1~12의 **공통 규칙 58개와 팀 미정 정책 30건**을 확인하는 정적 HTML 대시보드입니다.

**대시보드 주소:** https://whdrms971.github.io/bmart-study/

> GitHub Pages가 활성화되어 있어야 위 주소에서 화면이 열립니다. Settings → Pages → Deploy from a branch → main / (root) 설정.

## 팀원 이용
- 팀원 누구나 링크를 클릭해 조사 내용과 도메인 연결 정보를 조회할 수 있습니다.
- **목차·소개·안내문 수정은 GitHub에서 변경한 다음 Commit changes** 하면 Pages 사이트에 반영됩니다.
- 실시간 다중 편집, DB, 로그인, Supabase는 사용하지 않습니다.

## 목차 수정 (가장 쉬운 방법)
1. [dashboard-content.js](./dashboard-content.js) 파일을 GitHub에서 엽니다.
2. 오른쪽 연필 아이콘(**Edit this file**)을 클릭합니다.
3. 파일 위쪽 `window.BMART_CONTENT` 객체의 **menus** 부분을 수정합니다.
   - `label`: 왼쪽 목차 이름
   - `title`: 해당 화면의 큰 제목
   - `description`: 페이지 소개 문구
   - `icon`: 목차 아이콘
   - **순서를 바꾸고 싶으면 항목 행의 순서를 변경**합니다.
4. **Commit changes**를 누릅니다.
5. GitHub Pages가 재배포되면 팀원들에게도 반영됩니다(필요하면 새로고침).

기존 다섯 메뉴의 `id`(`overview`, `matrix`, `rules`, `policies`, `handoff`)는 바꾸지 마세요.

## 새 목차 또는 내용 추가
- 같은 파일의 `extraPages` 배열에 새 페이지를 추가합니다. 파일 내 주석 예시를 참고하세요.
- 기존 페이지에 안내 문구를 추가하려면 `notes`에 `overview: "추가 안내"` 같은 값을 넣습니다.
- 58개 규칙 또는 도메인 매트릭스의 **원본 데이터 자체**를 바꾸려면 `index.html` 내부 `const DB` 데이터를 수정해야 합니다. 가능하면 수정 전 근거 자료를 검토하고 별도 커밋을 남기세요.

## 정책 회의 기록 관련
현재 미정 정책 화면의 '저장' 버튼은 **현재 브라우저의 localStorage에만 저장**됩니다. 팀원 전체에 공유되지는 않습니다. 회의 결정 내용을 공유할 때는 CSV/JSON으로 내보내거나 GitHub에 회의 기록을 별도로 커밋해 관리하세요. **개인 브라우저의 '확정' 표시는 공식 팀 확정으로 간주하면 안 됩니다.**

## 파일 구성
- `index.html`: 기존 대시보드 화면·58개 규칙·30개 정책
- `dashboard-content.js`: 팀이 GitHub에서 수정할 목차·페이지 안내문 및 새 페이지 설정
- `README.md`: 사용 설명서

> 저장소와 Pages 사이트가 공개되어 있으므로 민감한 내부 자료·비밀번호·API 비밀키를 올리지 마세요.
