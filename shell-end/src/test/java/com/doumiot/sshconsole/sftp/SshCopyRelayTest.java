package com.doumiot.sshconsole.sftp;

import net.schmizz.concurrent.Promise;
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.common.LoggerFactory;
import net.schmizz.sshj.sftp.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SshCopyRelayTest {
    static class FileHandle extends RemoteFile {
        final byte[] content;
        final ByteArrayOutputStream written=new ByteArrayOutputStream();
        boolean closed;
        FileHandle(byte[] content) {super(engine(),"/file",new byte[0]);this.content=content;}
        static SFTPEngine engine(){return mock(SFTPEngine.class,call->"getLoggerFactory".equals(call.getMethod().getName())?LoggerFactory.DEFAULT:RETURNS_DEFAULTS.answer(call));}
        @Override public int read(long offset,byte[] buffer,int start,int count) {
            if(offset>=content.length)return -1;
            int take=Math.min(count,content.length-(int)offset);System.arraycopy(content,(int)offset,buffer,start,take);return take;
        }
        @Override protected Promise<Response,SFTPException> asyncWrite(long offset,byte[] bytes,int start,int size) throws IOException {
            assertEquals(written.size(),offset);written.write(bytes,start,size);
            Buffer<?> packet=new Buffer<>();packet.putByte((byte)101).putUInt32(1).putUInt32(0);
            Promise<Response,SFTPException> ack=new Promise<>("write",SFTPException.chainer,LoggerFactory.DEFAULT);
            ack.deliver(new Response(new Buffer(packet),3));return ack;
        }
        @Override public void close(){closed=true;}
    }
    void relay(byte[] content,long expected,java.util.function.LongConsumer progress,FileHandle input,FileHandle output) throws Exception {
        SFTPClient source=mock(SFTPClient.class),target=mock(SFTPClient.class);
        when(source.lstat("/source")).thenReturn(new FileAttributes.Builder().withType(FileMode.Type.REGULAR).withSize(expected).build());
        when(source.open("/source")).thenReturn(input);
        when(target.open(eq("/temporary"),anySet())).thenReturn(output);
        SshCopyEngine.relay(source,target,"/source","/temporary",expected,progress);
    }
    @Test void streamsBinaryContentAcrossMultipleBuffersAndClosesBothHandles() throws Exception {
        byte[] content=new byte[200003];new Random(42).nextBytes(content);
        FileHandle input=new FileHandle(content),output=new FileHandle(new byte[0]);List<Long> progress=new ArrayList<>();
        relay(content,content.length,progress::add,input,output);
        assertArrayEquals(content,output.written.toByteArray());assertTrue(input.closed);assertTrue(output.closed);
        assertEquals(Long.valueOf(content.length),progress.get(progress.size()-1));assertTrue(progress.size()>1);
    }
    @Test void truncatedSourceAndCancellationReleaseBothHandles() throws Exception {
        FileHandle input=new FileHandle(new byte[]{1,2,3}),output=new FileHandle(new byte[0]);
        assertThrows(IOException.class,()->relay(input.content,5,n->{},input,output));
        assertTrue(input.closed);assertTrue(output.closed);
        FileHandle cancelledInput=new FileHandle(new byte[100000]),cancelledOutput=new FileHandle(new byte[0]);
        assertThrows(CancellationException.class,()->relay(cancelledInput.content,100000,n->{throw new CancellationException();},cancelledInput,cancelledOutput));
        assertTrue(cancelledInput.closed);assertTrue(cancelledOutput.closed);
        assertTrue(cancelledOutput.written.size()<100000);
    }
}
