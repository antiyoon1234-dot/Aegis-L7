package com.l7defense.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GeoIpManagerTest {
    @Test void apiFailureIsNotARestrictedCountry() {
        assertTrue(GeoIpManager.parseResponse("{\"status\":\"fail\",\"message\":\"private range\"}").apiFailed());
    }
    @Test void whitespaceAndFieldOrderAreSupported() {
        var result = GeoIpManager.parseResponse("{ \"hosting\": false, \"countryCode\": \"KR\", \"status\": \"success\", \"proxy\": true }");
        assertFalse(result.apiFailed());
        assertEquals("KR", result.countryCode());
        assertTrue(result.isVpn());
    }
    @Test void malformedAndMissingFieldsFailOpenWithoutPretendingToBeSuccessful() {
        for (String response : new String[] {"", "null", "[]", "<html>error</html>", "{\"status\":\"success\"}",
                "{\"status\":\"success\",\"countryCode\":\"KR\",\"proxy\":\"true\",\"hosting\":false}"}) {
            assertTrue(GeoIpManager.parseResponse(response).apiFailed(), response);
        }
    }
}
