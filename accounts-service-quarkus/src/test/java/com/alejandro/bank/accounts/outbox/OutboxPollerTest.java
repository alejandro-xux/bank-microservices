package com.alejandro.bank.accounts.outbox;

import com.alejandro.bank.accounts.IntegrationTest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Los procesos de fondo del outbox. En %test no corren solos (every=off): aquí se llaman sus métodos a mano, para
// probar lo que agregan sobre el relay y la limpieza: seguir mientras los lotes salen llenos y no caerse si Kafka no
// responde
@QuarkusTest
class OutboxPollerTest extends IntegrationTest {

    @Inject
    OutboxPoller poller;

    @BeforeEach
    void emptyOutbox() {
        update("delete from outbox");
    }

    @Test
    void oneRoundKeepsPublishingWhileTheBatchesComeOutFull() {
        // 250 pendientes con lotes de 100: 100 + 100 + 50 en la misma vuelta, sin esperar la siguiente
        for (int i = 0; i < 250; i++) {
            insert("cuenta-" + (i % 3), "now()", "null");
        }

        poller.publish();

        assertThat(queryString("select count(*)::text from outbox where published_at is null")).isEqualTo("0");
    }

    @Test
    void withKafkaDownTheRoundEndsWithoutFailingAndNothingIsMarked() {
        insert("cuenta", "now()", "null");
        var docker = kafkaDocker();
        docker.pauseContainerCmd(kafkaContainerId()).exec();
        try {
            poller.publish();
        } finally {
            docker.unpauseContainerCmd(kafkaContainerId()).exec();
        }

        assertThat(queryString("select count(*)::text from outbox where published_at is null")).isEqualTo("1");
    }

    @Test
    void theHourlyRoundDeletesWhatWasPublishedLongAgo() {
        insert("vieja", "now() - interval '30 days'", "now() - interval '8 days'");
        insert("pendiente", "now()", "null");

        poller.clean();

        assertThat(queryString("select string_agg(message_key, ', ') from outbox")).isEqualTo("pendiente");
    }

    private void insert(String key, String createdAt, String publishedAt) {
        update("insert into outbox (id, event_type, message_key, payload, created_at, published_at) "
                + "values (?, 'TransferCompleted', ?, ?, " + createdAt + ", " + publishedAt + ")",
                UUID.randomUUID(), key, new byte[]{1});
    }
}
