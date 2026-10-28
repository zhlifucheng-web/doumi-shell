# -*- coding: utf-8 -*-
"""Demand protocol integration tests; run with python3 -m unittest discover -s ... ."""
from __future__ import unicode_literals
import io
import json
import os
import select
import runpy
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

HELPER = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../main/resources/ssh/file-filter.py'))


class Session(object):
    def __init__(self, test, path, **options):
        test.assertTrue(os.path.isfile(HELPER), 'remote demand filter helper is missing')
        with io.open(HELPER, encoding='utf-8') as source:
            self.process = subprocess.Popen([os.environ.get('FILE_FILTER_PYTHON', sys.executable), '-u', '-c', source.read()],
                                            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                            stderr=subprocess.PIPE)
        test.addCleanup(self.close)
        config = dict(path=path, keyword='hit', encoding='UTF-8', caseSensitive=True, contextLines=0)
        config.update(options)
        self.send(json.dumps(config))
        self.ready = self.receive()

    def send(self, command):
        self.process.stdin.write((command + '\n').encode('utf-8'))
        self.process.stdin.flush()

    def receive(self):
        if not select.select([self.process.stdout], [], [], 5)[0]:
            raise AssertionError('helper did not return a protocol frame')
        line = self.process.stdout.readline()
        if not line:
            raise AssertionError('helper exited without a frame: ' + self.process.stderr.read().decode('utf-8', 'replace'))
        self.last_frame_size = len(line)
        return json.loads(line.decode('utf-8'))

    def next(self):
        self.send('next')
        return self.receive()

    def all(self):
        rows, batches = [], []
        for unused in range(100):
            batch = self.next()
            if batch['type'] == 'error':
                return rows, batches + [batch]
            rows.extend(batch['rows'])
            batches.append(batch)
            if batch['eof'] or batch['limited']:
                return rows, batches
        raise AssertionError('filter failed to finish in 100 requests')

    def close(self):
        if self.process.stdin and not self.process.stdin.closed:
            self.process.stdin.close()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait()
        self.process.stdout.close()
        self.process.stderr.close()


class FileFilterTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.mkdtemp(prefix='file-filter-')
        self.addCleanup(shutil.rmtree, self.directory)
        self.path = os.path.join(self.directory, 'test.log')

    def write(self, data, path=None, mode='wb'):
        with open(path or self.path, mode) as output:
            output.write(data)

    def test_ready_then_only_reads_on_demand_and_closes_on_stdin_eof(self):
        self.write(b'hit\nno\n')
        session = Session(self, self.path)
        self.assertEqual(dict(type='ready', path=self.path, totalSize=7), session.ready)
        self.assertEqual([], select.select([session.process.stdout], [], [], 0)[0])
        batch = session.next()
        self.assertEqual([dict(lineNumber=1, text='hit', matched=True)], batch['rows'])
        self.assertEqual((7, 7, True, False), (batch['offset'], batch['totalSize'], batch['eof'], batch['limited']))
        session.process.stdin.close()
        self.assertEqual(0, session.process.wait(timeout=5))

    def test_utf8_and_gbk_characters_split_at_demand_boundary(self):
        for encoding in ('UTF-8', 'GBK', 'GB18030', 'UTF-16LE', 'UTF-16BE'):
            # 256 KiB boundary falls in the encoded Chinese match or the newline before it.
            padding = ('x\n' * 65535 + 'x\n' * 65535).encode(encoding)
            self.write(padding + '中文\r\n'.encode(encoding))
            session = Session(self, self.path, keyword='中文', encoding=encoding)
            rows, batches = session.all()
            self.assertEqual([dict(lineNumber=131071, text='中文', matched=True)], rows)
            self.assertGreaterEqual(len(batches), 2)
            previous = 0
            for batch in batches:
                self.assertLessEqual(batch['offset'] - previous, 256 * 1024)
                previous = batch['offset']
            session.close()

    def test_split_multibyte_character_continues_after_snapshot_eof(self):
        for encoding in ('UTF-8', 'GBK', 'GB18030', 'UTF-16LE', 'UTF-16BE'):
            encoded = '中文\n'.encode(encoding)
            self.write(encoded[:1])
            session = Session(self, self.path, keyword='中文', encoding=encoding)
            self.assertEqual([], session.next()['rows'])
            self.write(encoded[1:], mode='ab')
            self.assertEqual([dict(lineNumber=1, text='中文', matched=True)], session.next()['rows'])
            session.close()

    def test_literal_metacharacters_paths_and_keywords_never_execute(self):
        marker = os.path.join(self.directory, 'PWNED')
        path = os.path.join(self.directory, "space ';$(touch PWNED)`quote`.log")
        keyword = '.*$(touch ' + marker + ')'
        self.write(('other\n' + keyword + '\n').encode('utf-8'), path=path)
        session = Session(self, path, keyword=keyword)
        self.assertEqual([dict(lineNumber=2, text=keyword, matched=True)], session.next()['rows'])
        self.assertFalse(os.path.exists(marker))

    def test_overlapping_contexts_emit_each_completed_line_once(self):
        self.write(b'a\nhit\nb\nhit\nc\nd\ne\n')
        session = Session(self, self.path, contextLines=2)
        batch = session.next()
        self.assertEqual([1, 2, 3, 4, 5, 6], [row['lineNumber'] for row in batch['rows']])
        self.assertEqual([False, True, False, True, False, False], [row['matched'] for row in batch['rows']])
        self.assertEqual([], session.next()['rows'])

    def test_partial_line_updates_preserve_number_context_and_unique_limit_count(self):
        self.write(b'before\nhit')
        session = Session(self, self.path, contextLines=2)
        self.assertEqual(['before', 'hit'], [row['text'] for row in session.next()['rows']])
        self.assertEqual([], session.next()['rows'])
        self.write(b' more\nafter', mode='ab')
        self.assertEqual([dict(lineNumber=2, text='hit more', matched=True),
                          dict(lineNumber=3, text='after', matched=False)], session.next()['rows'])
        self.write(b' done\nlast\n', mode='ab')
        self.assertEqual([dict(lineNumber=3, text='after done', matched=False),
                          dict(lineNumber=4, text='last', matched=False)], session.next()['rows'])

    def test_java_style_case_comparison_does_not_expand_sharp_s(self):
        self.write('SS\nß\nẞ\nİ\nı\ni\n'.encode('utf-8'))
        session = Session(self, self.path, keyword='ß', caseSensitive=False)
        self.assertEqual([2, 3], [row['lineNumber'] for row in session.next()['rows']])
        session = Session(self, self.path, keyword='i', caseSensitive=False)
        self.assertEqual([4, 5, 6], [row['lineNumber'] for row in session.next()['rows']])

    def test_truncation_rotation_and_same_size_rewrite_are_file_changed(self):
        for change in ('truncate', 'rotate', 'rewrite'):
            self.write(b'hit\noriginal\n')
            session = Session(self, self.path)
            self.assertEqual('batch', session.next()['type'])
            if change == 'truncate':
                self.write(b'hit\n')
            elif change == 'rotate':
                os.rename(self.path, self.path + '.old')
                self.write(b'hit\noriginal\n')
            else:
                self.write(b'hit\nreplaced\n')
            result = session.next()
            self.assertEqual(('error', 'FILE_CHANGED'), (result['type'], result['code']))
            session.close()

    def test_prefix_and_boundary_detect_rewrite_followed_by_growth(self):
        for anchor in ('prefix', 'boundary'):
            self.write(b'hit\n' + b'a\n' * 140000)
            session = Session(self, self.path)
            first = session.next()
            with open(self.path, 'r+b') as output:
                output.seek(0 if anchor == 'prefix' else first['offset'] - 2)
                output.write(b'Z\n')
                output.seek(0, os.SEEK_END)
                output.write(b'append\n')
            self.assertEqual('FILE_CHANGED', session.next()['code'])
            session.close()

    def test_invalid_encoding_is_a_chinese_error(self):
        self.write(b'\xffhit\n')
        session = Session(self, self.path)
        result = session.next()
        self.assertEqual('error', result['type'])
        self.assertIn('编码', result['message'])

    def test_supported_encoding_aliases(self):
        for encoding in ('UTF8', 'gb_18030', 'GBK', 'Big5', 'Shift_JIS', 'EUC-KR',
                         'ISO-8859-1', 'latin1', 'Windows-1252', 'UTF-16LE', 'UTF-16BE'):
            codec = 'gb18030' if encoding == 'gb_18030' else encoding
            self.write('hit\r\n'.encode(codec))
            session = Session(self, self.path, encoding=encoding)
            self.assertEqual('hit', session.next()['rows'][0]['text'])
            session.close()

    def test_line_limit_counts_utf16_units_and_rejects_oversized_line(self):
        for text in ('a' * 65537, '😀' * 32769):
            self.write(text.encode('utf-8'))
            session = Session(self, self.path)
            result = session.next()
            self.assertEqual('error', result['type'])
            self.assertIn('65536', result['message'])

    def test_row_limit_stops_at_ten_thousand_and_no_more_reads(self):
        self.write(b'hit\n' * 10002)
        session = Session(self, self.path)
        batch = session.next()
        self.assertEqual(10000, len(batch['rows']))
        self.assertTrue(batch['limited'])
        again = session.next()
        self.assertEqual([], again['rows'])
        self.assertEqual(batch['offset'], again['offset'])

    def test_result_limit_counts_provisional_replacements_once(self):
        self.write((b'x' * 65536 + b'\n') * 63 + b'x' * 65530)
        session = Session(self, self.path, keyword='x')
        rows, batches = session.all()
        self.assertFalse(batches[-1]['limited'])
        self.assertEqual(64, len(rows))
        self.write(b'xxxxxx', mode='ab')
        batch = session.next()
        self.assertTrue(batch['limited'])
        self.assertEqual(64, batch['rows'][0]['lineNumber'])
        self.assertEqual(65536, len(batch['rows'][0]['text']))

    def test_sparse_matches_scan_more_than_one_mib_without_raw_transfer(self):
        self.write(b'x\n' * 600000 + b'hit\n')
        session = Session(self, self.path)
        rows, batches = session.all()
        self.assertEqual([dict(lineNumber=600001, text='hit', matched=True)], rows)
        self.assertGreater(len(batches), 4)
        self.assertLess(sum(len(json.dumps(batch)) for batch in batches), 4096)

    def test_append_between_fd_and_path_stat_does_not_report_rotation(self):
        self.write(b'hit\n')
        module = runpy.run_path(HELPER)
        remote = module['RemoteFilter'](dict(path=self.path, keyword='hit'))
        self.addCleanup(remote.close)
        original_stat = os.stat
        appended = [False]

        def append_then_stat(path):
            if not appended[0]:
                appended[0] = True
                self.write(b'hit later\n', mode='ab')
            return original_stat(path)

        with mock.patch.object(module['os'], 'stat', side_effect=append_then_stat):
            try:
                batch = remote.next_batch()
            except module['FilterError'] as error:
                self.fail('ordinary concurrent append was rejected: ' + error.code)
            self.assertEqual('hit', batch['rows'][0]['text'])
        self.assertEqual([dict(lineNumber=2, text='hit later', matched=True)], remote.next_batch()['rows'])

    def test_raw_demand_budget_includes_verification_and_ready_reads_no_content(self):
        self.write(b'x\n' * 300000)
        module = runpy.run_path(HELPER)
        original_read = os.read
        read_sizes = []

        def record_read(fd, size):
            value = original_read(fd, size)
            read_sizes.append(len(value))
            return value

        with mock.patch.object(module['os'], 'read', side_effect=record_read):
            remote = module['RemoteFilter'](dict(path=self.path, keyword='hit'))
            self.addCleanup(remote.close)
            self.assertEqual([], read_sizes)
            for unused in range(3):
                del read_sizes[:]
                remote.next_batch()
                self.assertGreater(sum(read_sizes), 0)
                self.assertLessEqual(sum(read_sizes), 256 * 1024)

    def test_each_escaped_json_frame_stays_below_four_mib(self):
        # First demand retains two maximum-length context rows. Subsequent
        # control characters use six JSON bytes per input character.
        context = b'\x01' * 65536 + b'\n'
        self.write(b'z\n' * 65535 + context * 2 +
                   b'hit' + b'\x01' * 65533 + b'\n' + context * 3)
        session = Session(self, self.path, contextLines=2)
        while True:
            batch = session.next()
            self.assertEqual('batch', batch['type'])
            self.assertLess(session.last_frame_size, 4 * 1024 * 1024)
            if batch['eof']:
                break
        self.write(b'hit\n' * 10002)
        session.close()
        session = Session(self, self.path)
        self.assertEqual(10000, len(session.next()['rows']))
        self.assertLess(session.last_frame_size, 4 * 1024 * 1024)

    def test_rejects_fifo_without_waiting_for_a_writer(self):
        os.mkfifo(self.path)
        session = Session(self, self.path)
        self.assertEqual('error', session.ready['type'])
        self.assertIn('普通文件', session.ready['message'])

    def test_rejects_bad_config_and_bounded_commands(self):
        self.write(b'hit\n')
        for options in (dict(keyword=''), dict(contextLines=1), dict(encoding='not-a-codec'),
                        dict(keyword='a' * 65537), dict(caseSensitive='false')):
            session = Session(self, self.path, **options)
            self.assertEqual('error', session.ready['type'])
            session.close()
        session = Session(self, self.path)
        session.send('x' * 1000)
        self.assertEqual('error', session.receive()['type'])


if __name__ == '__main__':
    unittest.main()
