package com.alejandro.bank.accounts.web;

import com.alejandro.bank.accounts.repository.MovementLine;
import org.springframework.data.domain.Page;

import java.util.List;

// Forma propia de la página: el JSON de Page de Spring Data no es estable entre versiones
public record MovementPageResponse(
        List<MovementLineResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static MovementPageResponse from(Page<MovementLine> page) {
        return new MovementPageResponse(
                page.getContent().stream().map(MovementLineResponse::from).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
