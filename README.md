# B마트 공통 규칙 연결 대시보드 — 팀 공동 편집판

기존 PART 1~12의 58개 규칙, 30개 미정 정책, 5개 도메인 연결 정보를 보존합니다.

## 대시보드 열기
- **웹사이트:** https://whdrms971.github.io/bmart-study/ (GitHub Pages 배포 완료 후 이용 가능)
- **배포 설정:** Settings → Pages → Deploy from a branch → `main` / `/(root)` → Save

## 새 기능
- 목차 추가·제목/설명/본문 편집·순서 변경·숨김·사용자 추가 목차 삭제
- 팀 작업의 담당자·진행 상태·완료율 공동 관리
- 정책 결정 상태·담당자·기한·결정 근거 공동 저장
- 변경 이력 저장·낙관적 버전 충돌 검사·실시간 변경 알림
- 기존 상세 규칙 원문은 GitHub HTML에 고정하여 보존하고, 팀 추가 본문은 별도 DB에서 편집합니다.

## 중요: 공동 저장 활성화하기
**현재 저장소의 `shared-editor-config.js`는 설정 값이 비어 있습니다.** 사이트를 볼 수 있어도 공동 편집은 아직 작동하지 않습니다.

1. [Supabase](https://supabase.com/dashboard)에서 **대시보드 전용 프로젝트**를 만듭니다.
2. SQL Editor에서 `supabase/setup.sql` 전체를 실행합니다.
3. Authentication → Users에서 팀원 계정을 준비합니다. 공개 가입은 프로젝트 정책에 맞게 차단하거나 제한하세요.
4. SQL Editor로 팀원 역할을 등록합니다. **최초 관리자는 반드시 admin으로 등록**합니다.

```sql
insert into public.dashboard_members(user_id, display_name, role)
select id, '팀 관리자', 'admin'
from auth.users where email = 'ADMIN_EMAIL@example.com'
on conflict (user_id) do update set role='admin', active=true;
```

다른 팀원은 이메일·이름을 바꾸고 `role='editor'` 또는 `role='viewer'`를 적용합니다.

5. `shared-editor-config.js`에 프로젝트 **URL과 공개 publishable key**를 입력해 커밋합니다.

```javascript
window.BMART_SUPABASE = {
  url: 'https://YOUR-PROJECT.supabase.co',
  anonKey: 'YOUR-PUBLISHABLE-KEY'
};
```

6. 웹페이지에서 **팀원 로그인** → **목차·본문 편집**을 사용합니다. `admin` 또는 `editor` 권한의 팀원만 수정할 수 있습니다.

**절대로 service_role, secret key, DB 비밀번호를 GitHub에 넣지 마세요.** 서버 권한은 Supabase RLS에서 검증합니다.

## 현재 미구현·주의사항
- Supabase 설정 전에는 기존 HTML 보기와 개인 브라우저 정책 기록만 가능합니다.
- 기존 58개 규칙의 원문과 30개 정책의 제목은 웹 편집 대상이 아니라 HTML의 조사 근거입니다. 본문 추가 및 목차 편집은 가능합니다.
- 별도 파일이나 새로운 프로그램 기능을 추가하려면 GitHub 파일을 수정해야 합니다.
- 진행률은 `완료 작업 수 / 전체 작업 수`이고, 전체 B마트 애플리케이션 구현률은 아닙니다.
- 공개 Pages에서는 HTML과 정적 자료를 외부에서도 볼 수 있습니다. DB 내부 내용은 팀원 로그인·RLS로 보호하지만 **비공개 조사 원문은 이 저장소에 올리지 마세요**.
- 실제 DB 연결·동기화·접근 통제 시나리오 테스트는 아직 수행되지 않았습니다.

## 팀 검증 체크
- [ ] GitHub Pages에서 기존 화면 정상 표시
- [ ] Supabase SQL 적용 및 팀원 등록
- [ ] 관리자 목차 추가·이름 변경·본문 저장
- [ ] 다른 팀원 PC에서 변경 확인
- [ ] 미승인 계정의 데이터 접근 거부
- [ ] 정책 확정은 관리자 권한만 허용
- [ ] 동시 편집 충돌 시 경고 및 재조회
