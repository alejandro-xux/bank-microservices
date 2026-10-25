package com.alejandro.bank.notifications.transfers;

import com.alejandro.bank.notifications.customers.ContactsUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

// Circuit breaker sobre la entrega de avisos (contacto + correo, BANK-73, spike BANK-66 K10). Al abrirse PAUSA al
// consumidor: los eventos esperan en Kafka sin gastar intentos contra un correo caído. A los 10 s pasa a medio abierto
// y lo reanuda: el primer aviso hace de prueba; si sale, se cierra, y si no, se abre otra vez.
// También es un indicador de salud (/actuator/health → deliveryCircuit), fuera de la readiness: un correo caído no
// es motivo para sacar el pod del tráfico ni reiniciarlo
@Slf4j
@Component("deliveryCircuit")
public class DeliveryCircuit implements HealthIndicator {

    private final CircuitBreaker breaker;
    private final ObjectProvider<KafkaListenerEndpointRegistry> listeners;

    DeliveryCircuit(ObjectProvider<KafkaListenerEndpointRegistry> listeners,
                    @Value("${bank.notifications.circuit.wait-open:10s}") Duration waitOpen) {
        this.listeners = listeners;
        this.breaker = CircuitBreaker.of("delivery", CircuitBreakerConfig.custom()
                .slidingWindowSize(5)
                .minimumNumberOfCalls(3)
                .failureRateThreshold(50)
                .waitDurationInOpenState(waitOpen)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .permittedNumberOfCallsInHalfOpenState(1)
                // Solo cuentan las caídas de lo que está del otro lado; un evento inválido no abre el circuito
                .recordExceptions(MailException.class, ContactsUnavailableException.class)
                .build());
        breaker.getEventPublisher().onStateTransition(event -> {
            CircuitBreaker.State to = event.getStateTransition().getToState();
            log.warn("Circuito de avisos: {}", event.getStateTransition());
            switch (to) {
                case OPEN, FORCED_OPEN -> container().pause();
                case HALF_OPEN, CLOSED -> container().resume();
                default -> { }
            }
        });
    }

    // Si el circuito está abierto, lanza CallNotPermittedException sin llamar a nadie (error pasajero: se reintenta)
    public <T> T call(Supplier<T> delivery) {
        return breaker.executeSupplier(delivery);
    }

    public CircuitBreaker.State state() {
        return breaker.getState();
    }

    // Vuelve a CLOSED y olvida las fallas (operación, y pruebas que fallan a propósito y no deben abrirlo para otras)
    void reset() {
        breaker.reset();
        container().resume();
    }

    @Override
    public Health health() {
        MessageListenerContainer container = listeners.getObject().getListenerContainer(TransferEventsListener.ID);
        return Health.up()
                .withDetail("state", breaker.getState().name())
                .withDetail("consumerPaused", container != null && container.isPauseRequested())
                .build();
    }

    private MessageListenerContainer container() {
        return listeners.getObject().getListenerContainer(TransferEventsListener.ID);
    }
}
