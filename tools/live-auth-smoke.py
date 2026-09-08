#!/usr/bin/env python3
"""Opt-in device smoke test. Credentials never enter files, environment variables, or process arguments."""
import argparse
import getpass
import json
import re
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
    parser.add_argument('--timetable', action='store_true', help='Verify native academic SSO, timetable, calendar, clocks and UI')
    parser.add_argument('--grades', action='store_true', help='Verify native academic SSO, grade list, components and cache')
    parser.add_argument('--electric', action='store_true', help='Verify Neo SSO and read-only electricity queries')
    args = parser.parse_args()
    if sum((args.campus_code, args.timetable, args.grades, args.electric)) > 1:
        parser.error('Choose one of --campus-code, --timetable, --grades or --electric')
    test_timeout = 600 if args.campus_code else 300 if args.timetable or args.grades or args.electric else 180
    adb = [args.adb, '-s', args.serial]
    account = input('数字杭电账号: ').strip()
    password = getpass.getpass('数字杭电密码（不回显）: ')
    payload = json.dumps({'account': account, 'password': password}).encode()
    del account, password
    name = 'hdu-auth-test-' + uuid.uuid4().hex
    port = subprocess.check_output(adb + ['forward', 'tcp:0', 'localabstract:' + name], text=True).strip()
    runner = subprocess.Popen(adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
        ('moe.nepnep.hduhelper.LiveElectricTest' if args.electric else 'moe.nepnep.hduhelper.LiveGradesTest' if args.grades else 'moe.nepnep.hduhelper.LiveTimetableTest' if args.timetable else 'moe.nepnep.hduhelper.LiveCampusCodeTest' if args.campus_code else 'moe.nepnep.hduhelper.LiveAuthTest'), '-e', 'liveAuthSocket', name,
        'moe.nepnep.hduhelper.test/androidx.test.runner.AndroidJUnitRunner'],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    lines = []

    def collect():
        for line in runner.stdout:
            lines.append(line)
            print(line, end='', flush=True)
            if (args.campus_code or args.timetable) and line.strip() == 'INSTRUMENTATION_STATUS_CODE: 1':
                if args.timetable:
                    # Let ActivityScenario issue its launch first; an earlier competing launch can
                    # destroy its activity. Only assist if the system kept the test host backgrounded.
                    time.sleep(1)
                    activity = subprocess.run(adb + ['shell', 'dumpsys', 'activity', 'activities'],
                        check=False, capture_output=True, text=True).stdout
                    if re.search(r'(?:topResumedActivity|mResumedActivity).*moe\.nepnep\.hduhelper/androidx\.activity\.ComponentActivity', activity):
                        continue
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
                connection.settimeout(test_timeout)
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
        runner.wait(timeout=test_timeout)
        reader.join(timeout=5)
        if not any('OK (1 test)' in line for line in lines):
            raise RuntimeError('Live authentication smoke test failed')
    finally:
        subprocess.run(adb + ['forward', '--remove', 'tcp:' + port], check=False, capture_output=True)
        if runner.poll() is None:
            runner.terminate()


if __name__ == '__main__':
    main()
