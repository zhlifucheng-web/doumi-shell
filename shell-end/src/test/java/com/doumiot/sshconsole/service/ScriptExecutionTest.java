package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.config.SshConsoleProperties;
import com.doumiot.sshconsole.entity.*;
import com.doumiot.sshconsole.mapper.*;
import com.doumiot.sshconsole.service.*;
import com.doumiot.sshconsole.ssh.*;
import com.doumiot.sshconsole.crypto.CredentialCipher;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ScriptExecutionTest {
    @TempDir Path directory;
    SshScriptExecutionService service;
    SshConsoleProperties properties;
    JdbcTemplate jdbc;
    SSHClient client;
    SshClientFactory clients;
    SshAccountService accounts;
    SshAccount account;
    Session main,kill;
    Session.Command command,killCommand;
    AtomicBoolean open;
    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:sqlite:"+directory.resolve("test.db"));
        new ResourceDatabasePopulator(new ClassPathResource("sql/sqlite_schema.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);
        service=new SshScriptExecutionService();
        SshScriptMapper scripts=mock(SshScriptMapper.class);SshHostMapper hosts=mock(SshHostMapper.class);
        accounts=mock(SshAccountService.class);clients=mock(SshClientFactory.class);
        SshScript script=new SshScript();script.setId(1L);script.setName("test");script.setContent("echo 中文");script.setInterpreter("bash");
        SshHost host=new SshHost();host.setId(2L);host.setName("host");host.setHost("127.0.0.1");host.setPort(22);
        account=new SshAccount();account.setId(3L);account.setHostId(2L);account.setUsername("test");account.setAuthType("PASSWORD");
        when(scripts.selectById(1L)).thenReturn(script);when(hosts.selectById(2L)).thenReturn(host);when(accounts.requireAccount(3L)).thenReturn(account);
        when(accounts.loadSecret(account)).thenReturn(new CredentialCipher.SshSecretPayload());
        client=mock(SSHClient.class);main=mock(Session.class);kill=mock(Session.class);command=mock(Session.Command.class);killCommand=mock(Session.Command.class);
        when(clients.connectAndAuth(anyString(),anyInt(),anyString(),anyString(),any())).thenReturn(client);
        when(client.isConnected()).thenReturn(true);when(client.startSession()).thenReturn(main,kill);
        when(main.exec(anyString())).thenReturn(command);when(kill.exec(anyString())).thenAnswer(call->{open.set(false);return killCommand;});
        open=new AtomicBoolean(true);when(command.isOpen()).thenAnswer(call->open.get());
        doAnswer(call->{Thread.sleep(30);return null;}).when(command).join(anyLong(),any(TimeUnit.class));
        when(command.getInputStream()).thenReturn(new ByteArrayInputStream("正在执行\n".getBytes(StandardCharsets.UTF_8)));
        when(command.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));when(command.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(killCommand.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));when(killCommand.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));when(killCommand.getExitStatus()).thenReturn(0);
        properties=new SshConsoleProperties();properties.setScriptRunTimeoutSec(30);
        ReflectionTestUtils.setField(service,"jdbc",jdbc);ReflectionTestUtils.setField(service,"scripts",scripts);ReflectionTestUtils.setField(service,"hosts",hosts);
        ReflectionTestUtils.setField(service,"accounts",accounts);ReflectionTestUtils.setField(service,"clients",clients);ReflectionTestUtils.setField(service,"properties",properties);
    }
    @AfterEach void shutdown(){service.shutdown();}
    Map<String,Object> await(String id) throws Exception {
        long deadline=System.currentTimeMillis()+5000;Map<String,Object> row;
        do {row=service.detail(id);if(!Arrays.asList("QUEUED","RUNNING","CANCELLING").contains(row.get("status")))return row;Thread.sleep(30);}while(System.currentTimeMillis()<deadline);
        fail("Execution did not finish");return row;
    }
    @Test void stopSendsRemoteProcessGroupSignalsAndPreservesOutput() throws Exception {
        String id=(String)service.start(1,3,"MANUAL").get("id");
        long deadline=System.currentTimeMillis()+3000;
        while(!service.detail(id).get("stdout").toString().contains("正在执行") && System.currentTimeMillis()<deadline)Thread.sleep(30);
        service.stop(id);Map<String,Object> row=await(id);
        assertEquals("CANCELLED",row.get("status"));assertTrue(row.get("stdout").toString().contains("正在执行"));assertNotNull(row.get("finished_at"));
        verify(main).exec(contains("exec setsid"));verify(kill).exec(contains("kill -TERM"));verify(kill).exec(contains("-KILL"));
        verify(main).close();verify(kill).close();
    }
    @Test void timeoutsAlsoTerminateRemoteProcessGroup() throws Exception {
        properties.setScriptRunTimeoutSec(1);
        String id=(String)service.start(1,3,"MANUAL").get("id");
        assertEquals("TIMED_OUT",await(id).get("status"));verify(kill).exec(contains("kill -TERM"));
    }
    @Test void successAndRestartRecoveryArePersisted() throws Exception {
        open.set(false);when(command.getExitStatus()).thenReturn(0);
        String id=(String)service.start(1,3,"MANUAL").get("id");
        Map<String,Object> row=await(id);assertEquals("SUCCESS",row.get("status"));assertEquals(0,row.get("exit_status"));
        jdbc.update("UPDATE ssh_script_execution SET status='RUNNING' WHERE id=?",id);service.recover();
        assertEquals("INTERRUPTED",service.detail(id).get("status"));
    }
    @Test void cronExecutionEstablishesItsOwnSshConnectionWithoutInteractiveSession() throws Exception {
        open.set(false);when(command.getExitStatus()).thenReturn(0);
        String id=(String)service.start(1,3,"CRON").get("id");
        assertEquals("SUCCESS",await(id).get("status"));
        verify(client).startSession();
        verify(clients).connectAndAuth(eq("127.0.0.1"),eq(22),eq("test"),eq("PASSWORD"),any(CredentialCipher.SshSecretPayload.class));
        verify(accounts).loadSecret(account);
    }
    @Test void missingRemoteExitStatusIsFailedWithExplicitReason() throws Exception {
        open.set(false);when(command.getExitStatus()).thenReturn(null);
        String id=(String)service.start(1,3,"MANUAL").get("id");Map<String,Object> row=await(id);
        assertEquals("FAILED",row.get("status"));assertNull(row.get("exit_status"));
        assertTrue(row.get("error").toString().contains("未收到远端退出码"));
    }
    @Test void stdinWriteFailureCannotBeReportedAsSuccess() throws Exception {
        open.set(false);when(command.getExitStatus()).thenReturn(0);
        when(command.getOutputStream()).thenReturn(new OutputStream(){
            @Override public void write(int value) throws IOException {throw new IOException("broken pipe");}
            @Override public void write(byte[] data,int offset,int length) throws IOException {throw new IOException("broken pipe");}
        });
        String id=(String)service.start(1,3,"MANUAL").get("id");Map<String,Object> row=await(id);
        assertEquals("FAILED",row.get("status"));assertEquals(0,row.get("exit_status"));
        assertTrue(row.get("error").toString().contains("发送失败"));
    }
    @Test void stdinCloseFailureAfterCompleteWriteDoesNotOverrideRemoteExitStatus() throws Exception {
        open.set(false);when(command.getExitStatus()).thenReturn(0);
        ByteArrayOutputStream written=new ByteArrayOutputStream();
        when(command.getOutputStream()).thenReturn(new OutputStream(){
            @Override public void write(int value) {written.write(value);}
            @Override public void write(byte[] data,int offset,int length) {written.write(data,offset,length);}
            @Override public void close() throws IOException {throw new IOException("Stream closed");}
        });
        String id=(String)service.start(1,3,"MANUAL").get("id");Map<String,Object> row=await(id);
        assertEquals("SUCCESS",row.get("status"));assertEquals(0,row.get("exit_status"));
        assertEquals("echo 中文",written.toString(StandardCharsets.UTF_8.name()));
        assertNull(row.get("error"));
    }
    @Test void queuedExecutionCanBeCancelledImmediatelyWithoutSsh() throws Exception {
        java.util.concurrent.ThreadPoolExecutor workers=(java.util.concurrent.ThreadPoolExecutor)ReflectionTestUtils.getField(service,"workers");
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(4),release=new java.util.concurrent.CountDownLatch(1);
        try {
            for(int i=0;i<4;i++)workers.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            String id=(String)service.start(1,3,"MANUAL").get("id");
            assertEquals("QUEUED",service.detail(id).get("status"));
            assertEquals("CANCELLED",service.stop(id).get("status"));assertTrue(workers.getQueue().isEmpty());
            verifyNoInteractions(client);
        } finally {release.countDown();}
    }
    @Test void excessiveOutputIsDrainedAndMarkedTruncated() throws Exception {
        byte[] data=new byte[2*1024*1024];Arrays.fill(data,(byte)'x');
        ByteArrayInputStream input=new ByteArrayInputStream(data);
        when(command.getInputStream()).thenReturn(input);open.set(false);when(command.getExitStatus()).thenReturn(0);
        String id=(String)service.start(1,3,"MANUAL").get("id");Map<String,Object> row=await(id);
        assertEquals("SUCCESS",row.get("status"));assertEquals(1,row.get("truncated"));
        assertEquals(512*1024,row.get("stdout").toString().length());assertEquals(0,input.available());
    }
}
