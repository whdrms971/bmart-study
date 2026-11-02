-- 고객 도착 예외 요청 (DECISIONS.md A-03). 라이더가 요청하고 관리자만 승인/반려한다.
-- 요청 시점의 판정(거리·정확도·위치 경과·출처)과 처리자·사유·시각을 감사 기록으로 보존한다.
create table delivery_arrival_exception (
    id                   varchar(40) primary key,
    delivery_id          varchar(40) not null references delivery(id),
    rider_id             varchar(40) not null,
    assignment_version   integer not null,
    reason_code          varchar(40) not null,
    rider_note           varchar(300),
    check_code           varchar(40) not null,
    distance_m           double precision,
    accuracy_m           double precision,
    location_age_s       bigint,
    location_source      varchar(30),
    location_measured_at timestamp with time zone,
    status               varchar(20) not null,
    requested_at         timestamp with time zone not null,
    decided_by           varchar(40),
    decided_at           timestamp with time zone,
    decision_note        varchar(300)
);
create index ix_arrival_exception_delivery on delivery_arrival_exception (delivery_id);
create index ix_arrival_exception_status on delivery_arrival_exception (status);
