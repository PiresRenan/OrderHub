package io.github.piresrenan.orderhub.administration.web;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

@Documented
@Target({FIELD, PARAMETER, RECORD_COMPONENT, ANNOTATION_TYPE})
@Retention(RUNTIME)
@Constraint(validatedBy = NormalizedAdministrativeName.Validator.class)
public @interface NormalizedAdministrativeName {

    String message() default "must contain between 1 and 120 normalized Unicode code points";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    final class Validator implements ConstraintValidator<NormalizedAdministrativeName, String> {

        private static final int MAX_CODE_POINTS = 120;

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) {
                return false;
            }

            var normalized = value.strip();
            return !normalized.isEmpty()
                    && normalized.codePointCount(0, normalized.length()) <= MAX_CODE_POINTS;
        }
    }
}
