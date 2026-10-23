package com.alejandro.bank.customers;

import com.alejandro.bank.customers.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class CustomersServiceApplicationTests {

	@MockitoBean
	CustomerRepository customerRepository;

	@Test
	void contextLoads() {
	}

}
