package com.orderfulfillment.monolith.order.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** docs/openapi/order-service.yaml's CreateOrderRequest / CreateOrderItem constraints. */
class CreateOrderRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void validRequestHasNoViolations() {
        CreateOrderRequest request = new CreateOrderRequest("demo-customer", List.of(new CreateOrderItem("SKU-001", 2)));
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void emptyItemsIsRejected() {
        CreateOrderRequest request = new CreateOrderRequest("demo-customer", List.of());
        Set<ConstraintViolation<CreateOrderRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
    }

    @Test
    void blankCustomerIdIsRejected() {
        CreateOrderRequest request = new CreateOrderRequest("", List.of(new CreateOrderItem("SKU-001", 1)));
        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    void skuNotMatchingPatternIsRejected() {
        CreateOrderItem item = new CreateOrderItem("NOT-A-SKU", 1);
        assertThat(validator.validate(item)).isNotEmpty();
    }

    @Test
    void quantityBelowOneIsRejected() {
        CreateOrderItem item = new CreateOrderItem("SKU-001", 0);
        assertThat(validator.validate(item)).isNotEmpty();
    }

    @Test
    void quantityAboveOneHundredIsRejected() {
        CreateOrderItem item = new CreateOrderItem("SKU-001", 101);
        assertThat(validator.validate(item)).isNotEmpty();
    }
}
