package com.songly.common.validation;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Górna granica długości tekstu w <b>bajtach UTF-8</b>, nie w znakach.
 *
 * <p>
 * {@code @Size} liczy znaki, a bcrypt bierze pod uwagę 72 bajty i od Spring
 * Security 6.4 odrzuca dłuższe hasło wyjątkiem. Polska litera zajmuje 2 bajty,
 * emoji 4 — hasło mieszczące się w {@code @Size(max = 72)} kończyło się więc
 * 500 przy rejestracji, zmianie i resecie hasła zamiast zwykłym 400.
 */
@Documented
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
@Target({FIELD, METHOD, PARAMETER})
@Retention(RUNTIME)
public @interface MaxUtf8Bytes {

	int value();

	String message() default "może mieć najwyżej {value} bajtów (litery takie jak ą czy ę zajmują po 2)";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
