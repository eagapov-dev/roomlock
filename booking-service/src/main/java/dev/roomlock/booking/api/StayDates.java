package dev.roomlock.booking.api;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The stay of a request is half-open, so the check-out day has to come after the check-in day. The
 * violation is reported on {@code checkOut}, the field a client can fix (rule V-4 keeps {@link
 * dev.roomlock.booking.domain.DateRange} from ever seeing a wrong pair).
 */
@Documented
@Constraint(validatedBy = StayDatesValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@interface StayDates {

  String message() default "must be after checkIn";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
