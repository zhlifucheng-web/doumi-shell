# -*- coding: utf-8 -*-
# Python 2.7/3; read-only Linux process inspection. Never runs terminal commands.
from __future__ import print_function
import json
import os
import pwd
import signal
import sys
import time

SHELLS = set(('sh', 'bash', 'zsh', 'dash', 'ash', 'ksh', 'ksh93', 'mksh', 'fish', 'csh', 'tcsh'))

class Unavailable(Exception):
    pass

def is_interactive_shell(args):
    if not args or os.path.basename(args[0]).lstrip('-') not in SHELLS:
        return False
    # A script or -c shell is not an interactive directory source.
    for arg in args[1:]:
        if not arg.startswith('-') or arg == '--' or 'c' in arg.lstrip('-') and not arg.startswith('--'):
            return False
        if arg.startswith('--') and arg not in ('--login', '--noprofile', '--norc', '--interactive'):
            return False
    return True

def ancestry(nodes, pid, base):
    chain, seen = [], set()
    while pid in nodes and pid not in seen and len(chain) < 128:
        seen.add(pid)
        chain.append(pid)
        if pid == base:
            return chain
        pid = nodes[pid]['ppid']
    return []

def select_shell(nodes, base):
    if base not in nodes:
        raise Unavailable('终端进程已结束')
    root = nodes[base]
    members = [p for p in nodes.values() if p['tty'] == root['tty'] and ancestry(nodes, p['pid'], base)]
    foreground = [p for p in members if p['pgrp'] == root['tpgid']]
    if not foreground:
        raise Unavailable('无法确认终端前台进程')
    if any(p['command'] in ('ssh', 'mosh', 'mosh-client', 'telnet', 'tmux', 'screen') for p in foreground):
        raise Unavailable('嵌套远程连接或终端会话期间暂停目录跟随')
    shells = [p for p in foreground if p['interactive']]
    if not shells and any(p['command'] in ('su', 'sudo', 'login', 'runuser') for p in foreground):
        raise Unavailable('终端正在切换用户，等待交互 Shell')
    if not shells:
        ancestors = set()
        for p in foreground:
            ancestors.update(ancestry(nodes, p['pid'], base))
        shells = [nodes[pid] for pid in ancestors if nodes[pid]['interactive']]
    if not shells:
        raise Unavailable('当前终端没有可识别的交互 Shell')
    return max(shells, key=lambda p: len(ancestry(nodes, p['pid'], base)))

def read_process(pid):
    prefix = '/proc/%d/' % pid
    with open(prefix + 'stat', 'rb') as f:
        stat = f.read(8192).decode('utf-8', 'replace')
    fields = stat[stat.rfind(')') + 2:].split()
    with open(prefix + 'cmdline', 'rb') as f:
        args = [a.decode('utf-8', 'replace') for a in f.read(8192).split(b'\0') if a]
    node = dict(pid=pid, ppid=int(fields[1]), pgrp=int(fields[2]), tty=int(fields[4]),
                tpgid=int(fields[5]), start=fields[19], command=os.path.basename(args[0]).lstrip('-') if args else '',
                interactive=is_interactive_shell(args))
    return node

def process_identity(pid):
    values = {}
    with open('/proc/%d/status' % pid, 'r') as f:
        for line in f:
            if line.startswith('Uid:'): values['uid'] = int(line.split()[2])
            if line.startswith('Gid:'): values['gid'] = int(line.split()[2])
    if len(values) != 2:
        raise Unavailable('无法读取终端用户身份')
    return values

def username(uid):
    try: return pwd.getpwuid(uid).pw_name
    except KeyError: return str(uid)

def plain_base(nodes):
    connection = os.environ.get('SSH_CONNECTION', '')
    if not connection:
        raise Unavailable('无法识别当前 SSH 终端')
    matched = []
    for pid, node in nodes.items():
        if not node['interactive'] or not node['tty']:
            continue
        try:
            with open('/proc/%d/environ' % pid, 'rb') as f:
                env = dict(part.split(b'=', 1) for part in f.read(65536).split(b'\0') if b'=' in part)
            if env.get(b'SSH_CONNECTION', b'').decode('utf-8', 'replace') == connection and env.get(b'SSH_TTY'):
                matched.append(node)
        except (IOError, OSError, ValueError): pass
    if not matched:
        raise Unavailable('无法识别此连接的交互 Shell（需要可读的 Linux /proc）')
    # The login shell is older than su/child shells inheriting the same environment.
    return min(matched, key=lambda p: int(p['start']))['pid']

def probe(base=None):
    result = dict(available=False, status='unavailable', message='', fileUid=os.geteuid(), fileGid=os.getegid(),
                  fileUser=username(os.geteuid()))
    try:
        if not sys.platform.startswith('linux') or not os.path.isdir('/proc'):
            raise Unavailable('目录跟随需要 Linux /proc；SFTP 原有功能仍可使用')
        nodes = {}
        deadline = time.time() + 2.0
        for name in os.listdir('/proc'):
            if time.time() > deadline or len(nodes) > 32768:
                raise Unavailable('终端进程探测超时，稍后重试')
            if not name.isdigit(): continue
            try: nodes[int(name)] = read_process(int(name))
            except (IOError, OSError, ValueError, IndexError): pass
        base = int(base) if base else plain_base(nodes)
        selected = select_shell(nodes, base)
        identity = process_identity(selected['pid'])
        result.update(terminalUid=identity['uid'], terminalGid=identity['gid'], terminalUser=username(identity['uid']))
        try: cwd = os.readlink('/proc/%d/cwd' % selected['pid'])
        except (IOError, OSError): raise Unavailable('原登录用户无法读取终端当前目录；文件操作权限保持原登录身份')
        if not cwd.startswith('/') or cwd.endswith(' (deleted)'):
            raise Unavailable('终端当前目录已删除或不可识别')
        after = read_process(selected['pid'])
        root_after = read_process(base)
        if after['start'] != selected['start'] or after['tty'] != selected['tty'] or root_after['tpgid'] != nodes[base]['tpgid'] or process_identity(selected['pid']) != identity:
            raise Unavailable('终端上下文正在变化，稍后重试')
        result.update(available=True, status='ready', cwd=cwd,
                      contextId='%d:%s:%d:%d' % (selected['pid'], selected['start'], identity['uid'], identity['gid']))
    except (Unavailable, IOError, OSError, ValueError, KeyError, IndexError) as error:
        result['message'] = str(error) if isinstance(error, Unavailable) else '终端上下文暂不可读，稍后重试'
    return result

if __name__ == '__main__':
    # Bound even NSS lookups and slow proc reads; the HTTP side also has a 4s deadline.
    signal.signal(signal.SIGALRM, lambda signum, frame: os._exit(124))
    signal.alarm(3)
    print(json.dumps(probe(sys.argv[1] if len(sys.argv) > 1 else None), ensure_ascii=True))
