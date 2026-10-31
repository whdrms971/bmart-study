package com.baropick.delivery.testsupport.simulation;

/**
 * 장애 주입 정의. atFraction(0~1) 경로 진행률에 도달하면 1회 발동.
 * STOP: durationSeconds(가상) 동안 정차(위치 고정, 전송은 계속)
 * DROP: durationTicks 동안 위치 이벤트 유실
 * DELAY: durationTicks 동안 전송 보류 후 한꺼번에 늦게 전송
 * DUPLICATE: 발동 틱의 좌표를 같은 seq 로 두 번 전송
 * REORDER: 발동 틱 좌표를 다음 틱 좌표 뒤에 전송(역순)
 * ANOMALY: 범위 밖 좌표 1개 + 순간이동 좌표 1개 추가 전송
 * DISCONNECT: durationTicks 동안 연결 끊김(전송 없음) 후 재연결
 */
public record FaultSpec(Type type, double atFraction, Integer durationTicks, Integer durationSeconds) {
    public enum Type { STOP, DROP, DELAY, DUPLICATE, REORDER, ANOMALY, DISCONNECT }

    public FaultSpec {
        if (type == null) throw new IllegalArgumentException("type 필요");
        if (atFraction < 0 || atFraction > 1) throw new IllegalArgumentException("atFraction 은 0~1");
        if ((type == Type.DROP || type == Type.DELAY || type == Type.DISCONNECT) && (durationTicks == null || durationTicks < 1 || durationTicks > 120)) {
            throw new IllegalArgumentException(type + " 는 durationTicks(1~120) 필요");
        }
        if (type == Type.STOP && (durationSeconds == null || durationSeconds < 1 || durationSeconds > 3600)) {
            throw new IllegalArgumentException("STOP 은 durationSeconds(1~3600) 필요");
        }
    }
}
