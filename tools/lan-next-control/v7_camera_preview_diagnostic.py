#!/usr/bin/env python3
"""Cloud-only attach/read diagnostic; screenshot/QR contents never leave memory."""
from __future__ import annotations
import argparse
from importlib.metadata import version
import io
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import warnings

PACKAGE = 'com.example.chatbar.lansyncnext'
CAPTURE = PACKAGE + '/com.journeyapps.barcodescanner.CaptureActivity'
APK_SHA = 'ac8e1b84c6cc0327eb7c2dd5ccb4c2cab594298040939b68966d0379632ad7e0'
MARKER = 'CCB_SYNTHETIC_NOT_AN_INVITATION_V1'
MAX_BYTES = 8 * 1024 * 1024
MAX_PIXELS = 4_000_000

class Refused(Exception):
    pass

def need(condition, code):
    if not condition:
        raise Refused(code)

def guard(result_path):
    need(os.environ.get('GITHUB_ACTIONS') == 'true'
         and os.environ.get('RUNNER_OS') == 'Linux'
         and os.environ.get('GITHUB_REPOSITORY') == 'MisakaPiano/ChatChatBar-Desktop'
         and os.environ.get('CCB_V7_CAMERA_SYNTHETIC_DIAGNOSTIC') == 'true'
         and re.fullmatch('[0-9a-f]{40}', os.environ.get('GITHUB_SHA', '')) is not None,
         'SYNTHETIC_GITHUB_LINUX_ONLY')
    root = Path(os.environ.get('RUNNER_TEMP', '')).resolve(strict=True)
    need(root.is_dir() and bool(os.environ.get('RUNNER_TEMP')), 'RUNNER_TEMP_REQUIRED')
    # This fixed synthetic frame is prepared by the existing invalid-QR generator.
    work = root / 'ccb-v7-scan'
    need(work.is_dir() and not work.is_symlink() and work.resolve().is_relative_to(root),
         'SYNTHETIC_DIRECTORY_REQUIRED')
    need(result_path.name == 'camera-preview-result.json'
         and result_path.parent.resolve(strict=True) == work.resolve()
         and not result_path.exists() and not result_path.is_symlink(), 'NEW_RESULT_PATH_REQUIRED')
    reference = work / 'qr.png'
    need(reference.is_file() and not reference.is_symlink() and reference.stat().st_size <= MAX_BYTES,
         'FIXED_SYNTHETIC_FRAME_REQUIRED')
    return reference

class Adb:
    def __init__(self, executable, serial):
        self.prefix = [str(executable), '-s', serial]

    def raw(self, *arguments):
        try:
            proc = subprocess.run(self.prefix + list(arguments), capture_output=True, timeout=15)
        except (OSError, subprocess.TimeoutExpired):
            raise Refused('ADB_READ_FAILED') from None
        need(proc.returncode == 0 and len(proc.stdout) <= MAX_BYTES
             and len(proc.stderr) <= 128 * 1024, 'ADB_READ_FAILED')
        return proc.stdout

    def text(self, *arguments):
        return self.raw(*arguments).decode('utf-8', errors='replace')

    def capture_foreground(self):
        text = self.text('shell', 'dumpsys', 'activity', 'activities')
        return any(CAPTURE in line for line in text.splitlines()
                   if 'mResumedActivity' in line or 'topResumedActivity' in line)

def png_image(raw, Image):
    need(24 <= len(raw) <= MAX_BYTES and raw[:8] == b'\x89PNG\r\n\x1a\n'
         and raw[12:16] == b'IHDR', 'PNG_REQUIRED')
    width, height = struct.unpack('>II', raw[16:24])
    need(64 <= width <= 4096 and 64 <= height <= 4096
         and width * height <= MAX_PIXELS, 'DIAGNOSTIC_PIXEL_BUDGET')
    try:
        with Image.open(io.BytesIO(raw)) as image:
            need(image.format == 'PNG' and image.size == (width, height), 'PNG_REQUIRED')
            image.load()
            return image.convert('L')
    except Refused:
        raise
    except Exception:
        raise Refused('PNG_DECODE_FAILED') from None

def decoded(image, zxingcpp):
    # Decoder output objects stay private. Even unexpected barcodes are never printed.
    return zxingcpp.read_barcodes(image, formats=zxingcpp.BarcodeFormat.QRCode,
                                 try_rotate=True, try_downscale=True, return_errors=False)

def analyze(args, reference, result):
    need(version('Pillow') == '11.3.0' and version('zxing-cpp') == '2.3.0',
         'PINNED_DECODERS_REQUIRED')
    from PIL import Image
    import zxingcpp
    Image.MAX_IMAGE_PIXELS = MAX_PIXELS
    warnings.simplefilter('error', Image.DecompressionBombWarning)
    reference_image = png_image(reference.read_bytes(), Image)
    codes = decoded(reference_image, zxingcpp)
    need(len(codes) == 1 and codes[0].text == MARKER, 'REFERENCE_MARKER_NOT_DECODED')
    result['referenceFixedMarkerDecoded'] = True
    need(re.fullmatch(r'emulator-[0-9]+', args.serial) is not None, 'EMULATOR_SERIAL_REQUIRED')
    adb = Adb(args.adb, args.serial)
    # No install, launch, input, clear, logcat, shell script, or app-file reads.
    devices = adb.text('devices').splitlines()
    rows = [line.split() for line in devices if re.match(r'^\S+\s+(?:device|offline|unauthorized)\b', line)]
    need(rows == [[args.serial, 'device']], 'UNIQUE_ONLINE_EMULATOR_REQUIRED')
    need(adb.text('shell', 'getprop', 'ro.kernel.qemu').strip() == '1'
         and adb.text('shell', 'getprop', 'ro.build.version.sdk').strip() == '36', 'API36_QEMU_REQUIRED')
    package = adb.text('shell', 'dumpsys', 'package', PACKAGE)
    need(re.search(r'\bversionCode=86\b', package) is not None, 'VERSION86_REQUIRED')
    paths = adb.text('shell', 'pm', 'path', PACKAGE).splitlines()
    need(len(paths) == 1 and re.fullmatch(r'package:/data/app/[A-Za-z0-9_./=+~-]+/base\.apk', paths[0])
         is not None and '..' not in paths[0].split('/'), 'ONE_INSTALLED_BASE_APK_REQUIRED')
    digest = adb.text('shell', 'toybox', 'sha256sum', paths[0][8:]).split()
    need(bool(digest) and digest[0] == APK_SHA, 'EXACT_SIGNED86_REQUIRED')
    result['exactSigned86Api36EmulatorObserved'] = True
    need(adb.capture_foreground(), 'CAPTURE_NOT_FOREGROUND_BEFORE')
    result['captureForegroundBefore'] = True
    # exec-out preserves PNG bytes. There is no screenshot path on host or guest.
    raw = adb.raw('exec-out', 'screencap', '-p')
    need(adb.capture_foreground(), 'CAPTURE_NOT_FOREGROUND_AFTER')
    result['captureForegroundAfter'] = True
    image = png_image(raw, Image)
    del raw
    result['screenshotPngCapturedInMemory'] = True
    width, height = image.size
    # Conservative coordinate diagnostic, NOT the actual ZXing view/preview ROI.
    side = int(min(width, height) * 0.8)
    left, top = (width - side) // 2, (height - side) // 2
    roi = (left, top, left + side, top + side)
    center = image.crop(roi)
    minimum, maximum = center.getextrema()
    result['estimatedCenterHasContrast'] = maximum - minimum >= 64
    full = decoded(image, zxingcpp)
    matches = [code for code in full if code.text == MARKER]
    result['fixedMarkerDecodedFullFrame'] = bool(matches)
    result['unexpectedQrDecoded'] = any(code.text != MARKER for code in full)
    result['fixedMarkerDecodedEstimatedCenter'] = any(code.text == MARKER for code in decoded(center, zxingcpp))
    result['fixedMarkerPolygonInsideEstimatedCenter'] = any(
        all(roi[0] <= point.x < roi[2] and roi[1] <= point.y < roi[3]
            for point in (code.position.top_left, code.position.top_right,
                          code.position.bottom_right, code.position.bottom_left))
        for code in matches)
    result['diagnosticCompleted'] = True
    result['status'] = 'DIAGNOSTIC_COMPLETED'

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--result', type=Path, required=True)
    args = parser.parse_args(argv)
    # Only these fixed enums, booleans/null are ever serialized. No raw/QR/hash/coords/tree/log.
    result = dict(kind='CCB_V7_SYNTHETIC_CAPTURE_PREVIEW_DIAGNOSTIC_V1',
                  status='NOT_RUN', errorCode=None, diagnosticCompleted=False,
                  referenceFixedMarkerDecoded=False, exactSigned86Api36EmulatorObserved=False,
                  captureForegroundBefore=False, captureForegroundAfter=False,
                  screenshotPngCapturedInMemory=False, estimatedCenterHasContrast=None,
                  fixedMarkerDecodedFullFrame=None, fixedMarkerDecodedEstimatedCenter=None,
                  fixedMarkerPolygonInsideEstimatedCenter=None, unexpectedQrDecoded=None,
                  rawImageSaved=False, rawImageExported=False, appStateWritten=False,
                  scanCallbackProven=False, actualZxingPreviewRoiProven=False, humanAcceptance=False)
    # Refusing a local invocation performs no filesystem write or adb call.
    try:
        reference = guard(args.result)
    except Exception:
        print(json.dumps(dict(kind=result['kind'], status='REFUSED', errorCode='CLOUD_GUARD_REFUSED')))
        return 2
    try:
        analyze(args, reference, result)
        rc = 0
    except Refused as error:
        result.update(status='REFUSED', errorCode=str(error))
        rc = 2
    except Exception:
        result.update(status='REFUSED', errorCode='DIAGNOSTIC_EXCEPTION')
        rc = 2
    try:
        with args.result.open('x', encoding='utf-8', newline='\n') as output:
            json.dump(result, output, ensure_ascii=True, indent=2)
            output.write('\n')
    except OSError:
        print(json.dumps(dict(kind=result['kind'], status='REFUSED', errorCode='RESULT_WRITE_FAILED')))
        return 2
    print(json.dumps(result, ensure_ascii=True))
    return rc

if __name__ == '__main__':
    raise SystemExit(main())
