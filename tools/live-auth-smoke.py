#!/usr/bin/env python3
"""Opt-in device smoke test. Credentials never enter files, environment variables, or process arguments."""
import argparse
import getpass
import json
import socket
import struct
import subprocess
import threading
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--campus-code', action='store_true', help='Verify native campus QR and a complete automatic refresh period')
    args = parser.parse_args()
    adb = [args.adb, '-s', args.serial]
    account = input('数字杭电账号: ').strip()
    password = getpass.getpass('数字杭电密码（不回显）: ')
    payload = json.dumps({'account': account, 'password': password}).encode()
    del account, password
    name = 'hdu-auth-test-' + uuid.uuid4().hex
    port = subprocess.check_output(adb + ['forward', 'tcp:0', 'localabstract:' + name], text=True).strip()
    runner = subprocess.Popen(adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
        ('moe.nepnep.hduhelper.LiveCampusCodeTest' if args.campus_code else 'moe.nepnep.hduhelper.LiveAuthTest'), '-e', 'liveAuthSocket', name,
        'moe.nepnep.hduhelper.test/androidx.test.runner.AndroidJUnitRunner'],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    lines = []

    def collect():
        for line in runner.stdout:
            lines.append(line)
            print(line, end='', flush=True)
            if args.campus_code and line.strip() == 'INSTRUMENTATION_STATUS_CODE: 1':
                # Some Android variants block ActivityScenario launching a background test host.
                subprocess.run(adb + ['shell', 'am', 'start', '-W', '-a', 'android.intent.action.MAIN',
                    '-c', 'android.intent.category.LAUNCHER', '-n',
                    'moe.nepnep.hduhelper/androidx.activity.ComponentActivity'],
                    check=False, capture_output=True)

    reader = threading.Thread(target=collect, daemon=True)
    reader.start()
    try:
        deadline = time.monotonic() + 120
        while True:
            if runner.poll() is not None:
                raise RuntimeError('Device test stopped before accepting the connection')
            try:
                connection = socket.create_connection(('127.0.0.1', int(port)), timeout=3)
                connection.settimeout(600 if args.campus_code else 180)
                connection.sendall(struct.pack('>I', len(payload)) + payload)
                # ADB may accept a connection before the Android socket exists, then immediately close it.
                header = connection.recv(2)
                if len(header) == 2:
                    break
                connection.close()
            except OSError:
                if 'connection' in locals():
                    connection.close()
            if time.monotonic() >= deadline:
                raise TimeoutError('Device test did not become ready')
            time.sleep(0.5)
        del payload
        with connection:
            while header:
                size = struct.unpack('>H', header)[0]
                data = b''
                while len(data) < size:
                    chunk = connection.recv(size - len(data))
                    if not chunk:
                        raise EOFError('Incomplete test status')
                    data += chunk
                print(data.decode('utf-8'), flush=True)
                header = connection.recv(2)
        runner.wait(timeout=600 if args.campus_code else 180)
        reader.join(timeout=5)
        if not any('OK (1 test)' in line for line in lines):
            raise RuntimeError('Live authentication smoke test failed')
    finally:
        subprocess.run(adb + ['forward', '--remove', 'tcp:' + port], check=False, capture_output=True)
        if runner.poll() is None:
            runner.terminate()


if __name__ == '__main__':
    main()
