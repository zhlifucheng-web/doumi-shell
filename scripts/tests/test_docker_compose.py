"""Validate effective Compose deployment settings without starting any container."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]

@unittest.skipUnless(shutil.which('docker'), 'Docker CLI is needed for compose config validation')
class DockerComposeTest(unittest.TestCase):
    def config(self, **overrides):
        names=('DOUMI_IMAGE','GUACD_IMAGE','DOUMI_BIND_IP','DOUMI_HTTP_PORT','NODE_IMAGE','MAVEN_IMAGE','JAVA_IMAGE')
        env={k:v for k,v in os.environ.items() if k not in names}
        result=subprocess.run(['docker','compose','--env-file',os.devnull,'-f',str(ROOT/'compose.yaml'),'config','--format','json'],env={**env,**overrides},capture_output=True,text=True,check=True)
        return json.loads(result.stdout)

    def test_default_stack_waits_for_private_guacd_and_persists_data(self):
        stack=self.config();app=stack['services']['web-shell'];gateway=stack['services']['guacd']
        self.assertEqual('guacamole/guacd:1.6.0',gateway['image'])
        self.assertEqual('missing',gateway['pull_policy'])
        self.assertFalse(gateway.get('ports'))
        self.assertFalse(app.get('privileged'));self.assertNotIn('/var/run/docker.sock',str(app.get('volumes',[])))
        self.assertEqual('service_healthy',app['depends_on']['guacd']['condition'])
        self.assertEqual('guacd',app['environment']['DOUMI_GUACD_HOST'])
        self.assertEqual('4822',app['environment']['DOUMI_GUACD_PORT'])
        self.assertEqual('false',app['environment']['DOUMI_UPDATE_ENABLED'])
        self.assertEqual('127.0.0.1',app['ports'][0]['host_ip']);self.assertEqual('61880',app['ports'][0]['published'])
        volume=next(v for v in app['volumes'] if v['target']=='/data')
        self.assertEqual('volume',volume['type']);self.assertEqual('doumi-data',volume['source'])

    def test_host_binding_and_images_can_be_overridden_without_exposing_guacd(self):
        stack=self.config(DOUMI_BIND_IP='192.0.2.10',DOUMI_HTTP_PORT='31882',GUACD_IMAGE='mirror.example/guacd:1.6.0',JAVA_IMAGE='mirror.example/java:17')
        app=stack['services']['web-shell']
        self.assertEqual('192.0.2.10',app['ports'][0]['host_ip']);self.assertEqual('31882',app['ports'][0]['published'])
        self.assertEqual('mirror.example/guacd:1.6.0',stack['services']['guacd']['image'])
        self.assertEqual('mirror.example/java:17',app['build']['args']['JAVA_IMAGE'])
        self.assertFalse(stack['services']['guacd'].get('ports'))

if __name__=='__main__':unittest.main()
