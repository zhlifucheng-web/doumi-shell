package com.doumiot.sshconsole.sftp;

import net.schmizz.sshj.sftp.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SshCopyEngineTest {
    final SFTPClient source = mock(SFTPClient.class), target = mock(SFTPClient.class);
    final Map<String, FileAttributes> files = new HashMap<>();
    final List<String> copied = new ArrayList<>();
    final List<Long> progress = new ArrayList<>();
    final SftpStreams.Ownership nobody;
    SshCopyEngineTest() throws Exception {
        nobody = SftpStreams.Ownership.of(null, null);
        files.put("/", dir()); files.put("/dest", dir());
        when(source.canonicalize(anyString())).thenAnswer(i -> i.getArgument(0));
        when(target.canonicalize(anyString())).thenAnswer(i -> i.getArgument(0));
        when(target.lstat(anyString())).thenAnswer(i -> {
            FileAttributes a = files.get(i.getArgument(0));
            if (a == null) throw new SFTPException(Response.StatusCode.NO_SUCH_FILE, "missing");
            return a;
        });
        when(target.stat(anyString())).thenAnswer(i -> files.get(i.getArgument(0)));
        when(target.statExistence(anyString())).thenAnswer(i -> files.get(i.getArgument(0)));
        when(target.size(anyString())).thenAnswer(i -> files.get(i.getArgument(0)).getSize());
        when(target.open(anyString(), anySet(), any(FileAttributes.class))).thenAnswer(i -> {
            files.put(i.getArgument(0), file(0)); return mock(RemoteFile.class);
        });
        doAnswer(i -> {files.put(i.getArgument(0), dir());return null;}).when(target).mkdir(anyString());
        doAnswer(i -> {files.remove(i.getArgument(0));return null;}).when(target).rm(anyString());
        doAnswer(i -> {files.put(i.getArgument(1), files.remove(i.getArgument(0)));return null;})
                .when(target).rename(anyString(), anyString(), anySet());
        doAnswer(i -> {files.put(i.getArgument(1), files.remove(i.getArgument(0)));return null;})
                .when(target).rename(anyString(), anyString());
        when(target.version()).thenReturn(5);
    }
    static FileAttributes dir() {return new FileAttributes.Builder().withType(FileMode.Type.DIRECTORY).withPermissions(0755).withUIDGID(0,0).build();}
    static FileAttributes file(long size) {return new FileAttributes.Builder().withType(FileMode.Type.REGULAR).withSize(size).withPermissions(0644).withUIDGID(0,0).build();}
    RemoteResourceInfo item(String name, FileAttributes a) {return new RemoteResourceInfo(new PathComponents("/src",name,"/"), a);}
    SshCopyEngine.ContentCopier copier = (from, temp, size, report) -> {
        copied.add(from); files.put(temp, file(size)); report.accept(size);
    };
    void execute(SshCopyEngine.Plan plan, SftpStreams.Ownership owner) throws Exception {
        SshCopyEngine.execute(plan, target, owner, copier, (loaded,total,path)->progress.add(loaded));
    }
    @Test void copiesTreeIncludingEmptyDirectoriesAndAppliesOwnerOnlyToNewDirectories() throws Exception {
        when(source.lstat("/src")).thenReturn(dir());
        when(source.ls("/src")).thenReturn(Arrays.asList(item("a.txt",file(3)),item("empty",dir())));
        when(source.ls("/src/empty")).thenReturn(Collections.emptyList());
        files.put("/dest/src",dir());
        execute(SshCopyEngine.plan(source,target,"/src","/dest",false,false),SftpStreams.Ownership.of(1000L,1001L));
        assertEquals(Collections.singletonList("/src/a.txt"),copied);
        assertTrue(files.containsKey("/dest/src/empty"));
        assertEquals(3,files.get("/dest/src/a.txt").getSize());
        assertEquals(Long.valueOf(3),progress.get(progress.size()-1));
        verify(target,never()).setattr(eq("/dest/src"),any());
        verify(target).setattr(eq("/dest/src/empty"),argThat(a->a.getUID()==1000&&a.getGID()==1001));
    }
    @Test void collisionPreflightLeavesAllTargetsUntouched() throws Exception {
        when(source.lstat("/src")).thenReturn(dir());
        when(source.ls("/src")).thenReturn(Arrays.asList(item("new",file(3)),item("old",file(4))));
        files.put("/dest/src",dir()); files.put("/dest/src/old",file(7));
        assertThrows(IOException.class,()->SshCopyEngine.plan(source,target,"/src","/dest",false,false));
        assertEquals(7,files.get("/dest/src/old").getSize());
        assertFalse(files.containsKey("/dest/src/new"));
    }
    @Test void refusesSymlinksAtSourceAndDestinationAncestors() throws Exception {
        FileAttributes link=new FileAttributes.Builder().withType(FileMode.Type.SYMLINK).build();
        when(source.lstat("/src")).thenReturn(link);
        assertThrows(IOException.class,()->SshCopyEngine.plan(source,target,"/src","/dest",true,false));
        when(source.lstat("/src")).thenReturn(file(1));files.put("/dest",link);
        assertThrows(IOException.class,()->SshCopyEngine.plan(source,target,"/src","/dest/sub",true,false));
    }
    @Test void refusesCopyIntoSelfOrDescendantOnSameMachine() throws Exception {
        when(source.lstat("/src")).thenReturn(dir());when(source.ls("/src")).thenReturn(Collections.emptyList());
        files.put("/src",dir());
        assertThrows(IOException.class,()->SshCopyEngine.plan(source,target,"/src","/src/nested",false,true));
    }
    @Test void cancellationRemovesStagingFileAndPreservesOriginal() throws Exception {
        when(source.lstat("/src")).thenReturn(file(3));files.put("/dest/src",file(7));
        SshCopyEngine.Plan plan=SshCopyEngine.plan(source,target,"/src","/dest",true,false);
        copier=(from,temp,size,report)->{files.put(temp,file(1));throw new CancellationException();};
        assertThrows(CancellationException.class,()->execute(plan,nobody));
        assertEquals(7,files.get("/dest/src").getSize());
        assertTrue(files.keySet().stream().noneMatch(p->p.contains(".doumi-copy-")));
    }
    @Test void shortCopyNeverReplacesOriginal() throws Exception {
        when(source.lstat("/src")).thenReturn(file(3));files.put("/dest/src",file(7));
        copier=(from,temp,size,report)->files.put(temp,file(2));
        assertThrows(IOException.class,()->execute(SshCopyEngine.plan(source,target,"/src","/dest",true,false),nobody));
        assertEquals(7,files.get("/dest/src").getSize());
    }
    @Test void fileAppearingAfterPreflightIsNotOverwrittenWithoutPermission() throws Exception {
        when(source.lstat("/src")).thenReturn(file(3));
        SshCopyEngine.Plan plan=SshCopyEngine.plan(source,target,"/src","/dest",false,false);
        copier=(from,temp,size,report)->{files.put(temp,file(3));files.put("/dest/src",file(7));};
        assertThrows(IOException.class,()->execute(plan,nobody));
        assertEquals(7,files.get("/dest/src").getSize());
    }
    @Test void ownershipDenialReportsContentSuccessAndWarning() throws Exception {
        when(source.lstat("/src")).thenReturn(file(0));
        doThrow(new IOException("denied")).when(target).setattr(eq("/dest/src"),any());
        String warning=SshCopyEngine.execute(SshCopyEngine.plan(source,target,"/src","/dest",false,false),target,
                SftpStreams.Ownership.of(1000L,1001L),copier,(l,t,p)->{});
        assertTrue(files.containsKey("/dest/src")); assertNotNull(warning);
    }
    @Test void doesNotDiscardASourceFilenameWithTrailingSpaces() throws Exception {
        when(source.lstat("/a ")).thenReturn(file(1));
        execute(SshCopyEngine.plan(source,target,"/a ","/dest",false,false),nobody);
        assertEquals(Collections.singletonList("/a "),copied);assertTrue(files.containsKey("/dest/a "));
    }
    @Test void directoryOwnerFailureIsVisibleEvenWhenNoFilesWereCopied() throws Exception {
        when(source.lstat("/src")).thenReturn(dir());when(source.ls("/src")).thenReturn(Collections.emptyList());
        doThrow(new IOException("denied")).when(target).setattr(eq("/dest/src"),any());
        String warning=SshCopyEngine.execute(SshCopyEngine.plan(source,target,"/src","/dest",false,false),target,
                SftpStreams.Ownership.of(1000L,1001L),copier,(l,t,p)->{});
        assertTrue(files.containsKey("/dest/src"));assertNotNull(warning);
    }
}
