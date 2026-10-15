package com.doumiot;

import com.doumiot.config.ConfigurationArchive;
import com.doumiot.config.LocalMasterKey;
import com.doumiot.sshconsole.service.SshScriptScheduleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationArchiveTest {
    @TempDir Path temporary;

    @Test void encryptedBackupsRejectWrongPasswordAndTampering() {
        byte[] data = "凭据与配置".getBytes(StandardCharsets.UTF_8);
        ConfigurationArchive.Archive archive = ConfigurationArchive.encrypt(data, "test-password");
        assertArrayEquals(data, ConfigurationArchive.decrypt(archive, "test-password"));
        assertThrows(IllegalArgumentException.class, () -> ConfigurationArchive.decrypt(archive, "wrong-password"));
        archive.setCiphertext("AAAA" + archive.getCiphertext().substring(4));
        assertThrows(IllegalArgumentException.class, () -> ConfigurationArchive.decrypt(archive, "test-password"));
    }

    @Test void masterKeySurvivesRestart() {
        String first = LocalMasterKey.loadOrCreate(temporary.toString());
        assertEquals(32, java.util.Base64.getDecoder().decode(first).length);
        assertEquals(first, LocalMasterKey.loadOrCreate(temporary.toString()));
    }

    @Test void plaintextArchivesNeedNoPasswordAndRejectMixedOrInvalidFormats() {
        byte[] data="{\"accounts\":[{\"password\":\"明文密码\"}]}".getBytes(StandardCharsets.UTF_8);
        ConfigurationArchive.Archive archive=ConfigurationArchive.plain(data);
        assertEquals("NONE",archive.getAlgorithm());assertNull(archive.getCiphertext());
        assertArrayEquals(data,ConfigurationArchive.decrypt(archive,null));
        archive.setCiphertext("mixed-format");
        assertThrows(IllegalArgumentException.class,()->ConfigurationArchive.decrypt(archive,null));
        archive.setCiphertext(null);archive.setVersion(99);
        assertThrows(IllegalArgumentException.class,()->ConfigurationArchive.decrypt(archive,null));
    }


    @Test void cronAcceptsFiveAndSixFieldsAndValidatesTimezoneAndDates() {
        assertEquals("0 0 9 * * *", SshScriptScheduleService.normalizeCron("0 9 * * *"));
        List<String> dates = SshScriptScheduleService.preview("0 0 9 * * *", "Asia/Shanghai");
        assertEquals(5, dates.size());
        assertEquals(9, OffsetDateTime.parse(dates.get(0)).getHour());
        assertEquals(8 * 3600, OffsetDateTime.parse(dates.get(0)).getOffset().getTotalSeconds());
        assertThrows(RuntimeException.class, () -> SshScriptScheduleService.preview("invalid", "Asia/Shanghai"));
        assertThrows(RuntimeException.class, () -> SshScriptScheduleService.preview("0 9 * * *", "invalid-zone"));
        assertThrows(RuntimeException.class, () -> SshScriptScheduleService.preview("0 0 0 31 2 *", "UTC"));
    }
}
