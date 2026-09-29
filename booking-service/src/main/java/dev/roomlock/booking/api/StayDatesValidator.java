package dev.roomlock.booking.api;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

class StayDatesValidator implements ConstraintValidator<StayDates, HoldBookingRequest> {

  private static final String FIELD = "checkOut";

  @Override
  public boolean isValid(HoldBookingRequest request, ConstraintValidatorContext context) {
    if (request.checkIn() == null || request.checkOut() == null) {
      return true; // @NotNull reports a missing date
    }
    if (request.checkOut().isAfter(request.checkIn())) {
      return true;
    }
    context.disableDefaultConstraintViolation();
    context
        .buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
        .addPropertyNode(FIELD)
        .addConstraintViolation();
    return false;
  }
}
