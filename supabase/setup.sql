-- B마트 프로젝트 팀 공동 편집용 Supabase 스키마
-- Supabase Dashboard > SQL Editor에서 관리자 권한으로 1회 실행합니다.
-- 실서비스 주문/회원/재고 DB와 분리된 프로젝트를 권장합니다.

create table if not exists public.dashboard_members (
  user_id uuid primary key references auth.users(id) on delete cascade,
  display_name text not null default '',
  role text not null default 'editor' check (role in ('admin','editor','viewer')),
  active boolean not null default true,
  created_at timestamptz not null default now()
);

create table if not exists public.dashboard_pages (
  id text primary key check (id ~ '^[a-z][a-z0-9-]{1,60}$'),
  title text not null check (char_length(title) between 1 and 90),
  description text not null default '',
  body text not null default '',
  sort_order integer not null default 100,
  is_core boolean not null default false,
  is_visible boolean not null default true,
  version integer not null default 1 check (version >= 1),
  updated_by uuid references auth.users(id),
  updated_at timestamptz not null default now()
);

create table if not exists public.dashboard_policies (
  policy_id text primary key check (policy_id ~ '^M[0-9]{2}$'),
  decision_state text not null default '미정' check (decision_state in ('미정','논의 중','확정','제외')),
  owner text not null default '',
  deadline date,
  note text not null default '',
  version integer not null default 1 check (version >= 1),
  updated_by uuid references auth.users(id),
  updated_at timestamptz not null default now()
);

create table if not exists public.dashboard_tasks (
  id uuid primary key default gen_random_uuid(),
  title text not null check (char_length(title) between 1 and 200),
  owner text not null default '',
  status text not null default '미착수' check (status in ('미착수','진행 중','검토 중','완료','보류')),
  priority text not null default 'P1' check (priority in ('P0','P1','P2')),
  sort_order integer not null default 100,
  version integer not null default 1 check (version >= 1),
  updated_by uuid references auth.users(id),
  updated_at timestamptz not null default now()
);

create table if not exists public.dashboard_change_logs (
  id bigint generated always as identity primary key,
  actor uuid,
  table_name text not null,
  record_key text not null,
  action text not null check (action in ('INSERT','UPDATE','DELETE')),
  old_data jsonb,
  new_data jsonb,
  created_at timestamptz not null default now()
);

-- RLS 정책 내부에서 다른 회원 데이터를 조회해야 하므로 SECURITY DEFINER 사용.
create or replace function public.dashboard_is_member()
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.dashboard_members
    where user_id = (select auth.uid()) and active = true);
$$;

create or replace function public.dashboard_can_edit()
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.dashboard_members
    where user_id = (select auth.uid()) and active = true and role in ('admin','editor'));
$$;

create or replace function public.dashboard_is_admin()
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.dashboard_members
    where user_id = (select auth.uid()) and active = true and role = 'admin');
$$;

revoke all on function public.dashboard_is_member() from public;
revoke all on function public.dashboard_can_edit() from public;
revoke all on function public.dashboard_is_admin() from public;
grant execute on function public.dashboard_is_member() to authenticated;
grant execute on function public.dashboard_can_edit() to authenticated;
grant execute on function public.dashboard_is_admin() to authenticated;

create or replace function public.dashboard_before_change()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if TG_OP = 'INSERT' then
    new.version := 1;
  elsif TG_OP = 'UPDATE' then
    if new.version <> old.version + 1 then
      raise exception 'VERSION_CONFLICT: record version must increment by one';
    end if;
    if TG_TABLE_NAME = 'dashboard_pages' then
      if new.id <> old.id or new.is_core <> old.is_core then
        raise exception 'Core metadata cannot be changed';
      end if;
      if old.id = 'overview' and new.is_visible = false then
        raise exception 'Overview cannot be hidden';
      end if;
    end if;
    if TG_TABLE_NAME = 'dashboard_policies' then
      if new.policy_id <> old.policy_id then
        raise exception 'Policy ID cannot be changed';
      end if;
      if (old.decision_state = '확정' or new.decision_state = '확정')
        and not public.dashboard_is_admin() then
        raise exception 'Only admin may confirm or revise confirmed decisions';
      end if;
    end if;
  end if;
  new.updated_by := auth.uid();
  new.updated_at := now();
  return new;
end;
$$;

create or replace function public.dashboard_audit_change()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
  row_data jsonb;
  rec text;
begin
  if TG_OP = 'DELETE' then
    row_data := to_jsonb(old);
  else
    row_data := to_jsonb(new);
  end if;
  if TG_TABLE_NAME = 'dashboard_pages' then
    rec := row_data ->> 'id';
  elsif TG_TABLE_NAME = 'dashboard_policies' then
    rec := row_data ->> 'policy_id';
  else
    rec := row_data ->> 'id';
  end if;
  insert into public.dashboard_change_logs(actor,table_name,record_key,action,old_data,new_data)
  values(auth.uid(),TG_TABLE_NAME,rec,TG_OP,
    case when TG_OP <> 'INSERT' then to_jsonb(old) else null end,
    case when TG_OP <> 'DELETE' then to_jsonb(new) else null end);
  if TG_OP = 'DELETE' then
    return old;
  end if;
  return new;
end;
$$;

-- DO 블록으로 재실행 시 동일 트리거 중복 생성을 방지합니다.
do $$
declare t text;
begin
  foreach t in array array['dashboard_pages','dashboard_policies','dashboard_tasks'] loop
    execute format('drop trigger if exists dashboard_version_guard on public.%I', t);
    execute format('create trigger dashboard_version_guard before insert or update on public.%I for each row execute function public.dashboard_before_change()', t);
    execute format('drop trigger if exists dashboard_change_audit on public.%I', t);
    execute format('create trigger dashboard_change_audit after insert or update or delete on public.%I for each row execute function public.dashboard_audit_change()', t);
  end loop;
end;
$$;

alter table public.dashboard_members enable row level security;
alter table public.dashboard_pages enable row level security;
alter table public.dashboard_policies enable row level security;
alter table public.dashboard_tasks enable row level security;
alter table public.dashboard_change_logs enable row level security;

-- 회원 목록은 본인 행만 조회. 역할 부여는 Supabase 관리자 SQL Editor에서만 수행합니다.
drop policy if exists "member can see self" on public.dashboard_members;
create policy "member can see self" on public.dashboard_members
  for select to authenticated using (user_id = (select auth.uid()));

do $$
declare t text;
begin
  foreach t in array array['dashboard_pages','dashboard_policies','dashboard_tasks'] loop
    execute format('drop policy if exists "members read" on public.%I',t);
    execute format('create policy "members read" on public.%I for select to authenticated using (public.dashboard_is_member())',t);
    execute format('drop policy if exists "editors insert" on public.%I',t);
    execute format('create policy "editors insert" on public.%I for insert to authenticated with check (public.dashboard_can_edit())',t);
    execute format('drop policy if exists "editors update" on public.%I',t);
    execute format('create policy "editors update" on public.%I for update to authenticated using (public.dashboard_can_edit()) with check (public.dashboard_can_edit())',t);
    execute format('drop policy if exists "editors delete" on public.%I',t);
    if t = 'dashboard_pages' then
      execute format('create policy "editors delete" on public.%I for delete to authenticated using (public.dashboard_can_edit() and is_core = false)',t);
    else
      execute format('create policy "editors delete" on public.%I for delete to authenticated using (public.dashboard_can_edit())',t);
    end if;
  end loop;
end;
$$;

drop policy if exists "members read audit" on public.dashboard_change_logs;
create policy "members read audit" on public.dashboard_change_logs
  for select to authenticated using (public.dashboard_is_member());
-- 앱 클라이언트가 감사 로그를 직접 삽입/수정/삭제할 수 없도록 정책 부여하지 않습니다.

grant select on public.dashboard_members to authenticated;
grant select,insert,update,delete on public.dashboard_pages to authenticated;
grant select,insert,update,delete on public.dashboard_policies to authenticated;
grant select,insert,update,delete on public.dashboard_tasks to authenticated;
grant select on public.dashboard_change_logs to authenticated;
revoke all on public.dashboard_members from anon;
revoke all on public.dashboard_pages from anon;
revoke all on public.dashboard_policies from anon;
revoke all on public.dashboard_tasks from anon;
revoke all on public.dashboard_change_logs from anon;

insert into public.dashboard_pages(id,title,description,sort_order,is_core) values
 ('overview','전체 개요','5개 도메인과 PART 1~12 연결 현황',10,true),
 ('matrix','도메인 연결 매트릭스','도메인과 PART 연결 지점 탐색',20,true),
 ('rules','공통 규칙 58개','공통 규칙 검색·조회',30,true),
 ('policies','팀 미정 정책 30건','정책 결정·담당자·근거 기록',40,true),
 ('handoff','구현 책임·후속 작업','공통 설계 계약과 인수 기준',50,true),
 ('team-work','공동 작업·진행률','6개 설계·검증 작업 진행 현황',60,false)
on conflict (id) do nothing;

insert into public.dashboard_policies(policy_id)
select 'M' || lpad(n::text,2,'0') from generate_series(1,30) n
on conflict (policy_id) do nothing;

insert into public.dashboard_tasks(title,sort_order,priority)
select title, n*10,'P1' from (values
 (1,'PART 1~12 × 5개 도메인 연결 매트릭스'),
 (2,'권한 매트릭스 및 공통 API·이벤트 규칙'),
 (3,'상태 충돌·동시성·보상 처리 기준'),
 (4,'미정 정책·담당자·결정 기한 관리'),
 (5,'도메인 간 통합 테스트 시나리오'),
 (6,'성능·복구·배포 검증 계획')) as x(n,title)
where not exists (select 1 from public.dashboard_tasks);

-- Realtime 활성화. 이미 추가된 테이블이면 건너뜁니다.
do $$
declare t text;
begin
  foreach t in array array['dashboard_pages','dashboard_policies','dashboard_tasks'] loop
    if not exists (select 1 from pg_publication_tables where pubname='supabase_realtime' and schemaname='public' and tablename=t) then
      execute format('alter publication supabase_realtime add table public.%I',t);
    end if;
  end loop;
end;
$$;

-- 최초 팀 관리자 1명 등록: Auth > Users에서 계정을 생성한 뒤 아래 EMAIL을 변경해 실행하세요.
-- INSERT INTO public.dashboard_members(user_id,display_name,role)
-- SELECT id, '팀 관리자', 'admin' FROM auth.users WHERE email = 'ADMIN_EMAIL@example.com'
-- ON CONFLICT (user_id) DO UPDATE SET role='admin', active=true;
-- 다른 팀원은 역할 editor 또는 viewer로 등록합니다.