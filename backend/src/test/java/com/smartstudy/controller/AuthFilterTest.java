package com.smartstudy.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bearer-header parsing, tested directly (the HTTP server trims some whitespace before the filter sees it). */
class AuthFilterTest {

    private static final String TOKEN = "abcDEF123_-abcDEF123_-abcDEF123_-abcDEF12345";

    @Test
    void wellFormedHeaderYieldsTheToken() {
        assertEquals(TOKEN, AuthFilter.extractBearerToken("Bearer " + TOKEN).orElseThrow());
        assertEquals(TOKEN, AuthFilter.extractBearerToken("bearer " + TOKEN).orElseThrow());
        assertEquals(TOKEN, AuthFilter.extractBearerToken("BEARER " + TOKEN).orElseThrow());
    }

    @Test
    void anythingElseYieldsNothing() {
        String[] bad = {
                null, "", " ", "Bearer", "Bearer ", "Bearer  " + TOKEN, " Bearer " + TOKEN,
                "Bearer " + TOKEN + " ", "Bearer " + TOKEN + "\t", "Bearer " + TOKEN + "\n", "Bearer " + TOKEN + "\r\n",
                "Bearer " + TOKEN + " extra", "Bearer a b", "Bearer short", "Basic " + TOKEN, "Token " + TOKEN,
                TOKEN, "Bearer " + "A".repeat(129), "Bearer " + TOKEN + "!", "Bearer " + TOKEN.replace('a', '/'),
                "Bearer\t" + TOKEN,
        };
        for (String header : bad) {
            assertTrue(AuthFilter.extractBearerToken(header).isEmpty(),
                    "should reject: " + (header == null ? "null" : header.replace(TOKEN, "<token>")));
        }
    }
}
