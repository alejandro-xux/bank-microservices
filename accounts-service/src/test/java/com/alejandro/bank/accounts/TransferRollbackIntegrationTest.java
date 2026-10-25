package com.alejandro.bank.accounts;

import com.alejandro.bank.accounts.domain.AccountNumber;
import com.alejandro.bank.accounts.repository.MovementRepository;
import com.alejandro.bank.accounts.service.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

// Si algo falla a mitad de la transferencia (aquí, al guardar el primer movimiento), no queda nada:
// ni el débito, ni el crédito, ni el registro transfer, ni su evento en el outbox (BANK-69); y la clave
// sigue libre para reintentar
class TransferRollbackIntegrationTest extends PostgresIntegrationTest {

    @MockitoSpyBean
    MovementRepository movementRepository;

    @Autowired
    TransferService transferService;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aFailureHalfwayRollsBackEverything() {
        jdbc.update("delete from outbox");
        jdbc.update("delete from movement");
        jdbc.update("delete from transfer");
        jdbc.update("delete from account");
        UUID from = insertAccount(ANA, AccountNumber.of(910_001));
        UUID to = insertAccount(LUIS, AccountNumber.of(910_002));
        UUID key = UUID.randomUUID();
        // Guardar el movimiento falla (a mitad de la transferencia: el transfer ya se insertó)
        doThrow(new IllegalStateException("falla simulada a mitad de la transferencia"))
                .when(movementRepository).save(any());

        assertThatThrownBy(() -> transferService.transfer(key, from, AccountNumber.of(910_002), new BigDecimal("100.00"), admin()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(balance(from)).isEqualTo("1000.00");
        assertThat(balance(to)).isEqualTo("1000.00");
        assertThat(jdbc.queryForObject("select count(*) from transfer", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox", Integer.class)).isZero();

        // La clave no quedó usada: con el repositorio otra vez normal, el reintento se hace
        reset(movementRepository);
        transferService.transfer(key, from, AccountNumber.of(910_002), new BigDecimal("100.00"), admin());
        assertThat(balance(from)).isEqualTo("900.00");
        assertThat(balance(to)).isEqualTo("1100.00");
        assertThat(jdbc.queryForObject("select count(*) from outbox", Integer.class)).isEqualTo(1);
    }

    private UUID insertAccount(String customerId, String number) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into account (id, number, customer_id, type, currency, balance, status, created_at, updated_at) "
                + "values (?, ?, ?, 'AHORROS', 'PEN', 1000.00, 'ACTIVE', now(), now())", id, number, customerId);
        return id;
    }

    private String balance(UUID id) {
        return jdbc.queryForObject("select balance::text from account where id = ?", String.class, id);
    }

    private static JwtAuthenticationToken admin() {
        Jwt jwt = Jwt.withTokenValue("token-asesor").header("alg", "RS256").subject("7b2e9d4c").claim("oid", "7b2e9d4c")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        return new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
    }
}
