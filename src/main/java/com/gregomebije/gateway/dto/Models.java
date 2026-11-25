
package com.gregomebije.gateway.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Models {
    private Models() {}
 
    public enum LedgerLineType { DEBIT, CREDIT }
    public enum AccountType { 
          ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE;
          
           /** ASSET/EXPENSE are debit; everything else is credit. */
          public boolean isDebitNormal() {
               return this == ASSET || this == EXPENSE;
          }
     }

  
    public record LoginRequest (  String tenantId,  String email,  String password ) {}
    public record LoginResponse (  String accessToken ) {}

    public record CreateUserRequest (String email,  String password,  String firstName,  String lastName ) {}
    public record CreateTenantRequest (  String email, String tenantId,  String tenantName, String webhookURL, String token ) {}
    
    public record CreateAccountRequest (  String accountNumber,  String name,  AccountType type,  String currency ) {}
    public record CreateTransactionRequest ( String description,  String idempotencyKey,  List<LedgerLine> lines ) {}

    // A line may carry a sub_ref: the customer wallet Id or merchant id
    // That gives per-customer balances inside one account (a sub-ledger) 
    // without creating an account per customer.
    public record LedgerLine(UUID accountId, String subRef, LedgerLineType ledgerLineType, long debit, long credit) {}


    public record CreateChargeRequest(String tenantId,
                String idempotencyKey,
                long amount, 
                long fee,
                String entryId, 
                String msg1) {}

    public record GatewayWebhookPayload(String type, String idempotencyKey) {}
    public record CaptureResult(String paymentId, String status) {}
}