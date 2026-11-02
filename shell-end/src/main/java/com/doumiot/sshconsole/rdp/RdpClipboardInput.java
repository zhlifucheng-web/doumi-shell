package com.doumiot.sshconsole.rdp;

import org.apache.guacamole.GuacamoleClientException;
import org.apache.guacamole.protocol.GuacamoleInstruction;
import java.util.Base64;
import java.util.List;

/** Only one bounded text clipboard stream; file/pipe streams remain forbidden. */
final class RdpClipboardInput {
    private String stream;
    private int bytes;

    boolean accept(GuacamoleInstruction instruction) throws GuacamoleClientException {
        String opcode=instruction.getOpcode();
        if (!"clipboard".equals(opcode) && !"blob".equals(opcode) && !"end".equals(opcode)) return false;
        List<String> args=instruction.getArgs();
        if ("clipboard".equals(opcode)) {
            if (stream!=null || args.size()!=2 || !args.get(0).matches("[0-9]{1,5}") || !"text/plain".equals(args.get(1))) throw invalid();
            stream=args.get(0);bytes=0;
        } else {
            if (stream==null || args.isEmpty() || !stream.equals(args.get(0))) throw invalid();
            if ("blob".equals(opcode)) {
                if (args.size()!=2 || args.get(1).length()>8192) throw invalid();
                int length;
                try {length=Base64.getDecoder().decode(args.get(1)).length;}
                catch (IllegalArgumentException error) {throw invalid();}
                if (length>262143-bytes) throw invalid(); // guacd adds a terminating NUL.
                bytes+=length;
            } else {
                if (args.size()!=1) throw invalid();
                stream=null;bytes=0;
            }
        }
        return true;
    }
    private static GuacamoleClientException invalid() {return new GuacamoleClientException("不支持的剪贴板输入或文本过长");}
}
