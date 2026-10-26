package com.alejandro.bank.accounts.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

// La hora de todo lo que se guarda (BANK-83, hallazgo del spike BANK-82). PostgreSQL guarda microsegundos
// (TIMESTAMPTZ) y Java en Linux da nanosegundos: sin truncar, la respuesta llevaba una fecha que la base nunca
// guardó, y el reintento con la misma Idempotency-Key devolvía otra. En macOS Instant.now() ya viene en
// microsegundos, por eso el error solo se vio en la CI
public final class Timestamps {

    private Timestamps() {
    }

    public static Instant now() {
        return now(Clock.systemUTC());
    }

    // Con un reloj a elección: la prueba usa uno con nanosegundos para comprobar el truncado en cualquier sistema
    static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
