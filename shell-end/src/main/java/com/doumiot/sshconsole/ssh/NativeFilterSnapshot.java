package com.doumiot.sshconsole.ssh;

import java.util.Arrays;

/** Bounded SFTP preflight for the native LF-terminated historical subset. */
public final class NativeFilterSnapshot {
    public final long size,mtime;
    public final byte[] prefix,boundary,seed;
    private NativeFilterSnapshot(long size,long mtime,byte[] prefix,byte[] boundary,byte[] seed) {
        this.size=size;this.mtime=mtime;this.prefix=prefix.clone();this.boundary=boundary.clone();this.seed=seed;
    }
    public static NativeFilterSnapshot create(long size,long mtime,byte[] prefix,byte[] boundary,byte[] suffix) {
        if(size<0 || suffix.length>65536 || suffix.length>size || (size>0 && (suffix.length==0 || suffix[suffix.length-1]!='\n')))return null;
        int count=0,start=0;
        for(int i=suffix.length-1;i>=0;i--)if(suffix[i]=='\n' && ++count==3){start=i+1;break;}
        if(count<3 && suffix.length<size)return null;
        return new NativeFilterSnapshot(size,mtime,prefix,boundary,Arrays.copyOfRange(suffix,start,suffix.length));
    }
}
