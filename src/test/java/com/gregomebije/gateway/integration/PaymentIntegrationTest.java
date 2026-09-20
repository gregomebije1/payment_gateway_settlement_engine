package com.gregomebije.gateway.payment;

import com.gregomebije.gateway.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    void shouldSaveAndRetrievePaymentFromRealPostgres() {
        Payment payment = new Payment("pay-integration-1", java.math.BigDecimal.TEN, "GBP");
        
        Payment saved = paymentRepository.save(payment);
        
        assertThat(saved.getId()).isNotNull();
        assertThat(paymentRepository.findByReference("pay-integration-1")).isPresent();
    }
}
