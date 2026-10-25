package com.alejandro.bank.accounts.corebanking.mainframe;

import com.alejandro.bank.accounts.corebanking.CoreBanking;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.net.http.HttpClient;
import java.time.Duration;

// El core bancario por HTTP (BANK-77). Dirección y plazo por configuración (config-repo/accounts-service.yml)
@Configuration(proxyBeanMethods = false)
public class MainframeConfig {

    @Bean
    CoreBanking coreBanking(@Value("${bank.core-banking.url}") String url,
                            @Value("${bank.core-banking.timeout:2s}") Duration timeout) {
        return client(url, timeout);
    }

    // También lo usan las pruebas, contra el WireMock real
    public static CoreBanking client(String url, Duration timeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
        // Plazo para la respuesta: pasado este tiempo, el resultado es UNKNOWN (el core pudo haberlo registrado)
        requests.setReadTimeout(timeout);
        RestClient rest = RestClient.builder().baseUrl(url).requestFactory(requests).build();
        CoreBankingApi api = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest)).build()
                .createClient(CoreBankingApi.class);
        return new MainframeCoreBanking(api);
    }
}
