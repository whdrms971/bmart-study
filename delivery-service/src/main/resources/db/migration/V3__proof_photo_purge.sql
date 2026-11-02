-- P-09 보관 기간: 증빙 사진 파일 삭제 시각. 증빙 기록(해시·크기·시각)은 남기고 파일만 지운다.
alter table delivery_proof add column photo_purged_at timestamp with time zone;
create index ix_rider_location_received on rider_location (received_at);
