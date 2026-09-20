package com.gregomebije.gateway.payment;

import com.gregomebije.gateway.idempotency.IdempotencyService;
import com.gregomebije.gateway.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class) // Ensures annotations are initialized correctly
class PaymentServiceUnitTest {

    @Mock
    private PaymentRepository payments;

    @Mock
    private OutboxRepository outbox;

    @Mock
    private IdempotencyService idempotency;

    @InjectMocks
    private PaymentService service; // Mockito automatically passes the mocks here

    @Test
    void createsPaymentAndOutboxEvent() {
        // Arrange
        /*When the PaymentService runs and calls idempotency.acquire("idem-1"), 
         do not run the real database or Redis code.
         Instead, instantly pretend everything worked and return true.
         Returning true means that this specific idempotency key ("idem-1") has never 
         been seen by the system before. The service is allowed to safely proceed with 
         creating the payment.The real IdempotencyService likely connects to a live Redis 
         instance or an SQL database to lock the key.
        */
        
        when(idempotency.acquire("idem-1")).thenReturn(true);

        /*Whenever payments.save() is called with any Payment object, 
         immediately return that exact same object back."What it tells you to write 
         in production: Inside your real PaymentService.create() method, you must 
         call payments.save(payment) and capture or return the result.
        */
        when(payments.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

        // Act
        PaymentResponse response = service.create(
                "idem-1",
                new PaymentRequest("pay-1", new BigDecimal("100.00"), "GBP"));

        // Assert
        assertThat(response.reference()).isEqualTo("pay-1");

        /*Note the use of isEqualByComparingTo—this is AssertJ's safe way of 
        comparing BigDecimal values so that 100.00 and 100 are treated as 
        mathematically equal, ignoring trailing zeroes.
        */
        assertThat(response.amount()).isEqualByComparingTo("100.00");

        /*This checks for a side effect. It ensures that during the execution 
         of service.create(), your code reached out and called 
         the outbox.save(...) method exactly once. If your code forgets 
         to save to the outbox, this assertion fails.What it tells you to 
         write in production: Inside your create method, after confirming 
         the request is valid, you must instantiate an outbox event object 
         and explicitly call outbox.save(event).
        */
        verify(outbox).save(any());
    }

    @Test
    void duplicateDoesNotCreateAnotherPayment() {
        // Arrange
        when(idempotency.acquire("idem-1")).thenReturn(false);
        Payment existing = new Payment("pay-1", new BigDecimal("10.00"), "GBP");
        when(payments.findByReference("pay-1")).thenReturn(Optional.of(existing));

        // Act
        service.create("idem-1",
                new PaymentRequest("pay-1", new BigDecimal("10.00"), "GBP"));

        // Assert
        verify(payments, never()).save(any());
        verify(outbox, never()).save(any());
    }
}
