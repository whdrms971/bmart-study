package com.baropick.delivery.common;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

/**
 * (sourceSystem, requestId) 명령 멱등.
 * 같은 키·같은 본문 → 기존 응답 반환, 같은 키·다른 본문 → 409 IDEMPOTENCY_CONFLICT.
 * 호출자의 트랜잭션 안에서 실행되어 명령 결과와 함께 원자적으로 커밋된다.
 */
@Service
public class IdempotencyService {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;

    public IdempotencyService(JdbcTemplate jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public <T> T execute(String sourceSystem, String requestId, Object requestBody, Class<T> type, Supplier<T> action) {
        if (requestId == null || requestId.isBlank()) {
            throw ApiException.badRequest("REQUEST_ID_REQUIRED", "requestId 가 필요합니다");
        }
        String hash = Hashing.sha256(json.writeValueAsString(requestBody));
        Optional<T> prior = lookup(sourceSystem, requestId, hash, type);
        if (prior.isPresent()) return prior.get();
        T result = action.get();
        try {
            jdbc.update("insert into idempotency_record(source_system, request_id, request_hash, response_body, created_at) values (?,?,?,?,?)",
                    sourceSystem, requestId, hash, json.writeValueAsString(result), Timestamp.from(clock.instant()));
        } catch (DuplicateKeyException e) {
            // 동시 동일 요청: 상태 전이는 행 잠금으로 이미 직렬화됨. 앞선 결과를 반환한다.
            return lookup(sourceSystem, requestId, hash, type).orElseThrow(() -> e);
        }
        return result;
    }

    private <T> Optional<T> lookup(String sourceSystem, String requestId, String hash, Class<T> type) {
        List<String[]> rows = jdbc.query("select request_hash, response_body from idempotency_record where source_system=? and request_id=?",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, sourceSystem, requestId);
        if (rows.isEmpty()) return Optional.empty();
        if (!rows.get(0)[0].equals(hash)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 requestId 로 다른 내용의 요청이 이미 처리되었습니다");
        }
        return Optional.of(json.readValue(rows.get(0)[1], type));
    }
}
