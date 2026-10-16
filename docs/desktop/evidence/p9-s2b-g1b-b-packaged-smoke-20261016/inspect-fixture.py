import argparse, json, hashlib, os
from pathlib import Path
from datetime import datetime, timezone
import msvcrt

parser = argparse.ArgumentParser()
parser.add_argument("operation", choices=("snapshot", "lock"))
parser.add_argument("relative_root")
parser.add_argument("output")
args = parser.parse_args()
gate = Path(__file__).resolve().parent
root = (gate / args.relative_root).absolute()
assert root.is_relative_to(gate)
def sha(p):
    h=hashlib.sha256()
    with p.open("rb") as f:
        for b in iter(lambda:f.read(1024*1024),b""):h.update(b)
    return h.hexdigest()
result={"utc":datetime.now(timezone.utc).isoformat(),"rootAlias":args.relative_root}
if args.operation == "snapshot":
    rows=[]
    if root.exists():
        for p in sorted(root.rglob("*")):
            assert not p.is_symlink()
            rel=p.relative_to(root).as_posix()
            if p.is_dir(): rows.append({"path":rel,"kind":"directory"})
            else:
                row={"path":rel,"kind":"file","bytes":p.stat().st_size}
                if p.name==".ccb-desktop.lock": row["classification"]="ownership_infrastructure"
                else: row["sha256"]=sha(p)
                rows.append(row)
    result.update(rootExists=root.exists(),entries=rows)
else:
    lock=root/".ccb-desktop.lock"
    result["probe"]="separate-process nonblocking OS byte-range lock, existing file only"
    if not lock.is_file():result["state"]="MISSING"
    else:
        before=lock.stat().st_size
        fd=os.open(lock,os.O_RDWR|os.O_BINARY)
        try:
            try:
                msvcrt.locking(fd,msvcrt.LK_NBLCK,1)
            except OSError as e: result.update(state="HELD",errno=e.errno)
            else:
                msvcrt.locking(fd,msvcrt.LK_UNLCK,1)
                result["state"]="ACQUIRED_AND_RELEASED"
        finally:os.close(fd)
        assert lock.stat().st_size==before
        result["lockBytesUnchanged"]=before
out=gate/args.output
out.parent.mkdir(parents=True,exist_ok=True)
out.write_bytes(json.dumps(result,indent=2).encode("utf-8"))
print(json.dumps({k:v for k,v in result.items() if k!="entries"}))
