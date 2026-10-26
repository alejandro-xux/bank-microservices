package com.alejandro.bank.accounts.web;

import com.alejandro.bank.accounts.repository.MovementRepository;

import java.util.List;

// La misma forma de página que accounts-service (content, page, size, totalElements, totalPages)
public record MovementPageResponse(
        List<MovementLineResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static MovementPageResponse from(MovementRepository.HistoryPage page) {
        return new MovementPageResponse(
                page.lines().stream().map(MovementLineResponse::from).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }
}
