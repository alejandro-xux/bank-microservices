package com.alejandro.bank.accounts;

import com.alejandro.bank.accounts.outbox.OutboxCleanup;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// La limpieza del outbox, los mismos casos que OutboxCleanupIntegrationTest de accounts-service (BANK-81): solo lo
// publicado hace más de 7 días, por lotes (de 10 en el perfil %test), nunca una pendiente y sin esperar al relay
@QuarkusTest
class OutboxCleanupTest extends IntegrationTest {

    @Inject
    OutboxCleanup cleanup;

    @BeforeEach
    void emptyOutbox() {
        update("delete from outbox");
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

        assertThat(queryString("select string_agg(message_key, ', ' order by seq) from outbox"))
                .isEqualTo("recent-published, old-pending, new-pending");
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
            Future<?> relay = pool.submit(() -> {
                try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
                    connection.setAutoCommit(false);
                    sql.execute("select pg_try_advisory_xact_lock(69)");
                    sql.execute("select id from outbox where published_at is null for update");
                    relayHasTheBatch.countDown();
                    relayMayFinish.await(10, TimeUnit.SECONDS);
                    connection.rollback();
                }
                return null;
            });
            assertThat(relayHasTheBatch.await(5, TimeUnit.SECONDS)).isTrue();

            long start = System.nanoTime();
            assertThat(cleanup.clean()).isEqualTo(1);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));

            relayMayFinish.countDown();
            relay.get(5, TimeUnit.SECONDS);
        }
        assertThat(queryString("select string_agg(message_key, ', ') from outbox")).isEqualTo("pending");
    }

    private void insert(String key, String createdAt, String publishedAt) {
        update("insert into outbox (id, event_type, message_key, payload, created_at, published_at) "
                + "values (?, 'TransferCompleted', ?, ?, " + createdAt + ", " + publishedAt + ")",
                UUID.randomUUID(), key, new byte[]{1});
    }
}
