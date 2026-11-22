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
