package com.baropick.delivery.testsupport.mockmfc;

import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.baropick.delivery.handover.MfcHandoverPort.HandoverCommand;
import com.baropick.delivery.handover.MfcHandoverPort.HandoverResult;
import com.baropick.delivery.testsupport.admin.AdminGuard;

@RestController
@Profile("local-test")
public class MockMfcController {
    private final MockMfcService mfc;

    public MockMfcController(MockMfcService mfc) { this.mfc = mfc; }

    // ---- Delivery → Mock MFC (내부 토큰) ----
    @PostMapping("/mock-mfc/internal/handovers")
    public HandoverResult handover(@RequestBody HandoverCommand cmd) { return mfc.handover(cmd); }

    @GetMapping("/mock-mfc/internal/handovers/{executionId}")
    public HandoverResult query(@PathVariable String executionId) { return mfc.query(executionId); }

    // ---- 관리자 조작 (테스트 UI) ----
    @GetMapping("/test-api/mock-mfc/packages")
    public List<MockMfcService.MockPackage> all(Authentication auth) {
        AdminGuard.require(auth);
        return mfc.all();
    }

    @GetMapping("/test-api/mock-mfc/deliveries/{deliveryId}/packages")
    public List<MockMfcService.MockPackage> packages(Authentication auth, @PathVariable String deliveryId) {
        AdminGuard.require(auth);
        return mfc.packages(deliveryId);
    }

    @PostMapping("/test-api/mock-mfc/deliveries/{deliveryId}/seal")
    public Object seal(Authentication auth, @PathVariable String deliveryId) {
        AdminGuard.require(auth);
        return mfc.seal(deliveryId);
    }

    public record FaultRequest(MockMfcService.HandoverFault fault) {}

    @PutMapping("/test-api/mock-mfc/fault")
    public Map<String, Object> fault(Authentication auth, @RequestBody FaultRequest req) {
        AdminGuard.require(auth);
        mfc.setFault(req.fault());
        return Map.of("fault", mfc.fault().name());
    }
}
