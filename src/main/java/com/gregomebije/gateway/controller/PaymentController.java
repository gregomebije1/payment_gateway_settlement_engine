package com.gregomebije.gateway.controller;

import com.gregomebije.gateway.service.*;
import com.gregomebije.gateway.dto.Models.*;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {
    
    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/setup")
    public ResponseEntity<String> setup() {
        paymentService.setup();
        return ResponseEntity.status(HttpStatus.CREATED).body("Setup completed");
    }
      
    @PostMapping("/onboarding/user")
    public ResponseEntity<String> registerUser(@RequestBody CreateTenantRequest request) {
        String message = paymentService.registerUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(message);
    }

    //record CreateChargeRequest {String transactionId, long amount}
    @PostMapping("/gateway/v1/charges")
    public ResponseEntity<String> charge(@RequestBody CreateChargeRequest request) {
        paymentService.charge(request);
        return ResponseEntity.status(HttpStatus.CREATED).body("Success");
    }

    /*
    //the card network pays the Gateway,
    @PostMapping("gateway/settlement")
    public ResponseEntity<String> gatewaySettlement {
        -- [Gateway DB]
        BEGIN;
        UPDATE payments SET status = 'SETTLED' WHERE id = :dep1 AND status = 'CAPTURED';  -- 0 rows => ROLLBACK
        SELECT enqueue_entry(:e4, 'gateway', 'Settlement and payout', ARRAY[
        eline('G110', NULL, 10300, 0), eline('G100', NULL, 0, 10300),    -- cash from card network
        eline('G200', NULL, 10150, 0), eline('G110', NULL, 0, 10150)]);  -- net paid to the wallet company
        INSERT INTO outbox (target, path, idempotency_key, body)
        VALUES ('wallet', '/wallet/v1/webhooks/gateway', :msg2,
        jsonb_build_object('type','settlement.paid','charge_id',:dep1,'gross',10300,'fee',150,'net',10150));
        COMMIT;

        POST /ledger/v1/entries e4
        CAll /wallet/v1/webhooks/gateway', :msg2 (settlement.paid)
    }
    
    //POST /gateway/v1/payouts
    //GET /gateway/v1/charges/{id}
    //POST /gateway/v1/webhooks/bank
    //POST /gateway/v1/payments, Idempotency-Key:
    */
}