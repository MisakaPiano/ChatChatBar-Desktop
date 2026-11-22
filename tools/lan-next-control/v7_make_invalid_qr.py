#!/usr/bin/env python3
"""Only a fixed synthetic invalid invitation; never consumes a real invitation."""
import argparse
import json
from pathlib import Path

PAYLOAD='CCB_SYNTHETIC_NOT_AN_INVITATION_V1'

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    try:
        import qrcode
    except ImportError:
        print(json.dumps({'prepared':False,'errorCode':'QR_ENCODER_UNAVAILABLE'}));return 2
    try:
        if a.output.exists() or not a.output.parent.is_dir():raise ValueError()
        qr=qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_H,box_size=16,border=8)
        qr.add_data(PAYLOAD);qr.make(fit=True);image=qr.make_image(fill_color='black',back_color='white').convert('RGB')
        with a.output.open('xb') as f:image.save(f,format='PNG')
    except Exception:
        print(json.dumps({'prepared':False,'errorCode':'QR_FIXTURE_WRITE_FAILED'}));return 2
    print(json.dumps({'prepared':True,'errorCode':None,'fixedSyntheticOnly':True}));return 0

if __name__=='__main__':raise SystemExit(main())
