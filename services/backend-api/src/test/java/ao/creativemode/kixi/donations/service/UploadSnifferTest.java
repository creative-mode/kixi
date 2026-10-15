package ao.creativemode.kixi.donations.service;

import static ao.creativemode.kixi.donations.service.DonationFixtures.executable;
import static ao.creativemode.kixi.donations.service.DonationFixtures.jpeg;
import static ao.creativemode.kixi.donations.service.DonationFixtures.pdf;
import static ao.creativemode.kixi.donations.service.DonationFixtures.png;
import static ao.creativemode.kixi.donations.service.DonationFixtures.pngClaiming;
import static ao.creativemode.kixi.donations.service.DonationFixtures.webp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ao.creativemode.kixi.shared.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Issue #252: what an upload is comes from its bytes. The name and the {@code Content-Type} are
 * written by the caller and are not looked at here at all.
 */
class UploadSnifferTest {

    @Test
    void theFourAcceptedFormatsAreRecognisedByTheirFirstBytes() {
        assertThat(UploadSniffer.detect(jpeg(8, 8, 3))).contains(UploadSniffer.Kind.JPEG);
        assertThat(UploadSniffer.detect(png(8, 8, 3))).contains(UploadSniffer.Kind.PNG);
        assertThat(UploadSniffer.detect(webp(64, 64))).contains(UploadSniffer.Kind.WEBP);
        assertThat(UploadSniffer.detect(pdf(1, null))).contains(UploadSniffer.Kind.PDF);
    }

    @Test
    void anExecutableRenamedToPngIsNotAnImage() {
        assertThat(UploadSniffer.detect(executable())).isEmpty();
    }

    @Test
    void textAndEmptyAndTooShortBytesAreNothing() {
        assertThat(UploadSniffer.detect("<html><script>alert(1)</script></html>".getBytes())).isEmpty();
        assertThat(UploadSniffer.detect(new byte[0])).isEmpty();
        assertThat(UploadSniffer.detect(new byte[]{(byte) 0xFF, (byte) 0xD8})).isEmpty();
        assertThat(UploadSniffer.detect(null)).isEmpty();
    }

    @Test
    void aWebpIsNotMistakenForAnyOtherRiffFile() {
        byte[] wave = webp(8, 8);
        wave[8] = 'W';
        wave[9] = 'A';
        wave[10] = 'V';
        wave[11] = 'E';
        assertThat(UploadSniffer.detect(wave)).isEmpty();
    }

    @Test
    void aSmallImageThatInflatesIntoAHugeOneIsRefusedWithoutDecodingIt() {
        // 60 000 x 60 000 = 3.6 billion pixels declared in a file of a few dozen bytes.
        byte[] bomb = pngClaiming(60_000, 60_000);

        assertThatThrownBy(() -> UploadSniffer.requireSafe(UploadSniffer.Kind.PNG, bomb, 100_000_000L, 40))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void aWebpThatDeclaresMoreThanTheCeilingIsRefused() {
        assertThatThrownBy(() -> UploadSniffer.requireSafe(UploadSniffer.Kind.WEBP, webp(16_000, 16_000), 100_000_000L, 40))
                .isInstanceOf(ApiException.class);
        assertThat(UploadSniffer.requireSafe(UploadSniffer.Kind.WEBP, webp(4000, 3000), 100_000_000L, 40)).isNull();
    }

    @Test
    void aNormalPhotoPasses() {
        assertThat(UploadSniffer.requireSafe(UploadSniffer.Kind.JPEG, jpeg(1200, 1600, 120), 100_000_000L, 40)).isNull();
    }

    @Test
    void aPdfWithScriptOrAnEmbeddedFileIsRefusedAs415() {
        for (String active : new String[]{"<< /S /JavaScript /JS (app.alert(1)) >>", "<< /S /Launch /F (cmd.exe) >>",
                "<< /Type /EmbeddedFile >>", "<< /Subtype /RichMedia >>"}) {
            assertThatThrownBy(() -> UploadSniffer.requireSafe(UploadSniffer.Kind.PDF, pdf(1, active), 100_000_000L, 40))
                    .as(active)
                    .isInstanceOfSatisfying(ApiException.class,
                            error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
        }
    }

    @Test
    void aPdfWithAnOpenActionToAPageIsNotTakenForScript() {
        // /OpenAction is how many viewers open on page one. It is not an action that runs anything.
        assertThat(UploadSniffer.requireSafe(UploadSniffer.Kind.PDF, pdf(2, "<< /OpenAction [1 0 R /Fit] >>"), 100_000_000L, 40))
                .isEqualTo(2);
    }

    @Test
    void thePageCountIsReadAndCapped() {
        assertThat(UploadSniffer.requireSafe(UploadSniffer.Kind.PDF, pdf(3, null), 100_000_000L, 40)).isEqualTo(3);
        assertThatThrownBy(() -> UploadSniffer.requireSafe(UploadSniffer.Kind.PDF, pdf(41, null), 100_000_000L, 40))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
