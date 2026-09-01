package io.github.piresrenan.orderhub.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProductVariantAttributeTest {

    @Test
    void preservesValidKeyAndValueExactly() {

        var attribute = ProductVariantAttribute.of("screen.size", "15.6 inch");

        assertThat(attribute.key()).isEqualTo("screen.size");
        assertThat(attribute.value()).isEqualTo("15.6 inch");
    }

    @Test
    void acceptsSupportedKeyCharacters() {

        assertThat(ProductVariantAttribute.of("display_size-v2", "large").key())
                .isEqualTo("display_size-v2");
    }

    @Test
    void rejectsInvalidKeys() {

        assertThatThrownBy(() -> ProductVariantAttribute.of("1color", "black"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Variant attribute key has invalid format");

        assertThatThrownBy(() -> ProductVariantAttribute.of("color space", "black"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Variant attribute key has invalid format");
    }

    @Test
    void rejectsOversizedKey() {

        var key = "a" + "x".repeat(64);

        assertThatThrownBy(() -> ProductVariantAttribute.of(key, "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Variant attribute key must not exceed 64 characters");
    }

    @Test
    void rejectsBlankOrSurroundedAttributeValue() {

        assertThatThrownBy(() -> ProductVariantAttribute.of("color", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Variant attribute value must not be blank");

        assertThatThrownBy(() -> ProductVariantAttribute.of("color", " black "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Variant attribute value must not contain surrounding whitespace");
    }

    @Test
    void rejectsControlCharactersAndOversizedValue() {

        assertThatThrownBy(() -> ProductVariantAttribute.of("color", "black\nblue"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Variant attribute value must not contain control characters");

        assertThatThrownBy(() -> ProductVariantAttribute.of("description", "x".repeat(257)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Variant attribute value must not exceed 256 characters");
    }
}