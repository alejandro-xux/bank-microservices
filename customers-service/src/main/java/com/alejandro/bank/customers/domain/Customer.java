package com.alejandro.bank.customers.domain;

import com.azure.spring.data.cosmos.core.mapping.Container;
import com.azure.spring.data.cosmos.core.mapping.PartitionKey;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Container(containerName = "customers", autoCreateContainer = false)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Customer {

    @Id
    private String id;

    @PartitionKey
    private String documentKey;

    private DocumentType documentType;
    private String documentNumber;

    private String firstName;
    private String paternalLastName;
    private String maternalLastName;
    private String searchName;

    private LocalDate birthDate;

    private List<Email> emails;
    private List<Phone> phones;

    private CustomerStatus status;
    private Instant createdAt;
    private Instant updatedAt;
}