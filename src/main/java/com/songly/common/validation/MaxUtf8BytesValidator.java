package com.songly.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;

class MaxUtf8BytesValidator implements ConstraintValidator<MaxUtf8Bytes, String> {

	private int max;

	@Override
	public void initialize(MaxUtf8Bytes annotation) {
		max = annotation.value();
	}

	/**
	 * {@code null} przepuszczamy, jak {@code @Size} — brak wartości łapie
	 * {@code @NotBlank}.
	 */
	@Override
	public boolean isValid(String value, ConstraintValidatorContext context) {
		return value == null || value.getBytes(StandardCharsets.UTF_8).length <= max;
	}
}
