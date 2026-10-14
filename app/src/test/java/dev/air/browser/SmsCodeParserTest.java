package dev.air.browser;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public final class SmsCodeParserTest {
    @Test public void koreanCodePreservesLeadingZeroesAndIgnoresTelephone() {
        var codes = SmsCodeParser.parse("[AIR] 인증번호 [001234]를 입력하세요. 고객센터 010-1234-5678", "example.com");
        assertEquals(List.of("001234"), codes.values());
        assertTrue(codes.unambiguous());
    }
    @Test public void alphanumericAndEnglishMessagesWork() {
        assertEquals(List.of("A7b9X2"), SmsCodeParser.parse("Your verification code is A7b9X2.", "example.com").values());
        assertTrue(SmsCodeParser.parse("123456 is your security code.", "example.com").unambiguous());
        assertEquals(List.of("345678"), SmsCodeParser.parse("인증 번호는 345678입니다.", "example.com").values());
    }
    @Test public void fullWidthDigitsAreNormalized() {
        assertEquals(List.of("001234"), SmsCodeParser.parse("인증번호: ００１２３４", "example.com").values());
    }
    @Test public void ambiguousLabelledCodesRequireAChoice() {
        var codes = SmsCodeParser.parse("인증번호 123456 / 확인코드 654321", "example.com");
        assertEquals(List.of("123456", "654321"), codes.values());
        assertFalse(codes.unambiguous());
    }
    @Test public void repeatedCodeIsOneCandidate() {
        var codes = SmsCodeParser.parse("OTP: 123456. 인증번호 123456", "example.com");
        assertEquals(List.of("123456"), codes.values());
        assertTrue(codes.unambiguous());
    }
    @Test public void unlabelledNumericCodeAlwaysRequiresConfirmation() {
        var codes = SmsCodeParser.parse("[AIR] 123456", "example.com");
        assertEquals(List.of("123456"), codes.values());
        assertFalse(codes.unambiguous());
    }
    @Test public void domainBoundCodeMatchesExactPageHost() {
        assertEquals(List.of("001234"), SmsCodeParser.parse("Your code\n@example.com #001234", "example.com").values());
        assertTrue(SmsCodeParser.parse("@EXAMPLE.COM #001234", "example.com").unambiguous());
        assertTrue(SmsCodeParser.parse("인증번호 001234\n@other.example #001234", "example.com").values().isEmpty());
        assertTrue(SmsCodeParser.parse("@www.example.com #001234", "example.com").values().isEmpty());
        assertTrue(SmsCodeParser.parse("@example.com #001234 @frame.example", "example.com").values().isEmpty());
        assertTrue(SmsCodeParser.parse("인증번호 001234\n@other.example #001234 trailing text", "example.com").values().isEmpty());
    }
    @Test public void multipleDomainBoundLinesAreRejected() {
        assertTrue(SmsCodeParser.parse("@example.com #123456\n@example.com #654321", "example.com").values().isEmpty());
    }
    @Test public void phoneNumbersDatesAmountsAndUrlsAreNotSuggested() {
        assertTrue(SmsCodeParser.parse("010-1234-5678 / 2026-10-14 / 10000원 / 12,345 / https://example.com/123456", "example.com")
                .values().isEmpty());
        assertTrue(SmsCodeParser.parse("OTP: 12345678901", "example.com").values().isEmpty());
    }
    @Test public void unrelatedWordsAndExcessiveCandidatesAreRejected() {
        assertTrue(SmsCodeParser.parse("AIR2026 is ready. Welcome", "example.com").values().isEmpty());
        assertTrue(SmsCodeParser.parse("1234 2345 3456 4567 5678", "example.com").values().isEmpty());
        assertFalse(SmsCodeParser.valid("password"));
        assertFalse(SmsCodeParser.valid("123"));
        assertFalse(SmsCodeParser.valid("12345678901"));
    }
    @Test public void emptyAndOversizedMessagesAreRejected() {
        assertTrue(SmsCodeParser.parse(null, "example.com").values().isEmpty());
        assertTrue(SmsCodeParser.parse("인증번호: 123456", null).values().isEmpty());
        assertTrue(SmsCodeParser.parse("x".repeat(8193), "example.com").values().isEmpty());
    }
}
