package com.alejandro.bank.accounts.corebanking.mainframe;

import com.alejandro.bank.accounts.corebanking.CoreBanking;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

// El core bancario por HTTP, con la dirección y el plazo de config-repo (bank.core-banking.*), igual que en
// accounts-service. El cliente se arma por código para usar el mismo plazo (Duration) para conectar y para leer
public class MainframeConfig {

    @Produces
    @ApplicationScoped
    CoreBanking coreBanking(@ConfigProperty(name = "bank.core-banking.url") String url,
                            @ConfigProperty(name = "bank.core-banking.timeout", defaultValue = "2s") Duration timeout) {
        return client(url, timeout);
    }

    // También lo usan las pruebas, contra el WireMock real
    public static CoreBanking client(String url, Duration timeout) {
        CoreBankingApi api = QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(url))
                .connectTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                // Pasado este plazo, el resultado es UNKNOWN: el core pudo haberlo registrado
                .readTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .build(CoreBankingApi.class);
        return new MainframeCoreBanking(api);
    }
}
