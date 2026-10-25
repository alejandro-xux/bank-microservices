package com.alejandro.bank.accounts.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// BANK-83: toda fecha que se guarda tiene a lo sumo microsegundos, lo que guarda PostgreSQL. Con un reloj fijo con
// nanosegundos, para que falle en cualquier sistema si se quita el truncado (en macOS Instant.now() ya viene en
// microsegundos y una prueba con la hora real no probaría nada)
class TimestampsTest {

    @Test
    void nanosecondsAreDroppedSoTheValueIsTheSameOneThePostgresColumnKeeps() {
        Clock linuxLike = Clock.fixed(Instant.parse("2026-10-25T17:08:41.086380939Z"), ZoneOffset.UTC);

        assertThat(Timestamps.now(linuxLike)).isEqualTo(Instant.parse("2026-10-25T17:08:41.086380Z"));
    }

    @Test
    void whatTheEntitiesStoreHasNoNanoseconds() {
        // Con la hora real: en Linux esto fallaba antes del arreglo (en macOS pasaba igual, por eso la prueba de arriba)
        assertThat(Movement.credit(UUID.randomUUID(), BigDecimal.TEN, BigDecimal.TEN, null).getCreatedAt().getNano() % 1000).isZero();
    }
}
