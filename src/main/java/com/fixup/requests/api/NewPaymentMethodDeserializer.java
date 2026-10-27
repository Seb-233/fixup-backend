package com.fixup.requests.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.io.IOException;

/** Missing creation property defaults to TRANSFER; explicit null and CASH remain invalid. */
public class NewPaymentMethodDeserializer extends JsonDeserializer<PaymentMethod> {
    @Override public PaymentMethod getAbsentValue(DeserializationContext context) { return PaymentMethod.TRANSFER; }
    @Override public PaymentMethod getNullValue(DeserializationContext context) throws JsonMappingException {
        throw context.weirdStringException("null", PaymentMethod.class, "Explicit null is not a payment method");
    }
    @Override public PaymentMethod deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_STRING) || !"TRANSFER".equals(parser.getText()))
            throw context.weirdStringException(parser.getText(), PaymentMethod.class, "New repairs accept only TRANSFER");
        return PaymentMethod.TRANSFER;
    }
}
