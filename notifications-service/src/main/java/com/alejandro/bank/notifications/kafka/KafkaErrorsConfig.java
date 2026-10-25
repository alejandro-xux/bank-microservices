package com.alejandro.bank.notifications.kafka;

import com.alejandro.bank.notifications.customers.ContactsUnavailableException;
import com.alejandro.bank.notifications.transfers.InvalidEventException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.mail.MailException;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

// Qué hacer cuando un aviso falla (BANK-73, spike BANK-66 K9). Lo que evita perder avisos es CLASIFICAR el error:
//   pasajero (correo o customers-service caídos, circuito abierto) → reintentar sin límite, con espera creciente
//   nunca va a funcionar (ilegible, inválido)                       → al DLT enseguida, sin bloquear a los demás
//   cualquier otro (un error de código)                              → 3 reintentos y al DLT
// Sin esto, Spring Kafka hace 10 intentos seguidos y SALTA el mensaje con solo un ERROR en el log: el aviso se pierde
@Configuration(proxyBeanMethods = false)
class KafkaErrorsConfig {

    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template) {
        // Misma partición que el original: el DLT conserva el orden de cada cuenta
        DeadLetterPublishingRecoverer toDeadLetter = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler(toDeadLetter, new FixedBackOff(1000, 3));
        handler.setBackOffFunction((record, ex) -> isTransient(ex) ? untilItComesBack() : null);
        // Ilegibles (DeserializationException) ya van directo al DLT por defecto; se suman los inválidos
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }

    // 1 s, 2 s, 4 s, 8 s, 8 s… sin máximo de tiempo: el aviso espera a que el correo vuelva
    private static ExponentialBackOff untilItComesBack() {
        ExponentialBackOff backOff = new ExponentialBackOff(1000, 2.0);
        backOff.setMaxInterval(8000);
        return backOff;
    }

    static boolean isTransient(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof MailException || cause instanceof ContactsUnavailableException
                    || cause instanceof CallNotPermittedException) {
                return true;
            }
        }
        return false;
    }
}
