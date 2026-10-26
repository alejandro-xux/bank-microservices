package com.alejandro.bank.accounts;

import com.alejandro.bank.contracts.customers.v1.CustomerStatus;
import com.alejandro.bank.contracts.customers.v1.CustomerSummary;
import com.alejandro.bank.contracts.customers.v1.CustomersServiceGrpc;
import com.alejandro.bank.contracts.customers.v1.GetMyCustomerRequest;
import io.grpc.stub.StreamObserver;

// customers-service simulado: el usuario de las pruebas está vinculado al cliente ANA
public class FakeCustomers extends CustomersServiceGrpc.CustomersServiceImplBase {

    static final String ANA = "c15a442d-5146-471e-8804-746f4b42253b";

    @Override
    public void getMyCustomer(GetMyCustomerRequest request, StreamObserver<CustomerSummary> response) {
        response.onNext(CustomerSummary.newBuilder().setId(ANA).setStatus(CustomerStatus.ACTIVE).build());
        response.onCompleted();
    }
}
