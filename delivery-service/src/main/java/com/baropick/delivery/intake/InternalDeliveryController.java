package com.baropick.delivery.intake;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.baropick.delivery.execution.ExecutionService;
import com.baropick.delivery.handover.HandoverService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** OMS/MFC → Delivery 내부 계약. X-Internal-Token 필수 (SecurityConfig). */
@RestController
public class InternalDeliveryController {

    private final DeliveryRequestService intake;
    private final ExecutionService execution;
    private final HandoverService handover;

    public InternalDeliveryController(DeliveryRequestService intake, ExecutionService execution, HandoverService handover) {
        this.intake = intake;
        this.execution = execution;
        this.handover = handover;
    }

    @PostMapping("/internal/delivery-requests")
    public ResponseEntity<DeliveryRequestService.DeliveryRequestResult> create(@Valid @RequestBody DeliveryRequestService.DeliveryRequest req) {
        var r = intake.accept(req);
        return ResponseEntity.status(r.created() ? 201 : 200).body(r);
    }

    public record CancelRequest(@NotBlank @Size(max = 40) String sourceSystem, @NotBlank @Size(max = 100) String requestId,
                                @Size(max = 200) String reason) {}

    @PostMapping("/internal/deliveries/{deliveryId}/cancel")
    public ExecutionService.CancelResult cancel(@PathVariable String deliveryId, @Valid @RequestBody CancelRequest req) {
        return execution.cancelFromOms(deliveryId, req);
    }

    /** MFC → Delivery: 해당 배송의 포장 전체가 준비(SEALED)되었음을 통지. (sourceSystem,eventId) 멱등. */
    public record PackagesReadyEvent(@NotBlank @Size(max = 40) String sourceSystem, @NotBlank @Size(max = 100) String eventId,
                                     @NotBlank @Pattern(regexp = DeliveryRequestService.ID_PATTERN) String deliveryId,
                                     @NotEmpty @Size(max = 20) List<@NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,60}$") String> packageIds) {}

    @PostMapping("/internal/mfc-events/packages-ready")
    public HandoverService.PackagesReadyResult packagesReady(@Valid @RequestBody PackagesReadyEvent ev) {
        return handover.onPackagesReady(ev);
    }
}
