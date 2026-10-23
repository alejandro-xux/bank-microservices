package com.alejandro.bank.customers.repository;

import com.alejandro.bank.customers.domain.Customer;
import com.azure.spring.data.cosmos.repository.ReactiveCosmosRepository;

public interface CustomerRepository extends ReactiveCosmosRepository<Customer, String> {
}