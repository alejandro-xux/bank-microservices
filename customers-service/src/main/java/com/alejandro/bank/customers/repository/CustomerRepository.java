package com.alejandro.bank.customers.repository;

import com.alejandro.bank.customers.domain.Customer;
import com.azure.spring.data.cosmos.repository.Query;
import com.azure.spring.data.cosmos.repository.ReactiveCosmosRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;

public interface CustomerRepository extends ReactiveCosmosRepository<Customer, String> {

    Flux<Customer> findByDocumentKey(String documentKey);

    // identityId no es la partition key: consulta en todas las particiones (cross-partition)
    Flux<Customer> findByIdentityId(String identityId);

    @Query("SELECT * FROM c WHERE CONTAINS(c.searchName, @name) ORDER BY c.searchName OFFSET @offset LIMIT @limit")
    Flux<Customer> searchByName(@Param("name") String name, @Param("offset") int offset, @Param("limit") int limit);
}