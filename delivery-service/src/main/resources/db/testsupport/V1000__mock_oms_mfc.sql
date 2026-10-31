-- local-test 전용: Mock OMS / Mock MFC 테이블. 운영 프로필의 Flyway location에는 포함되지 않는다.

create table mock_oms_order (
    order_id            varchar(40) primary key,
    customer_id         varchar(40) not null,
    mfc_id              varchar(40) not null,
    status              varchar(30) not null,
    total_weight_grams  integer not null,
    address             varchar(300) not null,
    address_detail      varchar(200),
    lat                 double precision not null,
    lng                 double precision not null,
    split               boolean not null,
    created_at          timestamp with time zone not null,
    completed_at        timestamp with time zone
);

create table mock_oms_order_delivery (
    delivery_id    varchar(40) primary key,
    order_id       varchar(40) not null,
    request_id     varchar(100) not null,
    package_count  integer not null,
    weight_grams   integer not null,
    status         varchar(30) not null,
    completed_at   timestamp with time zone
);

create table mock_oms_inbox (
    source_system  varchar(40) not null,
    event_id       varchar(100) not null,
    event_type     varchar(60) not null,
    payload        varchar(4000) not null,
    applied_at     timestamp with time zone not null,
    primary key (source_system, event_id)
);

create table mock_mfc_package (
    package_id        varchar(60) primary key,
    delivery_id       varchar(40) not null,
    order_id          varchar(40) not null,
    mfc_id            varchar(40) not null,
    status            varchar(20) not null,
    qr_token          varchar(64) not null,
    handover_id       varchar(60),
    handed_over_rider varchar(40),
    handed_over_at    timestamp with time zone,
    execution_id      varchar(100),
    created_at        timestamp with time zone not null
);
create index ix_mock_pkg_delivery on mock_mfc_package(delivery_id);
