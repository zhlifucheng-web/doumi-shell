import importlib.util
import pathlib
import unittest
import sys
sys.dont_write_bytecode = True

source = pathlib.Path(__file__).parents[2] / 'main/resources/ssh/terminal-context.py'

class TerminalContextTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location('terminal_context', str(source))
        cls.probe = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.probe)

    def shell(self, pid, parent=0, group=None, foreground=None, user=0, cwd='/root'):
        return dict(pid=pid, ppid=parent, pgrp=group or pid, tpgid=foreground or group or pid,
                    tty=99, command='bash', interactive=True, uid=user, gid=user, start=str(pid), cwd=cwd)

    def test_cd_uses_actual_shell_cwd(self):
        shell = self.shell(10, cwd='/srv/app with space')
        self.assertEqual('/srv/app with space', self.probe.select_shell({10:shell}, 10)['cwd'])

    def test_su_and_nested_shell_follow_then_exit_returns_parent(self):
        a=self.shell(10, foreground=20)
        b=self.shell(20, parent=10);b.update(command='su', interactive=False)
        c=self.shell(21, parent=20, group=20, user=1001, cwd='/home/app')
        selected=self.probe.select_shell({10:a,20:b,21:c},10)
        self.assertEqual((21,1001,'/home/app'),(selected['pid'],selected['uid'],selected['cwd']))
        a['tpgid']=10
        self.assertEqual(10,self.probe.select_shell({10:a},10)['pid'])

    def test_running_tool_and_noninteractive_shell_do_not_change_cwd(self):
        a=self.shell(10, foreground=20)
        tool=self.shell(20, parent=10, cwd='/tmp');tool.update(command='tail', interactive=False)
        self.assertEqual(10,self.probe.select_shell({10:a,20:tool},10)['pid'])
        tool.update(command='bash', interactive=False)
        self.assertEqual(10,self.probe.select_shell({10:a,20:tool},10)['pid'])

    def test_nested_ssh_and_pending_su_suspend_instead_of_guessing(self):
        a=self.shell(10, foreground=20)
        tool=self.shell(20,parent=10);tool.update(command='ssh', interactive=False)
        with self.assertRaises(self.probe.Unavailable): self.probe.select_shell({10:a,20:tool},10)
        tool['command']='su'
        with self.assertRaises(self.probe.Unavailable): self.probe.select_shell({10:a,20:tool},10)

    def test_unrelated_terminal_never_replaces_current(self):
        a=self.shell(10, foreground=10)
        other=self.shell(21, parent=1, group=10, cwd='/other');other['tty']=100
        self.assertEqual(10,self.probe.select_shell({10:a,21:other},10)['pid'])

    def test_interactive_arguments(self):
        for args in [['-bash'],['bash'],['bash','-i'],['zsh','-l'],['fish','--login']]:
            self.assertTrue(self.probe.is_interactive_shell(args),args)
        for args in [['bash','script.sh'],['bash','-c','cd /tmp'],['ssh','somewhere'],['python']]:
            self.assertFalse(self.probe.is_interactive_shell(args),args)

if __name__ == '__main__': unittest.main()
