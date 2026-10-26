package com.alejandro.bank.accounts.web;

import com.alejandro.bank.accounts.domain.AccountNumber;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Validación propia: formato 001-NNNNNNNNNN-D y dígito verificador Luhn correcto. Un número mal tipeado
// responde 400 en ese campo, sin buscarlo en la base. Vacío lo informa @NotBlank, no esta regla
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidAccountNumber.Validator.class)
public @interface ValidAccountNumber {

    String message() default "no es un número de cuenta válido (001-NNNNNNNNNN-D)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidAccountNumber, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.isBlank() || AccountNumber.isValid(value);
        }
    }
}
