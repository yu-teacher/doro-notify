package com.doro.notify.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서버 준비 스크립트가 쓰는 VAPID 키 생성(scripts/lib/vapid.sh)이 이 서비스가 읽는 형식과 맞는지 확인한다: 만든 쌍이 서로 짝이고 서명할 수 있어야 한다.
 * openssl·python3·bash 가 없는 환경에서는 건너뛴다. 키는 시험 안에서만 쓰고 어디에도 쓰지 않는다.
 */
class VapidKeyScriptTest {

    @Test
    @DisplayName("scripts/lib/vapid.sh 가 만든 키 쌍은 VapidSigner 가 짝으로 인정하고 서명한다")
    void generatedKeysLoad() throws Exception {
        Path script = Path.of("scripts/lib/vapid.sh").toAbsolutePath();
        Assumptions.assumeTrue(script.toFile().exists());
        Process process = new ProcessBuilder("bash", "-c", "source \"$0\" && vapid_generate && printf '%s\\n%s\\n' \"$VAPID_PUBLIC\" \"$VAPID_PRIVATE\"", script.toString())
                .redirectErrorStream(false).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Assumptions.assumeTrue(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0, "openssl/python3 가 없어 건너뜀");

        List<String> lines = out.lines().toList();
        assertThat(lines).hasSize(2);
        PushProperties properties = new PushProperties(lines.get(0), lines.get(1), "mailto:ops@example.test", List.of(), true, java.time.Duration.ofSeconds(5), 10, 5, 2);
        VapidSigner signer = VapidSigner.from(properties, Clock.systemUTC());
        assertThat(signer.enabled()).isTrue();
        assertThat(signer.authorization("https://fcm.googleapis.com")).startsWith("vapid t=");
        assertThat(java.util.Base64.getUrlDecoder().decode(lines.get(0))).hasSize(65);
        assertThat(java.util.Base64.getUrlDecoder().decode(lines.get(1))).hasSize(32);
        assertThat(lines.get(0)).doesNotContain("=").doesNotContain("+").doesNotContain("/");
    }
}
