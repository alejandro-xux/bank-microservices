package com.alejandro.bank.accounts;

import com.alejandro.bank.accounts.outbox.OutboxCleanup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// BANK-81: la limpieza borra solo lo publicado hace más de la retención, en lotes, y nunca un evento pendiente.
// Lotes de 10 para que la prueba recorra varios
@TestPropertySource(properties = {"bank.outbox.cleanup.retention=7d", "bank.outbox.cleanup.batch-size=10"})
class OutboxCleanupIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    OutboxCleanup cleanup;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    @BeforeEach
    void emptyOutbox() {
        jdbc.update("delete from outbox");
    }

    @Test
    void onlyOldPublishedRowsAreDeletedInBatchesAndPendingOnesNeverAre() {
        for (int i = 0; i < 25; i++) {
            insert("old-published", "now() - interval '30 days'", "now() - interval '8 days'");
        }
        insert("recent-published", "now() - interval '2 days'", "now() - interval '1 day'");
        // Una que nunca se pudo publicar (Kafka caído mucho tiempo): es la más vieja y TIENE que quedarse
        insert("old-pending", "now() - interval '30 days'", "null");
        insert("new-pending", "now()", "null");

        // 25 en lotes de 10: 10 + 10 + 5
        assertThat(cleanup.clean()).isEqualTo(25);

        assertThat(jdbc.queryForList("select message_key from outbox order by seq", String.class))
                .containsExactly("recent-published", "old-pending", "new-pending");
        assertThat(cleanup.clean()).isZero();
    }

    @Test
    void itDoesNotWaitForARelayThatIsPublishingABatch() throws Exception {
        insert("old-published", "now() - interval '30 days'", "now() - interval '8 days'");
        insert("pending", "now()", "null");

        // Un relay a mitad de lote: tiene su candado consultivo y las pendientes tomadas, sin terminar
        CountDownLatch relayHasTheBatch = new CountDownLatch(1);
        CountDownLatch relayMayFinish = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<?> relay = pool.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.queryForObject("select pg_try_advisory_xact_lock(69)", Boolean.class);
                jdbc.queryForList("select id from outbox where published_at is null for update", UUID.class);
                relayHasTheBatch.countDown();
                await(relayMayFinish);
            }));
            assertThat(relayHasTheBatch.await(5, TimeUnit.SECONDS)).isTrue();

            long start = System.nanoTime();
            assertThat(cleanup.clean()).isEqualTo(1);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));

            relayMayFinish.countDown();
            relay.get(5, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForList("select message_key from outbox", String.class)).containsExactly("pending");
    }

    private void insert(String key, String createdAt, String publishedAt) {
        jdbc.update("insert into outbox (id, event_type, message_key, payload, created_at, published_at) "
                + "values (?, 'TransferCompleted', ?, ?, " + createdAt + ", " + publishedAt + ")",
                UUID.randomUUID(), key, new byte[]{1});
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
