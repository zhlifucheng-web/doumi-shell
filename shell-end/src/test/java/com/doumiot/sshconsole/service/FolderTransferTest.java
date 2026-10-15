package com.doumiot.sshconsole.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class FolderTransferTest {
    @TempDir Path temp;
    private byte[] zip(String... names) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream out=new ZipOutputStream(bytes)) {
            for(String name:names){out.putNextEntry(new ZipEntry(name));if(!name.endsWith("/"))out.write("content 中文".getBytes(StandardCharsets.UTF_8));out.closeEntry();}
        }
        return bytes.toByteArray();
    }
    @Test void restoresUnicodeNestedEmptyFilesAndDirectories() throws Exception {
        byte[] zip=zip("资料/","资料/空目录/","资料/sub/file.txt");
        assertEquals(3,SshFolderTransferService.extract(new ByteArrayInputStream(zip),temp,1024).size());
        assertTrue(Files.isDirectory(temp.resolve("资料/空目录")));
        assertEquals("content 中文",Files.readString(temp.resolve("资料/sub/file.txt")));
    }
    @Test void rejectsTraversalWindowsDriveAndMixedSeparators() throws Exception {
        for(String name:new String[]{"../outside","/absolute","C:/outside","root/../../outside","root\\..\\outside","root//file","root/.. /outside","root/NUL.txt","root/abc."}) {
            byte[] zip=zip(name);
            assertThrows(IOException.class,()->SshFolderTransferService.extract(new ByteArrayInputStream(zip),temp,1024),name);
        }
    }
    @Test void limitsExpandedContentAndRejectsNonZip() throws Exception {
        assertThrows(IOException.class,()->SshFolderTransferService.extract(new ByteArrayInputStream(zip("root/file")),temp,2));
        assertThrows(IOException.class,()->SshFolderTransferService.extract(new ByteArrayInputStream(new byte[]{1,2,3}),temp,1024));
    }
    @Test void rejectsDeepPathsAndEmptyComponents() {
        assertThrows(IOException.class,()->SshFolderTransferService.validateRelative("x/".repeat(66)+"file"));
        assertThrows(IOException.class,()->SshFolderTransferService.validateRelative("root/./file"));
        assertDoesNotThrow(()->SshFolderTransferService.validateRelative("root/a ' $ &中文/file.txt"));
    }
}
