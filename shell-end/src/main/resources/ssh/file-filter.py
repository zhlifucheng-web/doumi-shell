# -*- coding: utf-8 -*-
"""Bounded, demand-driven remote literal file filter (Python 2.7 / Python 3).

Start with python -u -c <this source>. Configuration is a UTF-8 JSON stdin
line; each subsequent `next` line authorizes one read. No path or keyword is
interpreted as shell syntax. EOF is a snapshot, never decoder finalization.

Case-insensitive matching follows Java's per-character upper-then-lower
comparison without multi-character case expansions. Unicode table versions
can differ between Python and the JVM; supplementary case mappings also
vary on Python 2 narrow builds. No locale-sensitive or full case folding.
"""
from __future__ import unicode_literals

import codecs
import collections
import json
import os
import stat
import sys

try:
    TEXT = unicode
except NameError:
    TEXT = str

READ_BUDGET = 256 * 1024
ANCHOR_BYTES = 4096
MAX_LINE = 65536
MAX_ROWS = 10000
MAX_RESULT = 4 * 1024 * 1024
MAX_CONFIG = 512 * 1024
MAX_COMMAND = 16


class FilterError(Exception):
    def __init__(self, code, message):
        Exception.__init__(self, message)
        self.code = code
        self.message = message


def invalid(message):
    return FilterError('INVALID_CONFIG', '筛选参数无效: ' + message)


def changed():
    return FilterError('FILE_CHANGED', '文件已改变或截断，请重新筛选')


def utf16_length(text):
    # Correct for both wide Python 3 and narrow/wide Python 2 unicode builds.
    return sum(2 if ord(character) > 0xffff else 1 for character in text)


def utf16_clip(text, limit, suffix=False):
    """Clip at a Unicode character boundary, including Python 2 narrow builds."""
    units = 0
    if suffix:
        index = len(text)
        while index:
            width = 2 if ord(text[index - 1]) > 0xffff else 1
            size = 1
            if (0xdc00 <= ord(text[index - 1]) <= 0xdfff and index > 1 and
                    0xd800 <= ord(text[index - 2]) <= 0xdbff):
                width, size = 2, 2
            if units + width > limit:
                break
            units += width
            index -= size
        return text[index:], units
    index = 0
    while index < len(text):
        width = 2 if ord(text[index]) > 0xffff else 1
        size = 1
        if (0xd800 <= ord(text[index]) <= 0xdbff and index + 1 < len(text) and
                0xdc00 <= ord(text[index + 1]) <= 0xdfff):
            width, size = 2, 2
        if units + width > limit:
            break
        units += width
        index += size
    return text[:index], units


def simple_fold(text):
    result = []
    for character in text:
        upper = character.upper()
        if len(upper) != 1:
            upper = character
        lower = upper.lower()
        # Python's full lower mapping expands this; Java Character maps to i.
        if upper == '\u0130':
            lower = 'i'
        elif len(lower) != 1:
            lower = upper
        result.append(lower)
    return ''.join(result)


def decoder_for(encoding):
    if encoding is None or encoding == '':
        encoding = 'UTF-8'
    if not isinstance(encoding, TEXT) or len(encoding) > 64:
        raise invalid('编码名称无效')
    # The UI's stateless text encodings. Whitelisting excludes transformation
    # codecs and stateful codecs with potentially unbounded decoder carry.
    aliases = {
        'utf8': 'utf-8', 'gbk': 'gbk', 'cp936': 'gbk',
        'ms936': 'gbk', 'gb2312': 'gb2312', 'gb18030': 'gb18030',
        'big5': 'big5', 'cp950': 'big5', 'shiftjis': 'shift_jis',
        'sjis': 'shift_jis', 'mskanji': 'shift_jis', 'euckr': 'euc_kr',
        'iso88591': 'latin-1', 'latin1': 'latin-1', 'l1': 'latin-1',
        'windows1252': 'cp1252', 'cp1252': 'cp1252',
        'utf16le': 'utf-16-le', 'utf16be': 'utf-16-be',
        'ascii': 'ascii', 'usascii': 'ascii',
    }
    key = encoding.lower().replace('-', '').replace('_', '').replace(' ', '')
    codec = aliases.get(key)
    if codec is None:
        raise invalid('不支持所选编码，请更换编码')
    try:
        return codecs.getincrementaldecoder(codec)('strict')
    except LookupError:
        raise invalid('远端 Python 不支持所选编码，请更换编码')


class LineBuffer(object):
    """Retain a bounded preview and overlap while searching the entire line."""
    def __init__(self, keyword, case_sensitive, keyword_units):
        self.keyword = keyword
        self.keyword_units = keyword_units
        self.case_sensitive = case_sensitive
        self.preview = ''
        self.preview_units = 0
        self.preview_closed = False
        self.seen_units = 0
        self.matched = False
        self.overlap = ''
        self.pending_cr = False

    def extend_preview(self, text):
        if not self.preview_closed:
            clipped, units = utf16_clip(text, MAX_LINE - self.preview_units)
            self.preview += clipped
            self.preview_units += units
            self.preview_closed = len(clipped) < len(text) or self.preview_units == MAX_LINE

    def append(self, text):
        if not text:
            return
        if self.pending_cr:
            text = '\r' + text
        self.pending_cr = text.endswith('\r')
        if self.pending_cr:
            text = text[:-1]
        if not text:
            return
        self.extend_preview(text)
        if not self.matched:
            window = self.overlap + text
            haystack = window if self.case_sensitive else simple_fold(window)
            start = haystack.find(self.keyword)
            if start >= 0:
                end = start + len(self.keyword)
                match_end = self.seen_units - utf16_length(self.overlap) + utf16_length(window[:end])
                self.matched = True
                if match_end > MAX_LINE:
                    before, unused = utf16_clip(window[:start], min(128, MAX_LINE - self.keyword_units), True)
                    self.preview = ''
                    self.preview_units = 0
                    self.preview_closed = False
                    self.extend_preview(before + window[start:])
                # No matching carry is needed after the first match.
                self.overlap = ''
            else:
                # Keyword units minus one retain every cross-chunk match;
                # another 128 units retain the late snippet's lead-in.
                self.overlap, unused = utf16_clip(window, self.keyword_units - 1 + 128, True)
        self.seen_units += utf16_length(text)

    def row(self, number):
        return dict(lineNumber=number, text=self.preview, matched=self.matched,
                    truncated=self.seen_units > MAX_LINE)

    def has_content(self):
        return self.seen_units > 0 or self.pending_cr


class LineScanner(object):
    def __init__(self, config):
        keyword = config.get('keyword')
        if not isinstance(keyword, TEXT) or not keyword or utf16_length(keyword) > MAX_LINE:
            raise invalid('关键字不能为空且不能超过 65536 字符')
        self.case_sensitive = config.get('caseSensitive', True)
        if not isinstance(self.case_sensitive, bool):
            raise invalid('区分大小写参数必须为布尔值')
        context = config.get('contextLines', 0)
        if isinstance(context, bool) or context not in (0, 2):
            raise invalid('上下文只能为 0 或 2 行')
        self.context = context
        self.keyword = keyword if self.case_sensitive else simple_fold(keyword)
        self.keyword_units = utf16_length(self.keyword)
        self.decoder = decoder_for(config.get('encoding'))
        self.line = LineBuffer(self.keyword, self.case_sensitive, self.keyword_units)
        self.line_number = 1
        self.before = collections.deque()
        self.after_until = 0
        self.last_emitted = 0
        self.last_row = None
        self.last_length = 0
        self.count = 0
        self.result_chars = 0
        self.limited = False

    def current(self):
        return self.line.row(self.line_number)

    def emit(self, row, rows):
        number, text = row['lineNumber'], row['text']
        if self.limited or number < self.last_emitted:
            return
        if number == self.last_emitted and row == self.last_row:
            return
        length = utf16_length(text)
        added = length - (self.last_length if number == self.last_emitted else 0)
        if added > MAX_RESULT - self.result_chars:
            self.limited = True
            return
        if number > self.last_emitted:
            self.count += 1
            self.last_emitted = number
        self.result_chars += added
        self.last_length = length
        self.last_row = row
        rows[number] = row
        if self.count >= MAX_ROWS or self.result_chars >= MAX_RESULT:
            self.limited = True

    def select(self, row, rows):
        if row['matched']:
            for previous in self.before:
                if previous['lineNumber'] > self.last_emitted:
                    self.emit(previous, rows)
            self.after_until = row['lineNumber'] + self.context
        if row['matched'] or row['lineNumber'] <= self.after_until:
            self.emit(row, rows)

    def append(self, text):
        self.line.append(text)

    def accept(self, data, eof):
        rows = collections.OrderedDict()
        if self.limited:
            return []
        try:
            decoded = self.decoder.decode(data, False)
        except UnicodeError:
            raise FilterError('ENCODING_ERROR', '文件内容与所选编码不匹配，请更换编码后重新筛选')
        carry = self.decoder.getstate()[0]
        if len(carry) > 16:
            raise FilterError('ENCODING_ERROR', '编码解析缓存超出限制，请更换编码后重新筛选')
        parts = decoded.split('\n')
        for index, part in enumerate(parts):
            if self.limited:
                break
            self.append(part)
            if index < len(parts) - 1:
                completed = self.current()
                self.select(completed, rows)
                if self.context:
                    self.before.append(completed)
                    while len(self.before) > self.context:
                        self.before.popleft()
                self.line = LineBuffer(self.keyword, self.case_sensitive, self.keyword_units)
                self.line_number += 1
        if eof and self.line.has_content() and not self.limited:
            self.select(self.current(), rows)
        return list(rows.values())


class RemoteFilter(object):
    def __init__(self, config):
        if not isinstance(config, dict):
            raise invalid('配置必须为 JSON 对象')
        self.scanner = LineScanner(config)
        self.path = config.get('path')
        if (not isinstance(self.path, TEXT) or not self.path or
                '\x00' in self.path or len(self.path) > 16384):
            raise invalid('文件路径无效')
        self.fd = None
        self.offset = 0
        self.prefix = b''
        self.boundary = b''
        try:
            flags = os.O_RDONLY | os.O_NONBLOCK | getattr(os, 'O_CLOEXEC', 0)
            self.fd = os.open(self.path, flags)
            opened = os.fstat(self.fd)
            if not stat.S_ISREG(opened.st_mode):
                raise FilterError('FILE_ERROR', '筛选仅支持普通文件')
            self.identity = (opened.st_dev, opened.st_ino)
            self.last_size = opened.st_size
            self.last_mtime = self.mtime(opened)
            self.snapshot()
        except Exception:
            self.close()
            raise

    @staticmethod
    def mtime(info):
        return getattr(info, 'st_mtime_ns', info.st_mtime)

    def close(self):
        if self.fd is not None:
            os.close(self.fd)
            self.fd = None

    def ready(self):
        return dict(type='ready', path=self.path, totalSize=self.last_size)

    def snapshot(self):
        try:
            opened, named = os.fstat(self.fd), os.stat(self.path)
        except OSError:
            raise changed()
        if ((opened.st_dev, opened.st_ino) != self.identity or
                (named.st_dev, named.st_ino) != self.identity or
                not stat.S_ISREG(named.st_mode) or
                opened.st_size < self.last_size or
                (opened.st_size == self.last_size and self.mtime(opened) != self.last_mtime)):
            raise changed()
        # stat calls are separate snapshots: ordinary append can legitimately
        # change size/mtime between them. Path stat establishes identity only.
        return opened

    def read_exact(self, offset, length):
        os.lseek(self.fd, offset, os.SEEK_SET)
        parts = []
        while length:
            data = os.read(self.fd, length)
            if not data:
                raise changed()
            parts.append(data)
            length -= len(data)
        return b''.join(parts)

    def next_batch(self):
        snapshot = self.snapshot()
        size = snapshot.st_size
        # Every raw read, including anchor verification, is inside this demand.
        verified = 0
        for offset, expected in ((0, self.prefix),
                                 (self.offset - len(self.boundary), self.boundary)):
            if expected and self.read_exact(offset, len(expected)) != expected:
                raise changed()
            verified += len(expected)
        data = b''
        if not self.scanner.limited:
            length = min(READ_BUDGET - verified, size - self.offset)
            data = self.read_exact(self.offset, length)
        # Catch truncation/rotation/rewrite during the read before emitting rows.
        after = self.snapshot()
        if after.st_size < size or (after.st_size == size and self.mtime(after) != self.mtime(snapshot)):
            raise changed()
        eof = self.offset + len(data) == size
        rows = self.scanner.accept(data, eof)
        if len(self.prefix) < ANCHOR_BYTES:
            self.prefix += data[:ANCHOR_BYTES - len(self.prefix)]
        self.boundary = (self.boundary + data)[-ANCHOR_BYTES:]
        self.offset += len(data)
        self.last_size = size
        self.last_mtime = self.mtime(snapshot)
        return dict(type='batch', rows=rows, offset=self.offset, totalSize=size,
                    eof=eof, limited=self.scanner.limited)


def write_frame(frame):
    # ASCII JSON also works with Python 2 terminals configured with ASCII.
    sys.stdout.write(json.dumps(frame, ensure_ascii=True, separators=(',', ':')) + '\n')
    sys.stdout.flush()


def bounded_line(stream, limit):
    line = stream.readline(limit + 1)
    if not line:
        return None
    if len(line) > limit or not line.endswith(b'\n'):
        raise FilterError('PROTOCOL_ERROR', '筛选命令超出长度限制或缺少换行符')
    return line[:-1]


def main():
    remote = None
    stream = getattr(sys.stdin, 'buffer', sys.stdin)
    try:
        line = bounded_line(stream, MAX_CONFIG)
        if line is None:
            return
        try:
            config = json.loads(line.decode('utf-8'))
        except (ValueError, UnicodeError):
            raise invalid('配置必须为有效 UTF-8 JSON')
        remote = RemoteFilter(config)
        write_frame(remote.ready())
        while True:
            command = bounded_line(stream, MAX_COMMAND)
            if command is None:
                return
            if command != b'next':
                raise FilterError('PROTOCOL_ERROR', '不支持的筛选命令')
            write_frame(remote.next_batch())
    except FilterError as error:
        write_frame(dict(type='error', code=error.code, message=error.message))
    except (IOError, OSError):
        # A closed SSH channel may also make this final write fail; finally
        # still closes the retained descriptor and the helper then exits.
        try:
            write_frame(dict(type='error', code='FILE_ERROR', message='无法打开或读取远端文件，请检查权限和路径'))
        except (IOError, OSError):
            pass
    finally:
        if remote is not None:
            remote.close()


if __name__ == '__main__':
    main()
